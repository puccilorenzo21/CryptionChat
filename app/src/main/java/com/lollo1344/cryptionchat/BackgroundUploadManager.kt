package com.lollo1344.cryptionchat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import id.zelory.compressor.Compressor
import kotlinx.coroutines.*
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Call
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object BackgroundUploadManager {

    private const val TAG = "BackgroundUpload"
    private const val CHANNEL_ID = "file_upload_channel"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class UploadProgressState(val text: String, val progress: Int)

    data class UploadMetadata(
        val userId: Int,
        val groupId: Int,
        val username: String,
        val groupName: String,
        val isPrivateChat: Boolean
    )

    val activeUploads = ConcurrentHashMap<String, UploadProgressState>()
    val uploadMetadataMap = ConcurrentHashMap<String, UploadMetadata>()
    val activeJobs = ConcurrentHashMap<String, Job>()
    val activeCalls = ConcurrentHashMap<String, Call<*>>() // Traccia le chiamate di rete attive per chiuderle sul socket

    private val lastNotificationUpdateTimes = ConcurrentHashMap<String, Long>()
    private val lastNotificationTexts = ConcurrentHashMap<String, String>()

    private val uploadingUris = ConcurrentHashMap.newKeySet<String>()
    private val secureRandom = SecureRandom()

    private fun generateRandomHex(numBytes: Int): String {
        val bytes = ByteArray(numBytes)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Volatile
    private var progressListener: ((String, String, Int) -> Unit)? = null

    fun setProgressListener(listener: ((String, String, Int) -> Unit)?) {
        this.progressListener = listener
        if (listener != null) {
            activeUploads.forEach { (tempId, state) ->
                listener.invoke(tempId, state.text, state.progress)
            }
        }
    }

    /**
     * Annulla l'upload in corso: chiude il socket di rete e interrompe la Coroutine.
     */
    fun cancelUpload(context: Context, tempId: String) {
        Log.i(TAG, "Richiesta annullamento upload per tempId=$tempId")
        activeCalls.remove(tempId)?.cancel() // Chiude subito la connessione OkHttp/Retrofit
        val job = activeJobs.remove(tempId)
        job?.cancel() // Ferma la coroutine
        notifyFinished(context, tempId, "Allegato", false)
    }

    private fun notifyProgress(context: Context, tempId: String, text: String, progress: Int, fileName: String) {
        activeUploads[tempId] = UploadProgressState(text, progress)

        Handler(Looper.getMainLooper()).post {
            progressListener?.invoke(tempId, text, progress)
        }

        val now = System.currentTimeMillis()
        val lastUpdate = lastNotificationUpdateTimes[tempId] ?: 0L
        val lastText = lastNotificationTexts[tempId]
        val isTextChanged = lastText != text

        if (progress == 0 || progress == 100 || isTextChanged || (now - lastUpdate) >= 800L) {
            lastNotificationUpdateTimes[tempId] = now
            lastNotificationTexts[tempId] = text

            val meta = uploadMetadataMap[tempId]
            if (meta != null) {
                updateSystemProgressNotification(
                    context, tempId, fileName, text, progress,
                    meta.userId, meta.groupId, meta.username, meta.groupName, meta.isPrivateChat
                )
            }
        }
    }

    private fun notifyFinished(context: Context, tempId: String, fileName: String, isSuccess: Boolean) {
        activeUploads.remove(tempId)
        uploadMetadataMap.remove(tempId)
        activeJobs.remove(tempId)
        activeCalls.remove(tempId)
        lastNotificationUpdateTimes.remove(tempId)
        lastNotificationTexts.remove(tempId)

        Handler(Looper.getMainLooper()).post {
            progressListener?.invoke(tempId, "", -1)
        }
        dismissSystemProgressNotification(context, tempId, fileName, isSuccess)
    }

    fun startUpload(
        context: Context,
        uri: Uri,
        mimeType: String,
        caption: String?,
        userId: Int,
        groupId: Int,
        username: String,
        groupName: String,
        isPrivateChat: Boolean,
        replyToId: Int?,
        isViewOnce: Boolean,
        mentionedUsersJson: String?,
        currentGroupKey: String,
        currentGroupIv: String,
        onLocalMessageCreated: () -> Unit,
        isE2eeV2: Boolean = false,
        metadataEncryptor: ((String) -> String?)? = null
    ) {
        val uriString = uri.toString()

        if (uploadingUris.contains(uriString)) {
            Log.w(TAG, "Upload per questo file già in corso, salto duplicato: $uriString")
            return
        }
        uploadingUris.add(uriString)

        val tempId = UUID.randomUUID().toString()
        uploadMetadataMap[tempId] = UploadMetadata(userId, groupId, username, groupName, isPrivateChat)

        var compressedVideoFile: File? = null
        var tempFileToSend: File? = null

        val uploadJob = scope.launch {
            var isSuccess = false
            var fileName = context.getString(R.string.upload_default_attachment_name)

            try {
                val fileDetails = FileUtils.getFileDetails(context, uri)
                if (fileDetails != null) {
                    fileName = fileDetails.name
                }

                notifyProgress(context, tempId, context.getString(R.string.upload_preparing_file), 0, fileName)

                var fileToSend = FileUtils.getFileFromUri(context, uri) ?: throw IOException(context.getString(R.string.upload_temp_file_error))
                tempFileToSend = fileToSend
                var audioDuration: Int? = null

                val typeStr = when {
                    mimeType.startsWith("image/") -> "image"
                    mimeType.startsWith("audio/") -> "audio"
                    else -> "file"
                }

                val prefs = SecurePrefs.get(context)
                val serverKey = prefs.getString("key_$groupId", null)
                val isV2Upload = isE2eeV2 ||
                    metadataEncryptor != null ||
                    prefs.getInt("e2ee_version_$groupId", 1) >= 2 ||
                    currentGroupKey.isBlank() ||
                    (!serverKey.isNullOrEmpty() && currentGroupKey != serverKey) ||
                    DoubleRatchetHelper.hasActiveSession(currentGroupKey)

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val currentUtcTimestamp = sdf.format(Date())

                val finalFileToUpload: File
                val uploadLabel: String

                if (mimeType.startsWith("video/")) {
                    notifyProgress(context, tempId, context.getString(R.string.upload_compressing_video), 0, fileName)

                    compressedVideoFile = File(context.cacheDir, "vid_${System.currentTimeMillis()}.mp4")

                    try {
                        withTimeout(45000L) {
                            compressVideo(context, fileToSend, compressedVideoFile!!) { progress ->
                                val progressPercent = (progress * 100).toInt().coerceIn(0, 100)
                                notifyProgress(context, tempId, context.getString(R.string.upload_compressing_video), progressPercent, fileName)
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(TAG, "La compressione video ha superato il tempo massimo di sicurezza (45s). Procedo con l'originale.")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "La compressione video ha riscontrato un errore: ${e.message}. Procedo con l'originale.")
                    }

                    finalFileToUpload = if (compressedVideoFile != null && compressedVideoFile!!.exists() && compressedVideoFile!!.length() > 50 * 1024 && compressedVideoFile!!.length() < fileToSend.length()) {
                        compressedVideoFile!!
                    } else {
                        Log.i(TAG, "Uso file originale.")
                        fileToSend
                    }
                    uploadLabel = context.getString(R.string.upload_sending_video)
                } else {
                    if (mimeType.startsWith("image/") && mimeType != "image/gif" && mimeType != "image/webp") {
                        notifyProgress(context, tempId, context.getString(R.string.upload_processing_image), 20, fileName)
                        fileToSend = compressImageSafely(context, fileToSend)
                    } else if (mimeType.startsWith("audio/")) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(fileToSend.absolutePath)
                            audioDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
                        } finally {
                            try {
                                retriever.release()
                            } catch (_: Exception) {}
                        }
                    }
                    finalFileToUpload = fileToSend
                    uploadLabel = context.getString(R.string.upload_sending_file)
                }

                val pendingMsg = PendingMessage(
                    tempId = tempId,
                    userId = userId,
                    groupId = groupId,
                    encryptedMessage = "",
                    replyToMessageId = replyToId,
                    isViewOnce = isViewOnce,
                    mentionedUsersJson = mentionedUsersJson,
                    plainText = caption ?: "",
                    username = username,
                    timestamp = currentUtcTimestamp,
                    isTextOnly = false,
                    messageType = typeStr,
                    fileLocalPath = finalFileToUpload.absolutePath,
                    fileName = fileName,
                    fileSize = finalFileToUpload.length(),
                    audioDuration = audioDuration,
                    fileMimeType = mimeType
                )

                withContext(Dispatchers.Main) {
                    PendingMessageManager.addMessage(pendingMsg)
                    onLocalMessageCreated()
                }

                encryptAndSendFile(
                    context = context,
                    tempId = tempId,
                    fileToUpload = finalFileToUpload,
                    fileName = fileName,
                    mimeType = mimeType,
                    typeStr = typeStr,
                    audioDuration = audioDuration,
                    caption = caption,
                    userId = userId,
                    groupId = groupId,
                    replyToId = replyToId,
                    isViewOnce = isViewOnce,
                    mentionedUsersJson = mentionedUsersJson,
                    currentGroupKey = currentGroupKey,
                    currentGroupIv = currentGroupIv,
                    isV2Upload = isV2Upload,
                    metadataEncryptor = metadataEncryptor,
                    uploadLabel = uploadLabel
                )

                isSuccess = true

            } catch (e: CancellationException) {
                Log.i(TAG, "Upload annullato dall'utente.")
            } catch (e: Exception) {
                Log.e(TAG, "Caricamento in background fallito", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, context.getString(R.string.upload_error_toast, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            } finally {
                activeCalls.remove(tempId)
                compressedVideoFile?.let { PanicHelper.secureDelete(it) }
                tempFileToSend?.let {
                    if (it.absolutePath.startsWith(context.cacheDir.absolutePath)) {
                        PanicHelper.secureDelete(it)
                    }
                }
                uploadingUris.remove(uriString)
                PendingMessageManager.removeMessage(tempId)
                notifyFinished(context, tempId, fileName, isSuccess)
            }
        }

        activeJobs[tempId] = uploadJob
    }

    private suspend fun encryptAndSendFile(
        context: Context,
        tempId: String,
        fileToUpload: File,
        fileName: String,
        mimeType: String,
        typeStr: String,
        audioDuration: Int?,
        caption: String?,
        userId: Int,
        groupId: Int,
        replyToId: Int?,
        isViewOnce: Boolean,
        mentionedUsersJson: String?,
        currentGroupKey: String,
        currentGroupIv: String,
        isV2Upload: Boolean,
        metadataEncryptor: ((String) -> String?)?,
        uploadLabel: String
    ) {
        val fileKeyHex = if (isV2Upload) generateRandomHex(32) else currentGroupKey
        val fileIvHex = if (isV2Upload) generateRandomHex(16) else currentGroupIv

        val metadata = if (isV2Upload) {
            FileMetadata(
                name = fileName,
                mime = mimeType,
                size = fileToUpload.length(),
                duration = audioDuration,
                caption = caption,
                fileKeyHex = fileKeyHex,
                fileIvHex = fileIvHex,
                msgType = typeStr
            )
        } else {
            FileMetadata(
                name = fileName,
                mime = mimeType,
                size = fileToUpload.length(),
                duration = audioDuration,
                caption = caption
            )
        }

        val metadataJson = Gson().toJson(metadata)
        val encMetadata = metadataEncryptor?.invoke(metadataJson)
            ?: EncryptionHelper.encrypt(metadataJson, currentGroupKey, currentGroupIv)
            ?: throw SecurityException(context.getString(R.string.upload_metadata_encrypt_error))

        // Per upload v2 inviamo message_type opaco "file" al server: il tipo reale viaggia cifrato in FileMetadata
        val wireMessageType = if (isV2Upload) "file" else typeStr
        val typeBody = wireMessageType.toRequestBody("text/plain".toMediaTypeOrNull())

        val tempEncFile = File(context.cacheDir, "temp_enc_${System.currentTimeMillis()}.dat")
        try {
            if (mimeType.startsWith("video/")) {
                notifyProgress(context, tempId, context.getString(R.string.upload_encrypting_file), 100, fileName)
            }

            FileInputStream(fileToUpload).use { fis ->
                FileOutputStream(tempEncFile).use { fos ->
                    EncryptionHelper.encryptStream(fis, fos, fileKeyHex, fileIvHex)
                }
            }

            val reqFile = ProgressRequestBody(
                file = tempEncFile,
                contentType = "application/octet-stream",
                isCancelled = { activeJobs[tempId]?.isCancelled == true || !activeJobs.containsKey(tempId) },
                onProgressUpdate = { prog -> notifyProgress(context, tempId, uploadLabel, prog, fileName) }
            )

            val body = MultipartBody.Part.createFormData("encrypted_file", "zk_payload.enc", reqFile)

            val call = ApiClient.instance.sendFile(
                action = "send_file".toRequestBody("text/plain".toMediaTypeOrNull()),
                userId = userId.toString().toRequestBody("text/plain".toMediaTypeOrNull()),
                groupId = groupId.toString().toRequestBody("text/plain".toMediaTypeOrNull()),
                encryptedMetadata = encMetadata.toRequestBody("text/plain".toMediaTypeOrNull()),
                encryptedFile = body,
                replyToMessageId = replyToId?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull()),
                isViewOnce = isViewOnce.toString().toRequestBody("text/plain".toMediaTypeOrNull()),
                mentionedUsersJson = mentionedUsersJson?.toRequestBody("text/plain".toMediaTypeOrNull()),
                messageType = typeBody
            )

            activeCalls[tempId] = call
            val response = withContext(Dispatchers.IO) { call.execute() }

            if (!response.isSuccessful || response.body()?.status != "success") {
                throw IOException(context.getString(R.string.upload_server_failed))
            }
        } finally {
            PanicHelper.secureDelete(tempEncFile)
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.upload_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.upload_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateSystemProgressNotification(
        context: Context,
        tempId: String,
        fileName: String,
        text: String,
        progress: Int,
        userId: Int,
        groupId: Int,
        username: String,
        groupName: String,
        isPrivateChat: Boolean
    ) {
        createNotificationChannel(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = tempId.hashCode()

        val chatIntent = Intent(context, ChatActivity::class.java).apply {
            putExtra("GROUP_ID", groupId)
            putExtra("GROUP_NAME", groupName)
            putExtra("USER_ID", userId)
            putExtra("USERNAME", username)
            putExtra("IS_PRIVATE_CHAT", isPrivateChat)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            chatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file_download)
            .setContentTitle(text)
            .setContentText(fileName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0 && text == context.getString(R.string.upload_preparing_file))
            .setContentIntent(pendingIntent)

        notificationManager.notify(notificationId, builder.build())
    }

    private fun dismissSystemProgressNotification(
        context: Context,
        tempId: String,
        fileName: String,
        isSuccess: Boolean
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = tempId.hashCode()

        notificationManager.cancel(notificationId)

        if (isSuccess) {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_check_circle)
                .setContentTitle(context.getString(R.string.upload_completed_title))
                .setContentText(fileName)
                .setOngoing(false)
                .setAutoCancel(true)
                .setTimeoutAfter(3000)

            notificationManager.notify(notificationId, builder.build())
        }
    }

    private fun getVideoDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Errore nel recupero della durata del video: ${e.message}")
            0L
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {}
        }
    }

    private fun getVideoBitrate(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)

            val bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val metadataBitrate = bitrateStr?.toLongOrNull() ?: -1L

            if (metadataBitrate > 0) {
                metadataBitrate
            } else {
                val durationMs = getVideoDuration(file)
                if (durationMs > 0) {
                    val sizeInBits = file.length() * 8L
                    val durationInSec = durationMs / 1000.0
                    (sizeInBits / durationInSec).toLong()
                } else {
                    -1L
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore nel calcolo del bitrate: ${e.message}")
            -1L
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {}
        }
    }

    private suspend fun compressVideo(
        context: Context,
        sourceFile: File,
        destFile: File,
        onProgress: (Double) -> Unit
    ): File = suspendCancellableCoroutine { continuation ->
        val listener = object : TranscoderListener {
            override fun onTranscodeProgress(progress: Double) {
                onProgress(progress)
            }
            override fun onTranscodeCompleted(successCode: Int) {
                continuation.resume(destFile)
            }
            override fun onTranscodeCanceled() {
                if (continuation.isActive) continuation.cancel()
            }
            override fun onTranscodeFailed(exception: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(exception)
            }
        }

        val originalBitrate = getVideoBitrate(sourceFile)
        val fileSize = sourceFile.length()

        val targetResolution: Int
        val targetBitrate: Long

        when {
            fileSize > 50 * 1024 * 1024L -> {
                targetResolution = 360
                targetBitrate = if (originalBitrate > 0) minOf(400000L, (originalBitrate * 0.45).toLong()) else 400000L
            }
            fileSize > 20 * 1024 * 1024L -> {
                targetResolution = 480
                targetBitrate = if (originalBitrate > 0) minOf(650000L, (originalBitrate * 0.55).toLong()) else 650000L
            }
            else -> {
                targetResolution = 720
                targetBitrate = if (originalBitrate > 0) minOf(1100000L, (originalBitrate * 0.70).toLong()) else 1000000L
            }
        }

        val videoStrategy = DefaultVideoStrategy.atMost(targetResolution)
            .bitRate(targetBitrate)
            .frameRate(30)
            .build()

        val audioStrategy = DefaultAudioStrategy.builder().build()

        val future = Transcoder.into(destFile.absolutePath)
            .addDataSource(sourceFile.absolutePath)
            .setVideoTrackStrategy(videoStrategy)
            .setAudioTrackStrategy(audioStrategy)
            .setListener(listener)
            .transcode()

        continuation.invokeOnCancellation {
            future.cancel(true)
        }
    }

    private suspend fun compressImageSafely(context: Context, originalFile: File): File {
        return try {
            Compressor.compress(context, originalFile)
        } catch (e: Exception) {
            withContext(Dispatchers.IO) {
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeFile(originalFile.absolutePath, options)

                var inSampleSize = 1
                val reqHeight = 1920
                val reqWidth = 1080
                val height = options.outHeight
                val width = options.outWidth
                if (height > reqHeight || width > reqWidth) {
                    val halfHeight = height / 2
                    val halfWidth = width / 2
                    while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                        inSampleSize *= 2
                    }
                }

                options.inJustDecodeBounds = false
                options.inSampleSize = inSampleSize

                val bitmap = android.graphics.BitmapFactory.decodeFile(originalFile.absolutePath, options)
                    ?: throw Exception("Errore decodifica bitmap")

                val fallbackFile = File(context.cacheDir, "fallback_${System.currentTimeMillis()}.jpg")
                val out = FileOutputStream(fallbackFile)

                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                out.flush()
                out.close()
                bitmap.recycle()

                fallbackFile
            }
        }
    }
}