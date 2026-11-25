package com.lollo1344.cryptionchat

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.File
import java.util.Locale

object ScreenshotDestroyer {

    private const val TAG = "ScreenshotDestroyer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var contentObserver: ContentObserver? = null

    // Callback per avvisare l'Activity
    private var onScreenshotDetected: (() -> Unit)? = null

    // Parole chiave ampie per rilevare screenshot in varie lingue e cartelle
    private val SCREENSHOT_KEYWORDS = listOf(
        "screenshot", "screen_shot", "screen shot",
        "cattura", "schermata", "capture", "screencap",
        "snapshot", "screenshot_", "Screenshot"
    )

    fun startWatching(context: Context, listener: () -> Unit) {
        this.onScreenshotDetected = listener
        if (contentObserver != null) return

        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())

        contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                if (uri != null) {
                    checkAndDestroyImage(appContext, uri)
                } else {
                    checkAndDestroyImage(appContext, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                }
            }
        }

        try {
            // Monitora sia lo storage esterno che quello interno per la massima compatibilità tra i dispositivi
            appContext.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver!!
            )
            appContext.contentResolver.registerContentObserver(
                MediaStore.Images.Media.INTERNAL_CONTENT_URI,
                true,
                contentObserver!!
            )
            Log.i(TAG, "Terminator Screenshot Attivo.")
        } catch (e: Exception) {
            Log.e(TAG, "Errore avvio observer", e)
        }
    }

    fun stopWatching(context: Context) {
        contentObserver?.let {
            context.applicationContext.contentResolver.unregisterContentObserver(it)
            contentObserver = null
            onScreenshotDetected = null
            Log.i(TAG, "Terminator Screenshot Fermato.")
        }
    }

    private fun isSpecificItemUri(uri: Uri): Boolean {
        return try {
            val lastPathSegment = uri.lastPathSegment
            lastPathSegment != null && lastPathSegment.toLongOrNull() != null
        } catch (e: Exception) {
            false
        }
    }

    private fun checkAndDestroyImage(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                val isSpecificUri = isSpecificItemUri(uri)
                var targetUri = uri
                var isScreenshotDetected = false
                var resolvedPath: String? = null

                // Polling loop per attendere che la scrittura iniziale del file sul disco sia avviata
                var attempts = 0
                val maxAttempts = 15 // Ripeti fino a 15 volte (~3 secondi totali)
                val delayMs = 200L

                while (attempts < maxAttempts) {
                    attempts++

                    val projection = mutableListOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.DATE_ADDED,
                        MediaStore.Images.Media.DATA
                    ).apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            add(MediaStore.Images.Media.IS_PENDING)
                            add(MediaStore.Images.Media.RELATIVE_PATH)
                        }
                    }.toTypedArray()

                    var selection: String? = null
                    var selectionArgs: Array<String>? = null
                    var sortOrder: String? = null
                    var queryUri = targetUri

                    if (!isSpecificUri) {
                        // Se è un URI generico di directory, interroga l'immagine più recente
                        queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 1"
                    }

                    appContext.contentResolver.query(
                        queryUri,
                        projection,
                        selection,
                        selectionArgs,
                        sortOrder
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                            val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                            val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                            val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)

                            val id = cursor.getLong(idCol)
                            val path = cursor.getString(dataCol) ?: ""
                            val name = cursor.getString(nameCol)?.lowercase(Locale.ROOT) ?: ""
                            val dateAdded = cursor.getLong(dateCol)

                            // Rileva lo stato di scrittura tramite MediaStore su Android 10+
                            var isPending = false
                            var relativePath = ""
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val pendingCol = cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                                val relativePathCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                                if (pendingCol != -1) {
                                    isPending = cursor.getInt(pendingCol) == 1
                                }
                                if (relativePathCol != -1) {
                                    relativePath = cursor.getString(relativePathCol) ?: ""
                                }
                            }

                            // Se il file è ancora in scrittura (is_pending), attendiamo il ciclo successivo
                            if (isPending) {
                                Log.d(TAG, "File in stato PENDING (scrittura parziale). Attendo...")
                                delay(delayMs)
                                return@use
                            }

                            // Verifica l'esistenza fisica del file e la stabilità della dimensione
                            val file = File(path)
                            if (!file.exists()) {
                                Log.d(TAG, "File non ancora presente su disco. Attendo...")
                                delay(delayMs)
                                return@use
                            }

                            val size1 = file.length()
                            if (size1 == 0L) {
                                Log.d(TAG, "Dimensione del file 0 byte. Scrittura in corso...")
                                delay(delayMs)
                                return@use
                            }

                            delay(150L) // Breve pausa intermedia per verificare la stabilità
                            val size2 = file.length()
                            if (size1 != size2) {
                                Log.d(TAG, "La dimensione del file sta variando ($size1 -> $size2). Scrittura non conclusa...")
                                delay(delayMs)
                                return@use
                            }

                            // Controllo di temporalità per prevenire l'attivazione su file vecchi
                            val currentTime = System.currentTimeMillis() / 1000
                            if (currentTime - dateAdded > 15) {
                                return@launch
                            }

                            // Analisi approfondita di nome, percorso e cartelle correlate
                            val pathLower = path.lowercase(Locale.ROOT)
                            val relativePathLower = relativePath.lowercase(Locale.ROOT)

                            val isScreenshotNameOrPath = SCREENSHOT_KEYWORDS.any { name.contains(it) || pathLower.contains(it) || relativePathLower.contains(it) } ||
                                    pathLower.contains("/screenshots/") ||
                                    pathLower.contains("dcim/screenshot") ||
                                    relativePathLower.contains("screenshots")

                            if (isScreenshotNameOrPath) {
                                Log.w(TAG, "Screenshot rilevato! Attendo il completamento della scrittura di sistema...")

                                // ATTESA MANDATORIA: Permette al sistema di concludere interamente la scrittura del file e chiuderlo
                                delay(1200L)

                                isScreenshotDetected = true
                                resolvedPath = path
                                if (!isSpecificUri) {
                                    targetUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                                }
                            }

                            // Concludi il polling poichè il file è stato elaborato
                            attempts = maxAttempts
                        }
                    }
                    if (attempts < maxAttempts) {
                        delay(delayMs)
                    }
                }

                // Se abbiamo rilevato uno screenshot, procediamo alla rimozione sicura
                if (isScreenshotDetected && resolvedPath != null) {
                    onScreenshotDetected?.let { callback ->
                        Handler(Looper.getMainLooper()).post {
                            callback.invoke()
                        }
                    }

                    // 1. Esegui PRIMA la distruzione fisica del file dal disco per evitare che finisca nel Cestino
                    destroyFile(appContext, resolvedPath)

                    // 2. Successivamente elimina il record dal database MediaStore per ripulire l'indice della galleria
                    try {
                        appContext.contentResolver.delete(targetUri, null, null)
                        Log.i(TAG, "Record rimosso dal database MediaStore.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Errore rimozione record dal database MediaStore", e)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Errore in checkAndDestroyImage", e)
            }
        }
    }

    private fun destroyFile(context: Context, path: String): Boolean {
        val appContext = context.applicationContext
        val isWhitelisted = false
        val isRooted = RootDetectionHelper.isDeviceRooted(appContext)

        var deleted = false

        // TENTATIVO 1: Root (Per sviluppatori o utenti abilitati)
        if (isRooted && isWhitelisted) {
            val (success, _) = RootDetectionHelper.executeCommandAsRoot("rm -f \"$path\"")
            if (success) {
                deleted = true
                RootDetectionHelper.executeCommandAsRoot("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$path")
                Log.i(TAG, "File eliminato con successo tramite comando Root.")
            }
        }

        // TENTATIVO 1.5: Shizuku (Per utenti non-rootati con Shizuku abilitato)
        if (!deleted && isWhitelisted && ShizukuHelper.isShizukuAvailable()) {
            try {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    val (success, _) = ShizukuHelper.executeCommand("rm -f \"$path\"")
                    if (success) {
                        deleted = true
                        ShizukuHelper.executeCommand("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$path")
                        Log.i(TAG, "File eliminato con successo tramite Shizuku.")
                    }
                } else {
                    Log.w(TAG, "Shizuku rilevato ma i permessi di utilizzo non sono stati concessi in Shizuku Manager.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore eliminazione tramite Shizuku", e)
            }
        }

        // TENTATIVO 2: Standard Java (Funziona se l'app ha MANAGE_EXTERNAL_STORAGE)
        if (!deleted) {
            try {
                val file = File(path)
                if (file.exists()) {
                    if (file.delete()) {
                        deleted = true
                        Log.i(TAG, "File eliminato con successo tramite Java standard.")
                    } else {
                        // Evita la scrittura se canWrite() è false, prevenendo l'eccezione EACCES sul Logcat
                        if (file.canWrite()) {
                            file.writeBytes(ByteArray(0))
                            deleted = true
                            Log.i(TAG, "Impossibile eliminare, il file è stato svuotato (0 byte) per sicurezza.")
                        } else {
                            Log.w(TAG, "Scrittura non permessa su Scoped Storage. Screenshot non rimosso (Nessun Root o Shizuku autorizzato).")
                        }
                    }
                }

                // Forza l'aggiornamento della galleria di sistema
                android.media.MediaScannerConnection.scanFile(appContext, arrayOf(path), null, null)
            } catch (e: Exception) {
                Log.e(TAG, "Errore eliminazione standard", e)
            }
        }

        if (deleted) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, appContext.getString(R.string.screenshot_intercepted_deleted), Toast.LENGTH_LONG).show()
            }
        }

        return deleted
    }
}