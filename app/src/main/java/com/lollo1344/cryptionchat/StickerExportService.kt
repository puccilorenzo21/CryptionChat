package com.lollo1344.cryptionchat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StickerExportService : Service() {

    companion object {
        private const val TAG = "StickerExportService"
        private const val CHANNEL_ID = "sticker_export_channel_v2"
        private const val NOTIFICATION_ID = 8881
        private const val FINISHED_NOTIFICATION_ID = 8882
        private const val FIXED_ZIP_NAME = "CryptionChat_Stickers.zip"
        private val RELATIVE_FOLDER_PATH = "${Environment.DIRECTORY_DOWNLOADS}/CryptionChat_Stickers"

        fun startExport(context: Context) {
            val intent = Intent(context, StickerExportService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification(getString(R.string.sticker_export_preparing), 0, true)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else 0
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore avvio startForeground: ${e.message}")
        }

        executeExportWork()
        return START_NOT_STICKY
    }

    private fun executeExportWork() {
        serviceScope.launch {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                val appStickerDir = File(filesDir, "cryption_stickers")
                if (!appStickerDir.exists() || !appStickerDir.isDirectory) {
                    showFinishedNotification(getString(R.string.sticker_export_none), null)
                    stopSelf()
                    return@launch
                }

                val stickerFiles = appStickerDir.listFiles { _, name -> name.lowercase().endsWith(".webp") }
                if (stickerFiles.isNullOrEmpty()) {
                    showFinishedNotification(getString(R.string.sticker_export_none), null)
                    stopSelf()
                    return@launch
                }

                val totalFiles = stickerFiles.size
                val tempZipFile = File(cacheDir, FIXED_ZIP_NAME)
                if (tempZipFile.exists()) tempZipFile.delete()

                var lastProgressUpdate = 0L

                // COMPRESSIONE ZIP OTTIMIZZATA
                ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZipFile))).use { zos ->
                    zos.setLevel(Deflater.BEST_COMPRESSION)
                    val buffer = ByteArray(65536)

                    stickerFiles.forEachIndexed { index, file ->
                        if (!isActive) return@use

                        val entry = ZipEntry(file.name)
                        zos.putNextEntry(entry)
                        FileInputStream(file).use { fis ->
                            var bytesRead: Int
                            while (fis.read(buffer).also { bytesRead = it } != -1) {
                                zos.write(buffer, 0, bytesRead)
                            }
                        }
                        zos.closeEntry()

                        val now = System.currentTimeMillis()
                        val progress = ((index + 1) * 100) / totalFiles

                        if (now - lastProgressUpdate > 300 || index == totalFiles - 1) {
                            lastProgressUpdate = now
                            val updatedNotif = createNotification(getString(R.string.sticker_export_progress, index + 1, totalFiles, progress), progress, false)
                            notificationManager.notify(NOTIFICATION_ID, updatedNotif)
                        }
                    }
                }

                val resolver = contentResolver

                // 1. RIMOZIONE SOVRASCRITTURA OBBLIGATORIA DEL VECCHIO ZIP DALLA GALLERIA/MEDIASTORE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val projection = arrayOf(MediaStore.Downloads._ID)
                    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                    val selectionArgs = arrayOf(FIXED_ZIP_NAME)

                    try {
                        resolver.query(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            projection,
                            selection,
                            selectionArgs,
                            null
                        )?.use { cursor ->
                            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                            while (cursor.moveToNext()) {
                                val id = cursor.getLong(idCol)
                                val oldUri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                                resolver.delete(oldUri, null, null)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Errore nella cancellazione del vecchio ZIP dal MediaStore", e)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val targetFolder = File(publicDir, "CryptionChat_Stickers")
                    val targetFile = File(targetFolder, FIXED_ZIP_NAME)
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                }

                // 2. CREAZIONE NUOVA VOCE ED INSERIMENTO DEL NUOVO FILE SOVRASCRITTO
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, FIXED_ZIP_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_FOLDER_PATH)
                    }
                }

                val publicUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (publicUri != null) {
                    resolver.openOutputStream(publicUri)?.use { outputStream ->
                        FileInputStream(tempZipFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    tempZipFile.delete()
                    showFinishedNotification(getString(R.string.sticker_export_done_count, totalFiles), publicUri)
                } else {
                    showFinishedNotification(getString(R.string.sticker_export_done), Uri.fromFile(tempZipFile))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Errore durante l'esportazione ZIP in background", e)
                showFinishedNotification(getString(R.string.sticker_export_error), null)
            } finally {
                stopSelf()
            }
        }
    }

    private fun createNotification(text: String, progress: Int, isIndeterminate: Boolean): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file_download)
            .setContentTitle(getString(R.string.sticker_export_notif_ongoing))
            .setContentText(text)
            .setProgress(100, progress, isIndeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun showFinishedNotification(text: String, fileUri: Uri?) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check_circle)
            .setContentTitle(getString(R.string.sticker_export_notif_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (fileUri != null) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, Intent.createChooser(shareIntent, getString(R.string.sticker_export_share_title)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pendingIntent)
            builder.addAction(R.drawable.ic_send, getString(R.string.sticker_export_share_action), pendingIntent)
        }

        notificationManager.notify(FINISHED_NOTIFICATION_ID, builder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sticker_export_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.sticker_export_channel_desc)
                setShowBadge(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}