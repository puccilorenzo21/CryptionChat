package com.lollo1344.cryptionchat

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object SecurePrefs {

    private const val PREFS_FILENAME = "cryptionchat_secure_prefs"
    private const val TINK_KEYSET_FILENAME = "__androidx_security_crypto_encrypted_shared_preferences_keyset__"

    @Volatile
    private var sharedPreferencesInstance: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        return sharedPreferencesInstance ?: synchronized(this) {
            sharedPreferencesInstance ?: createSecurePrefs(context.applicationContext).also {
                sharedPreferencesInstance = it
            }
        }
    }

    private fun openEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILENAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Synchronized
    private fun createSecurePrefs(context: Context): SharedPreferences {
        try {
            return openEncryptedPrefs(context)
        } catch (_: Exception) {
            try {
                // Retry singolo per errori transitori Keystore/concorrenza prima di eseguire il wipe
                Thread.sleep(50)
                return openEncryptedPrefs(context)
            } catch (e: Exception) {
                Log.e("SecurePrefs", "Impossibile caricare le preferenze crittografate. Eseguo riparazione avanzata.", e)
                // Prima del wipe forzato, elimina il DB locale cifrato per non lasciarlo orfano con una passphrase persa.
                try {
                    AppDatabase.deleteLocalDatabase(context)
                } catch (_: Throwable) {
                }

            // Silent Bug Reporting
            try {
                BugReporter.sendAutoReport(
                    context = context,
                    errorTag = "PREFS_CORRUPTION",
                    message = "Fallita decifratura/inizializzazione EncryptedSharedPreferences. Eseguito wipe forzato del file, della cache, del keyset Tink e della chiave nel Keystore.",
                    throwable = e
                )
            } catch (ex: Exception) {
                Log.e("SecurePrefs", "Errore durante l'invio del report automatico", ex)
            }

            // 1. Svuota l'istanza in memoria del sistema e pulisce i vecchi dati
            try {
                context.getSharedPreferences(PREFS_FILENAME, Context.MODE_PRIVATE).edit().clear().commit()
                context.getSharedPreferences(TINK_KEYSET_FILENAME, Context.MODE_PRIVATE).edit().clear().commit()
                Log.i("SecurePrefs", "Cache in-memory delle SharedPreferences e del Keyset Tink pulita.")
            } catch (ex: Exception) {
                Log.e("SecurePrefs", "Errore durante il clear in-memory delle SharedPreferences", ex)
            }

            // 2. Eliminazione fisica e nativa del file XML delle preferenze e dei file di keyset di Tink
            // L'uso di deleteSharedPreferences() è fondamentale per svuotare la cache statica dell'OS
            try {
                context.deleteSharedPreferences(PREFS_FILENAME)
                context.deleteSharedPreferences(TINK_KEYSET_FILENAME)
                Log.i("SecurePrefs", "File delle preferenze e file keyset di Tink rimossi nativamente con successo.")
            } catch (ex: Exception) {
                Log.e("SecurePrefs", "Errore nell'eliminazione nativa. Tento eliminazione manuale fallback.", ex)
                try {
                    val prefsFile = File(context.filesDir.parent, "shared_prefs/${PREFS_FILENAME}.xml")
                    if (prefsFile.exists()) prefsFile.delete()
                    val backupFile = File(context.filesDir.parent, "shared_prefs/${PREFS_FILENAME}.bak")
                    if (backupFile.exists()) backupFile.delete()

                    val keysetFile = File(context.filesDir.parent, "shared_prefs/${TINK_KEYSET_FILENAME}.xml")
                    if (keysetFile.exists()) keysetFile.delete()
                    val keysetBackupFile = File(context.filesDir.parent, "shared_prefs/${TINK_KEYSET_FILENAME}.bak")
                    if (keysetBackupFile.exists()) keysetBackupFile.delete()
                } catch (folderEx: Exception) {
                    Log.e("SecurePrefs", "Fallito anche il recupero manuale dei file XML", folderEx)
                }
            }

            // 3. Rimozione totale dei possibili alias della MasterKey dal Keystore di Android
            try {
                val keyStore = KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)

                val aliasesToDelete = listOf(
                    MasterKey.DEFAULT_MASTER_KEY_ALIAS,
                    "_androidx_security_master_key_" // Alias statico di fallback
                )

                for (alias in aliasesToDelete) {
                    if (keyStore.containsAlias(alias)) {
                        keyStore.deleteEntry(alias)
                        Log.i("SecurePrefs", "Alias '$alias' rimosso con successo dal Keystore.")
                    }
                }
            } catch (ke: Exception) {
                Log.e("SecurePrefs", "Errore durante l'eliminazione della MasterKey dal Keystore", ke)
            }

            // 4. Secondo tentativo di generazione con nuove preferenze e chiavi vergini
            return try {
                openEncryptedPrefs(context)
            } catch (ex2: Exception) {
                Log.e("SecurePrefs", "Fallito anche il secondo tentativo di EncryptedSharedPreferences. Utilizzo vault solo in RAM per evitare scrittura in chiaro su disco.", ex2)
                // 5. Fallback sicuro solo in memoria RAM: nessun segreto viene mai persistito in chiaro su XML
                InMemorySharedPreferences()
            }
            }
        }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = ConcurrentHashMap<String, Any?>()
        private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = key != null && data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) pending[key] = values?.toSet()
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) {
                    data.clear()
                }
                for ((k, v) in pending) {
                    if (v == null) data.remove(k) else data[k] = v
                    listeners.forEach { it.onSharedPreferenceChanged(this@InMemorySharedPreferences, k) }
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners.remove(listener)
        }
    }
}