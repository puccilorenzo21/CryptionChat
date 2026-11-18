package com.lollo1344.cryptionchat

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class ProgressRequestBody(
    private val file: File,
    private val contentType: String,
    private val isCancelled: (() -> Boolean)? = null, // Controlla l'annullamento dell'utente
    private val onProgressUpdate: (Int) -> Unit
) : RequestBody() {

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192
    }

    override fun contentType(): MediaType? {
        return contentType.toMediaTypeOrNull()
    }

    override fun contentLength(): Long {
        return file.length()
    }

    override fun writeTo(sink: BufferedSink) {
        val fileLength = file.length()

        if (fileLength <= 0) {
            onProgressUpdate(100)
            return
        }

        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var uploaded: Long = 0
        var read: Int
        var lastProgress = -1

        FileInputStream(file).use { fis ->
            while (fis.read(buffer).also { read = it } != -1) {
                // VERIFICA SE L'UTENTE HA PREMUTO ANNULLA: Interrompe subito l'invio
                if (isCancelled?.invoke() == true) {
                    throw IOException("Upload annullato dall'utente.")
                }

                uploaded += read.toLong()
                sink.write(buffer, 0, read)
                sink.flush()

                val progress = (100 * uploaded / fileLength).toInt()

                if (progress != lastProgress) {
                    lastProgress = progress
                    onProgressUpdate(progress)
                }
            }
        }
    }
}