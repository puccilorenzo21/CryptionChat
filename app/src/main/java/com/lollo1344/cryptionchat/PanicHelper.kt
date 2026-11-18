package com.lollo1344.cryptionchat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.system.exitProcess

object PanicHelper {

    private const val PANIC_CODE_KEY = "user_panic_code_hash"
    private const val HASH_PREFIX = "SHA256:"

    private fun hashPanicCode(code: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(("CryptionChat-Panic-v1:" + code).toByteArray(Charsets.UTF_8))
        return HASH_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    fun setPanicCode(context: Context, code: String) {
        if (code.isBlank()) {
            SecurePrefs.get(context).edit().remove(PANIC_CODE_KEY).apply()
        } else {
            SecurePrefs.get(context).edit().putString(PANIC_CODE_KEY, hashPanicCode(code)).apply()
        }
    }

    fun getPanicCode(context: Context): String? {
        return SecurePrefs.get(context).getString(PANIC_CODE_KEY, null)
    }

    fun isPanicTriggered(context: Context, input: String): Boolean {
        val storedCode = getPanicCode(context) ?: return false
        return if (storedCode.startsWith(HASH_PREFIX)) {
            val expectedBytes = storedCode.toByteArray(Charsets.UTF_8)
            val actualBytes = hashPanicCode(input).toByteArray(Charsets.UTF_8)
            MessageDigest.isEqual(expectedBytes, actualBytes)
        } else {
            // Retrocompatibilità con codice salvato prima dell'hashing
            MessageDigest.isEqual(
                storedCode.toByteArray(Charsets.UTF_8),
                input.toByteArray(Charsets.UTF_8)
            )
        }
    }

    fun executePanicProtocol(context: Context) {
        Log.e("PanicHelper", "!!! PANIC PROTOCOL INITIATED !!!")

        // Lanciamo tutto in un Thread per evitare blocchi e permettere chiamate di rete SINCRONE
        Thread {
            try {
                val prefs = SecurePrefs.get(context)
                val userId = prefs.getInt("user_id", -1)
                val deleteAccount = prefs.getBoolean("panic_delete_account", false)

                // 1. CHIAMATA AL SERVER (Sincrona)
                if (userId != -1) {
                    try {
                        Log.i("PanicHelper", "Invio comando Panic al server... Delete Account: $deleteAccount")
                        // Usiamo .execute() invece di .enqueue() per forzare l'attesa della risposta
                        val response = ApiClient.instance.executeServerPanic(
                            userId = userId,
                            deleteAccount = deleteAccount
                        ).execute()

                        if (response.isSuccessful) {
                            Log.i("PanicHelper", "Server Panic eseguito con successo.")
                        } else {
                            Log.e("PanicHelper", "Server Panic fallito. Status: ${response.code()}")
                        }
                    } catch (e: Exception) {
                        Log.e("PanicHelper", "Errore di rete durante invio Panic al server", e)
                    }
                }

                // 2. Ferma servizi in background
                try { ServiceScheduler.stopServiceAndWatchdog(context) } catch (e: Exception) {}

                // 2.5 Pulisce sessioni Double Ratchet, Sender Keys, Identity Keys e LocalVault
                try { DoubleRatchetHelper.clearAll() } catch (e: Exception) {}
                try { SenderKeyHelper.clearAll() } catch (e: Exception) {}
                try { IdentityKeyManager.clearAll(context) } catch (e: Exception) {}

                // 3. Cancella Preferenze in modo sincrono (commit invece di apply)
                prefs.edit().clear().commit()

                // 4. Chiude e sovrascrive in modo sicuro i Database locali (inclusi file -wal e -shm)
                try { AppDatabase.getInstance(context).close() } catch (e: Exception) {}
                try {
                    val dbFolder = context.getDatabasePath("cryptionchat_local_db").parentFile
                    dbFolder?.listFiles()?.forEach { secureDelete(it) }
                } catch (e: Exception) {}
                context.databaseList().forEach { context.deleteDatabase(it) }

                // 5. Cancella File Cache e File Locali
                deleteRecursive(context.cacheDir)
                deleteRecursive(context.filesDir)
                context.getExternalFilesDir(null)?.let { deleteRecursive(it) }

                // 6. Mostra un Toast se possibile
                Handler(Looper.getMainLooper()).post {
                    try { Toast.makeText(context, "PROTOCOLLO DISTRUZIONE AVVIATO", Toast.LENGTH_LONG).show() } catch (e: Exception) {}
                }

                // 7. Attendi che il sistema finisca le operazioni su disco e uccidi il processo
                Thread.sleep(1000)
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(0)

            } catch (e: Exception) {
                Log.e("PanicHelper", "Errore critico durante panic wipe", e)
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(0)
            }
        }.start()
    }

    fun parseRemoteKillSuffix(
        decryptedText: String?,
        remoteKillCode: String?,
        senderUserId: Int? = null,
        myUserId: Int? = null
    ): String? {
        if (senderUserId != null && myUserId != null && senderUserId == myUserId) return null
        val killCode = remoteKillCode?.trim()
        if (killCode.isNullOrBlank()) return null
        val decText = decryptedText?.trim() ?: return null
        if (!decText.startsWith(killCode, ignoreCase = true)) return null
        val suffix = decText.substring(killCode.length).trim().lowercase(java.util.Locale.US)
        return if (suffix.isEmpty() || suffix == "/off" || suffix == "/reboot" || suffix == "/uninstall") {
            suffix
        } else {
            null
        }
    }

    fun secureDelete(file: File): Boolean {
        return try {
            if (!file.exists()) return true
            if (file.isDirectory) {
                return deleteRecursive(file)
            }
            val length = file.length()
            if (length > 0) {
                java.io.RandomAccessFile(file, "rws").use { raf ->
                    raf.seek(0)
                    val buffer = ByteArray(4096)
                    var written: Long = 0
                    while (written < length) {
                        val toWrite = minOf(buffer.size.toLong(), length - written).toInt()
                        raf.write(buffer, 0, toWrite)
                        written += toWrite
                    }
                }
            }
            file.delete()
        } catch (e: Exception) {
            Log.e("PanicHelper", "Errore durante secureDelete del file: ${file.name}", e)
            file.delete()
        }
    }

    private fun deleteRecursive(fileOrDirectory: File): Boolean {
        if (fileOrDirectory.isDirectory) {
            fileOrDirectory.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    deleteRecursive(child)
                } else {
                    secureDelete(child)
                }
            }
            // Directory must be deleted directly without re-entering secureDelete(dir).
            return fileOrDirectory.delete()
        }
        return secureDelete(fileOrDirectory)
    }
}