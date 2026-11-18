package com.lollo1344.cryptionchat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object StickerZipManager {

    private const val TAG = "StickerZipManager"
    private const val FIXED_ZIP_NAME = "CryptionChat_Stickers.zip"
    private val RELATIVE_FOLDER_PATH = "${Environment.DIRECTORY_DOWNLOADS}/CryptionChat_Stickers"

    private fun isValidWebpFile(file: File): Boolean {
        if (!file.exists() || file.length() < 128) return false
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(12)
                val read = fis.read(header)
                if (read < 12) return false
                header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                        header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() && header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun exportStickersToZip(context: Context): Uri? = withContext(Dispatchers.IO) {
        try {
            val appStickerDir = File(context.filesDir, "cryption_stickers")
            if (!appStickerDir.exists() || !appStickerDir.isDirectory) return@withContext null

            val stickerFiles = appStickerDir.listFiles { _, name -> name.lowercase().endsWith(".webp") && isValidWebpFile(File(appStickerDir, name)) }
            if (stickerFiles.isNullOrEmpty()) return@withContext null

            val resolver = context.contentResolver

            // 1. RIMOZIONE SOVRASCRITTURA OBBLIGATORIA DEL VECCHIO ZIP DAL MEDIASTORE
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
                    Log.e(TAG, "Errore nella pulizia del MediaStore per vecchi file ZIP", e)
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

            // 2. CREAZIONE NUOVO FILE ZIP TEMPORANEO CON COMPRESSIONE MASSIMA
            val tempZipFile = File(context.cacheDir, FIXED_ZIP_NAME)
            if (tempZipFile.exists()) tempZipFile.delete()

            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZipFile))).use { zos ->
                zos.setLevel(java.util.zip.Deflater.BEST_COMPRESSION)
                val buffer = ByteArray(65536)

                for (file in stickerFiles) {
                    val entry = ZipEntry(file.name)
                    zos.putNextEntry(entry)
                    FileInputStream(file).use { fis ->
                        var bytesRead: Int
                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                            zos.write(buffer, 0, bytesRead)
                        }
                    }
                    zos.closeEntry()
                }
            }

            // 3. SCRITTURA NEL MEDIASTORE PUBBLICO
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
                Log.d(TAG, "ZIP unico creato e sovrascritto in Download: $publicUri")
                return@withContext publicUri
            } else {
                return@withContext Uri.fromFile(tempZipFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante la creazione dello ZIP unico", e)
            return@withContext null
        }
    }

    suspend fun importStickersFromZip(context: Context, zipUri: Uri): Int = withContext(Dispatchers.IO) {
        var importedCount = 0
        try {
            val appStickerDir = File(context.filesDir, "cryption_stickers").apply { mkdirs() }
            val inputStream = context.contentResolver.openInputStream(zipUri) ?: return@withContext 0

            ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val rawName = entry.name.substringAfterLast("/").substringAfterLast("\\")
                    if (!entry.isDirectory && rawName.lowercase().endsWith(".webp")) {
                        val cleanName = rawName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                        val safeName = if (cleanName.startsWith("zip_") || cleanName.startsWith("wa_")) cleanName else "zip_$cleanName"
                        val destFile = File(appStickerDir, safeName)

                        FileOutputStream(destFile).use { fos ->
                            zis.copyTo(fos)
                        }

                        if (isValidWebpFile(destFile)) {
                            destFile.setLastModified(System.currentTimeMillis())
                            importedCount++
                        } else {
                            destFile.delete()
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Log.d(TAG, "Importati $importedCount nuovi sticker dallo ZIP")
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante l'importazione del file ZIP", e)
        }
        return@withContext importedCount
    }

    suspend fun deleteAllStickers(context: Context): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val appStickerDir = File(context.filesDir, "cryption_stickers")
            if (appStickerDir.exists() && appStickerDir.isDirectory) {
                appStickerDir.listFiles()?.forEach { file ->
                    file.delete()
                }
            }
            SecurePrefs.get(context).edit().remove("recent_stickers").apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante l'eliminazione di tutti gli sticker", e)
            false
        }
    }
}