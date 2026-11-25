package com.lollo1344.cryptionchat

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat

object FileUtils {

    data class FileDetails(val name: String, val size: Long, val mimeType: String?)

    fun getFileDetails(context: Context, uri: Uri): FileDetails? {
        if (ContentResolver.SCHEME_FILE == uri.scheme) {
            val file = File(uri.path ?: return null)
            val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            return FileDetails(file.name, file.length(), mimeType)
        }

        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                cursor.moveToFirst()
                if (nameIndex == -1 || sizeIndex == -1) return null

                var name = cursor.getString(nameIndex) ?: "image"
                val size = cursor.getLong(sizeIndex)
                val mimeType = context.contentResolver.getType(uri)

                if (!name.contains(".") && mimeType != null) {
                    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                    if (!ext.isNullOrEmpty()) {
                        name = "$name.$ext"
                    }
                }

                FileDetails(name, size, mimeType)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getFileFromUri(context: Context, uri: Uri): File? {
        val details = getFileDetails(context, uri) ?: return null
        val mimeType = details.mimeType ?: context.contentResolver.getType(uri) ?: ""
        val isGif = mimeType.equals("image/gif", ignoreCase = true) || details.name.endsWith(".gif", ignoreCase = true)
        val isWebp = mimeType.equals("image/webp", ignoreCase = true) || details.name.endsWith(".webp", ignoreCase = true)
        val isImage = mimeType.startsWith("image/", ignoreCase = true)

        return try {
            if (isImage && !isGif && !isWebp) {
                // Legge l'immagine standard e corregge l'orientamento EXIF salvandola in JPG privo di metadati
                val bitmap = getCorrectlyOrientedBitmap(context, uri)
                if (bitmap != null) {
                    val safeName = details.name.substringBeforeLast(".").replace("[^a-zA-Z0-9.-]".toRegex(), "_") + ".jpg"
                    val file = File(context.cacheDir, "temp_upload_${System.currentTimeMillis()}_$safeName")
                    FileOutputStream(file).use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, outputStream)
                        outputStream.flush()
                    }
                    bitmap.recycle()
                    stripSensitiveExif(file)
                    file
                } else {
                    // MAI inviare un'immagine raw non decodificata con possibili GPS/EXIF intatti
                    val rawCopy = copyRawStreamToFile(context, uri, details.name) ?: return null
                    if (stripSensitiveExif(rawCopy)) {
                        rawCopy
                    } else {
                        rawCopy.delete()
                        null
                    }
                }
            } else {
                // Mantiene intatte le GIF, gli sticker WebP animati e tutti i file non immagine
                copyRawStreamToFile(context, uri, details.name)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Rimuove tutti i tag EXIF sensibili (GPS, modello/marca fotocamera, software, date, commenti).
     * Ritorna true se l'operazione e' stata salvata con successo sul file.
     */
    private fun stripSensitiveExif(file: File): Boolean {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val sensitiveTags = listOf(
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_GPS_PROCESSING_METHOD,
                ExifInterface.TAG_GPS_SPEED,
                ExifInterface.TAG_GPS_SPEED_REF,
                ExifInterface.TAG_GPS_TRACK,
                ExifInterface.TAG_GPS_TRACK_REF,
                ExifInterface.TAG_GPS_IMG_DIRECTION,
                ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
                ExifInterface.TAG_GPS_AREA_INFORMATION,
                ExifInterface.TAG_GPS_DEST_BEARING,
                ExifInterface.TAG_GPS_DEST_DISTANCE,
                ExifInterface.TAG_GPS_DEST_LATITUDE,
                ExifInterface.TAG_GPS_DEST_LONGITUDE,
                ExifInterface.TAG_GPS_DIFFERENTIAL,
                ExifInterface.TAG_GPS_DOP,
                ExifInterface.TAG_GPS_MAP_DATUM,
                ExifInterface.TAG_GPS_MEASURE_MODE,
                ExifInterface.TAG_GPS_SATELLITES,
                ExifInterface.TAG_GPS_STATUS,
                ExifInterface.TAG_GPS_VERSION_ID,
                "GPSHPositioningError",
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_SOFTWARE,
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_SUBSEC_TIME,
                ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
                "OffsetTime",
                "OffsetTimeOriginal",
                "OffsetTimeDigitized",
                ExifInterface.TAG_USER_COMMENT,
                ExifInterface.TAG_MAKER_NOTE,
                ExifInterface.TAG_IMAGE_DESCRIPTION,
                ExifInterface.TAG_ARTIST,
                ExifInterface.TAG_COPYRIGHT,
                ExifInterface.TAG_IMAGE_UNIQUE_ID,
                "CameraOwnerName",
                "BodySerialNumber",
                "LensMake",
                "LensModel",
                "LensSerialNumber"
            )
            for (tag in sensitiveTags) {
                exif.setAttribute(tag, null)
            }
            exif.saveAttributes()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun copyRawStreamToFile(context: Context, uri: Uri, originalName: String): File? {
        val safeName = originalName.replace("[^a-zA-Z0-9.-]".toRegex(), "_")
        val file = File(context.cacheDir, "temp_upload_${System.currentTimeMillis()}_$safeName")
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(file).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: return null
        return file
    }

    /**
     * Rileva l'orientamento EXIF originale della fotocamera e ruota la Bitmap
     * per garantire che sia sempre dritta prima dell'invio.
     */
    private fun getCorrectlyOrientedBitmap(context: Context, uri: Uri): Bitmap? {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream)
        } ?: return null

        return try {
            val orientation = context.contentResolver.openInputStream(uri)?.use { exifStream ->
                val exif = ExifInterface(exifStream)
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: return bitmap

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }
            rotatedBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        }
    }

    fun getFormattedFileSize(sizeInBytes: Long): String {
        if (sizeInBytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(sizeInBytes.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(
            sizeInBytes / Math.pow(1024.0, digitGroups.toDouble())
        ) + " " + units[digitGroups]
    }

    fun getLocalFile(context: Context, message: Message): File {
        val storageDir = File(context.filesDir, "CryptionChat_Files").apply { mkdirs() }
        val safeFileName = message.fileName?.replace("[^a-zA-Z0-9._-]".toRegex(), "_") ?: "file"
        val fileName = "${message.fileId}_${safeFileName}"
        return File(storageDir, fileName)
    }

    fun saveBitmapToTempUri(context: Context, bitmap: Bitmap): Uri? {
        return try {
            val file = File(context.cacheDir, "temp_ai_avatar_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            Uri.fromFile(file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

}