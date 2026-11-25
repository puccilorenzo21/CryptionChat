package com.lollo1344.cryptionchat

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.DecimalFormat
import java.util.zip.ZipInputStream
import java.util.concurrent.CountDownLatch

object AudioTranscriber {

    private const val TAG = "AudioTranscriber"

    // --- CONFIGURAZIONE VOSK (ZIP) ---
    private const val VOSK_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"
    private const val VOSK_DIR_NAME = "vosk-model"

    // --- CONFIGURAZIONE WHISPER BASE (BIN) ---
    private const val WHISPER_BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
    private const val WHISPER_BASE_FILE = "ggml-base.bin"

    // --- CONFIGURAZIONE WHISPER SMALL (BIN) ---
    private const val WHISPER_SMALL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
    private const val WHISPER_SMALL_FILE = "ggml-small.bin"

    // --- CONFIGURAZIONE WHISPER SMALL QUANTIZED Q5 (BIN) ---
    private const val WHISPER_SMALL_Q5_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"
    private const val WHISPER_SMALL_Q5_FILE = "ggml-small-q5_1.bin"

    // --- CONFIGURAZIONE WHISPER SMALL QUANTIZED Q8 (BIN) ---
    private const val WHISPER_SMALL_Q8_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin"
    private const val WHISPER_SMALL_Q8_FILE = "ggml-small-q8_0.bin"

    // --- CONFIGURAZIONE WHISPER LARGE-V3-TURBO (Q5_0) ---
    private const val WHISPER_TURBO_Q5_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin"
    private const val WHISPER_TURBO_Q5_FILE = "ggml-large-v3-turbo-q5_0.bin"

    // Chiavi Preferenze
    private const val PREF_MODEL_TYPE = "transcription_model_type"

    // Tipi di modello supportati
    const val TYPE_VOSK = "vosk"
    const val TYPE_WHISPER_BASE = "whisper"          // Base
    const val TYPE_WHISPER_SMALL = "whisper_small"   // Small Standard
    const val TYPE_WHISPER_SMALL_Q5 = "whisper_small_q5" // Small 5-bit
    const val TYPE_WHISPER_SMALL_Q8 = "whisper_small_q8" // Small 8-bit
    const val TYPE_WHISPER_TURBO_Q5 = "whisper_turbo_q5" // Large-v3-Turbo Q5_0
    const val TYPE_HYBRID = "hybrid"                 // Vosk + Whisper Base

    fun setModelType(context: Context, type: String) {
        SecurePrefs.get(context).edit().putString(PREF_MODEL_TYPE, type).apply()
    }

    fun getModelType(context: Context): String {
        return SecurePrefs.get(context).getString(PREF_MODEL_TYPE, TYPE_VOSK) ?: TYPE_VOSK
    }

    private fun getCurrentWhisperFileName(type: String): String? {
        return when (type) {
            TYPE_WHISPER_BASE, TYPE_HYBRID -> WHISPER_BASE_FILE
            TYPE_WHISPER_SMALL -> WHISPER_SMALL_FILE
            TYPE_WHISPER_SMALL_Q5 -> WHISPER_SMALL_Q5_FILE
            TYPE_WHISPER_SMALL_Q8 -> WHISPER_SMALL_Q8_FILE
            TYPE_WHISPER_TURBO_Q5 -> WHISPER_TURBO_Q5_FILE
            else -> null
        }
    }

    private fun getCurrentWhisperUrl(type: String): String? {
        return when (type) {
            TYPE_WHISPER_BASE, TYPE_HYBRID -> WHISPER_BASE_URL
            TYPE_WHISPER_SMALL -> WHISPER_SMALL_URL
            TYPE_WHISPER_SMALL_Q5 -> WHISPER_SMALL_Q5_URL
            TYPE_WHISPER_SMALL_Q8 -> WHISPER_SMALL_Q8_URL
            TYPE_WHISPER_TURBO_Q5 -> WHISPER_TURBO_Q5_URL
            else -> null
        }
    }

    fun isModelAvailable(context: Context): Boolean {
        val type = getModelType(context)
        val filesDir = context.getExternalFilesDir(null)

        val hasVosk = File(filesDir, VOSK_DIR_NAME).let { it.exists() && it.isDirectory && (it.list()?.isNotEmpty() == true) }

        return when (type) {
            TYPE_VOSK -> hasVosk
            TYPE_HYBRID -> {
                val hasWhisperBase = File(filesDir, WHISPER_BASE_FILE).let { it.exists() && it.length() > 0 }
                hasVosk && hasWhisperBase
            }
            else -> {
                val fileName = getCurrentWhisperFileName(type) ?: return false
                File(filesDir, fileName).let { it.exists() && it.length() > 0 }
            }
        }
    }

    fun getModelSize(context: Context): String {
        val type = getModelType(context)
        val filesDir = context.getExternalFilesDir(null)
        var totalSize = 0L

        val whisperFile = getCurrentWhisperFileName(type)
        if (whisperFile != null) {
            val f = File(filesDir, whisperFile)
            if (f.exists()) totalSize += f.length()
        }

        if (type == TYPE_VOSK || type == TYPE_HYBRID) {
            val d = File(filesDir, VOSK_DIR_NAME)
            if (d.exists()) {
                totalSize += d.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            }
        }

        if (totalSize <= 0) return "0 B"

        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(totalSize.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(totalSize / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    fun deleteModel(context: Context): Boolean {
        val type = getModelType(context)
        val filesDir = context.getExternalFilesDir(null)
        var success = true

        val whisperFile = getCurrentWhisperFileName(type)
        if (whisperFile != null) {
            val f = File(filesDir, whisperFile)
            if (f.exists()) if (!f.delete()) success = false
        }

        if (type == TYPE_VOSK || type == TYPE_HYBRID) {
            val d = File(filesDir, VOSK_DIR_NAME)
            if (d.exists()) if (!d.deleteRecursively()) success = false
        }

        return success
    }

    // --- LOGICA DI DOWNLOAD E INSTALLAZIONE ---

    fun downloadAndInstallModel(
        context: Context,
        onProgress: (Int) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val type = getModelType(context)
        val targetDir = context.getExternalFilesDir(null) ?: run { onError(context.getString(R.string.transcribe_ext_storage_error)); return }

        Thread {
            try {
                fun downloadSpecific(url: String, fileName: String?, isZip: Boolean, baseProgress: Int, maxProgress: Int) {
                    if (isZip) {
                        val zipFile = File(context.cacheDir, "model.zip")
                        downloadFile(url, zipFile) { p ->
                            val mapped = baseProgress + (p * (maxProgress - baseProgress) / 100)
                            onProgress(mapped)
                        }
                        onProgress(maxProgress)
                        unzipModel(zipFile, targetDir)
                        if (zipFile.exists()) zipFile.delete()
                    } else if (fileName != null) {
                        val targetFile = File(targetDir, fileName)
                        downloadFile(url, targetFile) { p ->
                            val mapped = baseProgress + (p * (maxProgress - baseProgress) / 100)
                            onProgress(mapped)
                        }
                    }
                }

                if (type == TYPE_HYBRID) {
                    val hasVosk = File(targetDir, VOSK_DIR_NAME).exists()
                    val hasWhisper = File(targetDir, WHISPER_BASE_FILE).exists()
                    if (!hasVosk) downloadSpecific(VOSK_MODEL_URL, null, true, 0, 50)
                    if (!hasWhisper) downloadSpecific(WHISPER_BASE_URL, WHISPER_BASE_FILE, false, 50, 100)
                } else if (type == TYPE_VOSK) {
                    downloadSpecific(VOSK_MODEL_URL, null, true, 0, 100)
                } else {
                    val url = getCurrentWhisperUrl(type)
                    val fileName = getCurrentWhisperFileName(type)

                    if (url != null && fileName != null) {
                        downloadSpecific(url, fileName, false, 0, 100)
                    } else {
                        throw IOException(context.getString(R.string.transcribe_model_config_missing, type))
                    }
                }

                onSuccess()

            } catch (e: Exception) {
                Log.e(TAG, "Errore durante il download/installazione", e)
                onError(e.message ?: context.getString(R.string.transcribe_download_unknown_error))
            }
        }.start()
    }

    private fun downloadFile(url: String, destFile: File, onProgress: (Int) -> Unit) {
        val client = ApiClient.okHttpClient
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

        val body = response.body ?: throw IOException("Empty body.")
        val contentLength = body.contentLength()

        body.byteStream().use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                var totalBytesRead: Long = 0

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (contentLength > 0) {
                        val progress = ((totalBytesRead * 100) / contentLength).toInt()
                        if (progress % 2 == 0) onProgress(progress)
                    }
                }
            }
        }
    }

    // --- LOGICA DI TRASCRIZIONE CON PROGRESSO IN TEMPO REALE ---

    fun transcribe(
        context: Context,
        audioFile: File,
        onProgress: (Int, String) -> Unit = { _, _ -> },
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val type = getModelType(context)

        when (type) {
            TYPE_VOSK -> transcribeWithVosk(context, audioFile, onProgress, onResult, onError)
            TYPE_HYBRID -> transcribeHybrid(context, audioFile, onProgress, onResult, onError)
            else -> transcribeWithWhisper(context, audioFile, type, onProgress, onResult, onError)
        }
    }

    private fun transcribeHybrid(
        context: Context,
        audioFile: File,
        onProgress: (Int, String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            var voskResult = ""
            var whisperResult = ""
            var voskError: String? = null
            var whisperError: String? = null

            val latch = CountDownLatch(2)

            onProgress(10, context.getString(R.string.transcribe_hybrid_starting))

            transcribeWithVosk(context, audioFile,
                onProgress = { p, s -> onProgress(p / 2, "Vosk: $s") },
                onResult = { res -> voskResult = res; latch.countDown() },
                onError = { err -> voskError = err; latch.countDown() }
            )

            transcribeWithWhisper(context, audioFile, TYPE_WHISPER_BASE,
                onProgress = { p, s -> onProgress(50 + p / 2, "Whisper: $s") },
                onResult = { res -> whisperResult = res; latch.countDown() },
                onError = { err -> whisperError = err; latch.countDown() }
            )

            try { latch.await() } catch (e: InterruptedException) { onError(context.getString(R.string.transcribe_interrupted)); return@Thread }

            onProgress(100, context.getString(R.string.transcribe_processing_done))

            val sb = StringBuilder()
            sb.append("🗣️ VOSK (Offline Fast):\n")
            if (voskError != null) sb.append(context.getString(R.string.transcribe_error_line, voskError)) else sb.append("$voskResult\n")
            sb.append("\n-------------------------\n")
            sb.append("🧠 WHISPER (Base):\n")
            if (whisperError != null) sb.append(context.getString(R.string.transcribe_error_line, whisperError)) else sb.append("$whisperResult\n")

            onResult(sb.toString())
        }.start()
    }

    private fun transcribeWithWhisper(
        context: Context,
        audioFile: File,
        type: String,
        onProgress: (Int, String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!WhisperLib.isAvailable()) {
            onError(context.getString(R.string.transcribe_whisper_lib_missing))
            return
        }

        val fileName = getCurrentWhisperFileName(type)
        if (fileName == null) {
            onError(context.getString(R.string.transcribe_invalid_model_type))
            return
        }

        val modelFile = File(context.getExternalFilesDir(null), fileName)
        if (!modelFile.exists()) {
            onError(context.getString(R.string.transcribe_model_not_found, fileName))
            return
        }

        Thread {
            try {
                onProgress(15, context.getString(R.string.transcribe_decoding_track))
                val (pcmData, sourceSampleRate) = decodeAudioToPCM(audioFile) ?: run {
                    onError(context.getString(R.string.transcribe_decode_failed))
                    return@Thread
                }

                onProgress(35, context.getString(R.string.transcribe_resampling))
                val rawFloats = shortBytesToFloat(pcmData)
                val resampledFloats = if (sourceSampleRate != 16000) {
                    resampleTo16k(rawFloats, sourceSampleRate)
                } else {
                    rawFloats
                }

                onProgress(55, context.getString(R.string.transcribe_init_whisper))
                val contextPtr = WhisperLib.init(modelFile.absolutePath)
                if (contextPtr == 0L) {
                    onError(context.getString(R.string.transcribe_init_whisper_failed))
                    return@Thread
                }

                onProgress(75, context.getString(R.string.transcribe_neural_processing))
                val text = WhisperLib.transcribe(contextPtr, resampledFloats)
                WhisperLib.free(contextPtr)

                onProgress(100, context.getString(R.string.transcribe_done))
                val cleanText = text.trim()
                if (cleanText.isNotEmpty() && !cleanText.equals("[Musica]", ignoreCase = true) && !cleanText.equals("[Music]", ignoreCase = true)) {
                    onResult(cleanText)
                } else {
                    onResult(context.getString(R.string.transcribe_no_text))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Errore Whisper", e)
                onError(context.getString(R.string.transcribe_error_msg, e.message ?: ""))
            }
        }.start()
    }

    private fun transcribeWithVosk(
        context: Context,
        audioFile: File,
        onProgress: (Int, String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            onProgress(10, context.getString(R.string.transcribe_loading_vosk))
            val model = getVoskModel(context)
            if (model == null) {
                onError(context.getString(R.string.transcribe_vosk_not_found))
                return@Thread
            }

            var recognizer: Recognizer? = null
            try {
                onProgress(25, context.getString(R.string.transcribe_decoding_audio))
                val (pcmData, sampleRate) = decodeAudioToPCM(audioFile) ?: run {
                    onError(context.getString(R.string.transcribe_decode_failed))
                    return@Thread
                }

                recognizer = Recognizer(model, sampleRate.toFloat())
                BufferedInputStream(java.io.ByteArrayInputStream(pcmData)).use { bis ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    val totalBytes = pcmData.size.coerceAtLeast(1)
                    var processedBytes = 0

                    onProgress(40, context.getString(R.string.transcribe_vosk_analyzing))

                    while (bis.read(buffer).also { bytesRead = it } != -1) {
                        recognizer.acceptWaveForm(buffer, bytesRead)
                        processedBytes += bytesRead
                        val percent = (40 + (processedBytes.toDouble() / totalBytes * 50)).toInt().coerceIn(40, 90)
                        onProgress(percent, context.getString(R.string.transcribe_vosk_analyzing_pct, percent))
                    }
                }

                onProgress(95, context.getString(R.string.transcribe_processing_text))
                val finalResultJson = recognizer.finalResult
                val text = parseVoskJson(finalResultJson)

                onProgress(100, context.getString(R.string.transcribe_completed_short))
                if (text.isNotBlank()) onResult(text) else onResult(context.getString(R.string.transcribe_no_speech))

            } catch (e: Exception) {
                Log.e(TAG, "Errore Vosk", e)
                onError(context.getString(R.string.transcribe_vosk_error_msg, e.message ?: ""))
            } finally {
                try { recognizer?.close() } catch (_: Exception) {}
                // Rilascia esplicitamente il modello nativo Vosk (~45 MB Native Heap) per evitare leak ZRAM/RAM
                try { model.close() } catch (_: Exception) {}
            }
        }.start()
    }

    // --- UTILITY ---

    private fun resampleTo16k(input: FloatArray, sourceSampleRate: Int): FloatArray {
        if (sourceSampleRate == 16000) return input
        val ratio = sourceSampleRate.toDouble() / 16000.0
        val outputLength = (input.size / ratio).toInt()
        val output = FloatArray(outputLength)
        for (i in 0 until outputLength) {
            val position = i * ratio
            val index = position.toInt()
            val fraction = (position - index).toFloat()
            if (index >= input.size - 1) output[i] = input[input.size - 1]
            else output[i] = input[index] + (input[index + 1] - input[index]) * fraction
        }
        return output
    }

    private fun shortBytesToFloat(bytes: ByteArray): FloatArray {
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val floats = FloatArray(shorts.size)
        for (i in shorts.indices) floats[i] = shorts[i] / 32768.0f
        return floats
    }

    private fun getVoskModel(context: Context): Model? {
        val modelPath = File(context.getExternalFilesDir(null), VOSK_DIR_NAME)
        return if (modelPath.exists()) try { Model(modelPath.absolutePath) } catch (e: Exception) { null } else null
    }

    private fun unzipModel(zipFile: File, targetDir: File) {
        val destDir = File(targetDir, VOSK_DIR_NAME)
        if (destDir.exists()) destDir.deleteRecursively()
        var extractedRootName: String? = null
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.name.contains("__MACOSX")) {
                    val newFile = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        newFile.mkdirs()
                        if (extractedRootName == null) extractedRootName = entry.name.substringBefore("/")
                    } else {
                        newFile.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(newFile)).use { bos ->
                            val buffer = ByteArray(4096)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) bos.write(buffer, 0, len)
                        }
                    }
                }
                entry = zis.nextEntry
            }
        }
        if (extractedRootName != null) {
            val extractedDir = File(targetDir, extractedRootName!!.removeSuffix("/"))
            if (extractedDir.exists() && extractedDir.name != VOSK_DIR_NAME) extractedDir.renameTo(destDir)
        }
    }

    private fun parseVoskJson(json: String): String {
        return try {
            val regex = "\"text\"\\s*:\\s*\"(.*?)\"".toRegex()
            regex.find(json)?.groupValues?.get(1) ?: ""
        } catch (e: Exception) { json }
    }

    private fun decodeAudioToPCM(inputFile: File): Pair<ByteArray, Int>? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            try { extractor.setDataSource(inputFile.absolutePath) } catch (e: IOException) { return null }
            var format: MediaFormat? = null
            var mime = ""
            var sampleRate = 44100
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    mime = m
                    format = f
                    trackIndex = i
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            val outputStream = ByteArrayOutputStream()
            var isEOS = false
            while (true) {
                if (!isEOS) {
                    val inIndex = decoder.dequeueInputBuffer(5000)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)
                        val sampleSize = extractor.readSampleData(buffer!!, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 5000)
                if (outIndex >= 0) {
                    val outBuffer = decoder.getOutputBuffer(outIndex)
                    val chunk = ByteArray(info.size)
                    outBuffer?.get(chunk)
                    outBuffer?.clear()
                    outputStream.write(chunk)
                    decoder.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && isEOS) break
            }
            return Pair(outputStream.toByteArray(), sampleRate)
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}