package com.lollo1344.cryptionchat

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Classe Application personalizzata.
 * Gestisce l'inizializzazione globale dei componenti e la migrazione dei dati.
 */
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Cancella i rinvii di sessione temporanei e i permessi saltati ad ogni avvio a freddo (Cold Start)
        try {
            val prefs = SecurePrefs.get(this)
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith("session_postponed_") }.forEach {
                editor.remove(it)
            }
            // Rimuove il bypass temporaneo del permesso di overlay per rivalutarlo al prossimo avvio pulito
            editor.remove("ignore_overlay_permission")
            editor.apply()
            Log.i("MyApplication", "Reset dei rinvii temporanei di sessione e permessi ignorati completato.")
        } catch (e: Exception) {
            Log.e("MyApplication", "Errore durante il reset dei rinvii di sessione", e)
        }

        // 1. Configurazione Tema
        // Forza l'app a seguire la modalità Giorno/Notte del sistema.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        // 1.5 Inizializzazione Double Ratchet, Identity Keys, X3DH, Sender Keys e LocalVault cifrato
        DoubleRatchetHelper.init(this)
        IdentityKeyManager.init(this)
        X3dhHelper.init(this)
        SenderKeyHelper.init(this)

        // 2. Inizializzazione Crash Reporter
        CrashReporter.init(this)

        // 3. Inizializzazione Gestore Ciclo di Vita (per stato Online/Offline)
        AppLifecycleManager.initialize(this)

        // 4. Inizializzazione Client API (Carica Proxy se configurato)
        // NECESSARIO per le nuove funzioni di sicurezza
        ApiClient.init(this)

        // 4.5 Pulizia asincrona all'avvio di eventuali APK di aggiornamento già installati o obsoleti
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AutoUpdateManager.getInstance(this@MyApplication).cleanupIfUpdatedOrObsolete()
            } catch (e: Exception) {
                Log.w("MyApplication", "Errore pulizia APK all'avvio: ${e.message}")
            }
        }

        // 5. Gestione Migrazione Dati (da vecchie versioni)
        handleDataMigration(this)
    }

    private fun handleDataMigration(context: Context) {
        // NOTA: "CryptionChatPrefs" è il nome delle vecchie SharedPreferences non criptate.
        val oldPrefsName = "CryptionChatPrefs"
        val oldPrefs = context.getSharedPreferences(oldPrefsName, Context.MODE_PRIVATE)

        // Controlla se le vecchie preferenze esistono e contengono dati.
        if (oldPrefs.contains("user_id")) {
            Log.i("DataMigration", "Trovate vecchie SharedPreferences. Avvio migrazione.")

            try {
                val userId = oldPrefs.getInt("user_id", -1)
                val username = oldPrefs.getString("username", null)
                val profilePicUrl = oldPrefs.getString("profile_picture_url", null)

                if (userId != -1 && username != null) {
                    // I dati sono validi, li spostiamo nelle SecurePrefs
                    val securePrefs = SecurePrefs.get(context)
                    with(securePrefs.edit()) {
                        putInt("user_id", userId)
                        putString("username", username)
                        putString("profile_picture_url", profilePicUrl)
                        // Pulisce il timestamp delle notifiche per evitare di ricevere notifiche vecchie
                        remove("last_notification_timestamp")
                        apply()
                    }

                    // Svuota e cancella le vecchie SharedPreferences non sicure
                    with(oldPrefs.edit()) {
                        clear()
                        apply()
                    }
                    Log.i("DataMigration", "Migrazione completata con successo. Le vecchie preferenze sono state cancellate.")
                } else {
                    Log.w("DataMigration", "Le vecchie preferenze sono state trovate ma i dati erano incompleti. Le cancello.")
                    with(oldPrefs.edit()) {
                        clear()
                        apply()
                    }
                }
            } catch (e: Exception) {
                // Se si verifica un errore di crittografia durante la migrazione
                // (come l'AEADBadTagException), catturiamo l'eccezione e cancelliamo le vecchie
                // preferenze per impedire all'app di entrare in un ciclo di crash.
                Log.e("DataMigration", "Errore critico durante la migrazione. Cancello le vecchie preferenze per consentire l'avvio.", e)
                with(oldPrefs.edit()) {
                    clear()
                    apply()
                }
            }
        } else {
            Log.i("DataMigration", "Nessuna vecchia SharedPreferences da migrare.")
        }
    }
}