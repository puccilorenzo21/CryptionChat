package com.lollo1344.cryptionchat

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

object WhatsAppStickerImporter {

    private const val TAG = "WhatsAppStickerImporter"

    private val WHATSAPP_STICKER_ROOT_PATHS = listOf(
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers",
        "WhatsApp/Media/WhatsApp Stickers",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Stickers",
        "WhatsApp Business/Media/WhatsApp Business Stickers",
        "Android/media/com.whatsapp/WhatsApp/Media",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media",
        "Android/data/com.whatsapp/files/Stickers",
        "Android/data/com.whatsapp.w4b/files/Stickers"
    )

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

    suspend fun getOrImportStickers(context: Context): List<File> = withContext(Dispatchers.IO) {
        val appStickerDir = File(context.filesDir, "cryption_stickers").apply { mkdirs() }

        // 1. SCANSIONA TUTTE LE CARTELLE ESTERNE AD OGNI APERTURA
        val rootStorage = Environment.getExternalStorageDirectory()
        for (relativePath in WHATSAPP_STICKER_ROOT_PATHS) {
            val waFolder = File(rootStorage, relativePath)
            if (waFolder.exists() && waFolder.isDirectory) {
                try {
                    waFolder.walkTopDown()
                        .filter { it.isFile && it.length() > 128L && it.name.lowercase().endsWith(".webp") }
                        .forEach { file ->
                            val cleanName = file.name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                            val destFileName = if (cleanName.startsWith("wa_") || cleanName.startsWith("zip_") || cleanName.startsWith("fav_")) {
                                cleanName
                            } else {
                                "wa_$cleanName"
                            }

                            val destFile = File(appStickerDir, destFileName)

                            // Se lo sticker è nuovo (o modificato), lo aggiunge e gli assegna la data attuale per metterlo in cima
                            if (!destFile.exists() || destFile.length() != file.length()) {
                                if (isValidWebpFile(file)) {
                                    try {
                                        file.copyTo(destFile, overwrite = true)
                                        // Assegna il timestamp corrente per farlo apparire primo nella lista
                                        destFile.setLastModified(System.currentTimeMillis())
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Errore durante la copia dello sticker ${file.name}", e)
                                    }
                                }
                            }
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Errore durante la scansione di: $relativePath", e)
                }
            }
        }

        // 2. RESTITUISCE TUTTI GLI STICKER (VECCHI + NUOVI AGGIUNTI) ORDINATI PER DATA DECRESCENTE (I NUOVI IN CIMA)
        val allStickerFiles = appStickerDir.listFiles { _, name ->
            name.lowercase().endsWith(".webp") && !name.contains("+") && !name.contains("=")
        }?.filter { it.length() > 128L }?.sortedByDescending { it.lastModified() } ?: emptyList()

        return@withContext allStickerFiles
    }
}