package com.lollo1344.cryptionchat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecorderHelper(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(): String? {
        try {
            val storageDir = File(context.filesDir, "CryptionChat_Files").apply { mkdirs() }

            if (!storageDir.exists()) {
                throw IOException("Impossibile accedere alla directory di salvataggio.")
            }

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            outputFile = File(storageDir, "AUDIO_${timeStamp}.m4a")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outputFile!!.absolutePath)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                prepare()
                start()
            }
            return outputFile?.absolutePath
        } catch (e: IOException) {
            Log.e("AudioRecorderHelper", "startRecording failed (IOException)", e)
            cleanUp()
            return null
        } catch (e: IllegalStateException) {
            Log.e("AudioRecorderHelper", "startRecording failed (IllegalStateException)", e)
            cleanUp()
            return null
        }
    }

    fun stopRecording(): Boolean {
        val recorder = mediaRecorder ?: return false
        mediaRecorder = null // Impedisce il riutilizzo dell'istanza

        var success = false
        try {
            recorder.stop()
            success = true
        } catch (e: RuntimeException) {
            Log.w("AudioRecorderHelper", "stopRecording failed with RuntimeException: ${e.message}")
            deleteOutputFile()
        } finally {
            try {
                recorder.release()
            } catch (e: Exception) {
                Log.e("AudioRecorderHelper", "Error releasing media recorder", e)
            }
        }
        return success
    }

    fun cancelRecording() {
        cleanUp()
    }

    private fun cleanUp() {
        mediaRecorder?.let {
            try {
                it.stop()
            } catch (e: RuntimeException) {
                // Ignora
            } finally {
                try {
                    it.release()
                } catch (e: Exception) {
                    // Ignora
                }
            }
        }
        mediaRecorder = null
        deleteOutputFile()
    }

    private fun deleteOutputFile() {
        outputFile?.let {
            if (it.exists()) {
                PanicHelper.secureDelete(it)
            }
        }
        outputFile = null
    }
}