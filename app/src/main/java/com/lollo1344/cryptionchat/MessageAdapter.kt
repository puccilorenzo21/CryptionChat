package com.lollo1344.cryptionchat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.widget.LinearLayout
import com.google.android.material.progressindicator.LinearProgressIndicator
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.flexbox.FlexboxLayout
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

sealed class ListItem {
    data class MessageItem(val message: Message) : ListItem()
    data class DateItem(val date: String) : ListItem()
}

interface MessageInteractionListener {
    fun onShowReactions(message: Message)
    fun onShowOptions(message: Message)
    fun onReplyClicked(messageId: Int)
    fun onStatusIconClicked(message: Message)
    fun onViewOnceMessageClicked(message: Message)
    fun onReactionChipClicked(message: Message, reactionEmoji: String)
    fun onTranscribeAudioClicked(message: Message)
    fun onImageMessageClicked(message: Message)

    fun onAcceptLocationRequest(message: Message)
    fun onDeclineLocationRequest(message: Message)
}

object DownloadTracker {
    private val downloadStates = ConcurrentHashMap<Int, Int>()
    private val activeJobs = ConcurrentHashMap<Int, Job>()
    private val progressListeners = ConcurrentHashMap<Int, MutableSet<(Int) -> Unit>>()

    fun isDownloading(fileId: Int): Boolean = activeJobs.containsKey(fileId) || downloadStates.containsKey(fileId)
    fun getProgress(fileId: Int): Int? = downloadStates[fileId]

    fun registerListener(fileId: Int, listener: (Int) -> Unit) {
        progressListeners.getOrPut(fileId) { Collections.synchronizedSet(mutableSetOf()) }.add(listener)
        downloadStates[fileId]?.let { listener(it) }
    }

    fun unregisterListener(fileId: Int, listener: (Int) -> Unit) {
        progressListeners[fileId]?.remove(listener)
    }

    fun startDownload(fileId: Int, job: Job) {
        downloadStates[fileId] = 0
        activeJobs[fileId] = job
    }

    fun updateProgress(fileId: Int, progress: Int) {
        downloadStates[fileId] = progress
        val listeners = progressListeners[fileId]?.toList() ?: return
        Handler(Looper.getMainLooper()).post {
            listeners.forEach { it.invoke(progress) }
        }
    }

    fun finishDownload(fileId: Int) {
        downloadStates.remove(fileId)
        activeJobs.remove(fileId)
        progressListeners.remove(fileId)
    }

    fun cancelDownload(fileId: Int) {
        activeJobs.remove(fileId)?.cancel()
        downloadStates.remove(fileId)
        progressListeners.remove(fileId)
    }

    fun isAnyDownloadActive(): Boolean = activeJobs.isNotEmpty() || downloadStates.isNotEmpty()
    fun clearAll() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        downloadStates.clear()
        progressListeners.clear()
    }
}

class MessageAdapter(
    private var items: MutableList<ListItem>,
    private val currentUsername: String,
    private val currentUserId: Int,
    private val listener: MessageInteractionListener,
    private var groupKey: String?,
    private var groupIv: String?,
    var groupId: Int = -1
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileMetadataCache = ConcurrentHashMap<String, FileMetadata>()

    private fun decryptPayload(context: Context?, ciphertext: String?): String? {
        if (ciphertext.isNullOrBlank()) return null
        val isE2eeV2 = ciphertext.startsWith(SenderKeyHelper.SK_PREFIX) ||
                ciphertext.startsWith(DoubleRatchetHelper.DR_PREFIX)
        if (!isE2eeV2 && (groupKey.isNullOrEmpty() || groupIv.isNullOrEmpty())) {
            return null
        }
        if (ciphertext.startsWith(DoubleRatchetHelper.DR_PREFIX)) {
            DoubleRatchetHelper.getVaultPlaintextForPayload(ciphertext)?.let { return it }
        }
        var decrypted = EncryptionHelper.decrypt(ciphertext, groupKey ?: "", groupIv ?: "")
        if (decrypted == null && ciphertext.startsWith(DoubleRatchetHelper.DR_PREFIX) && context != null && groupId > 0) {
            val prefs = SecurePrefs.get(context)
            val peerId = listOf(
                "private_peer_uid_$groupId",
                "peer_user_id_$groupId",
                "other_user_id_$groupId",
                "private_peer_$groupId"
            ).firstNotNullOfOrNull { prefKey ->
                prefs.getInt(prefKey, -1).takeIf { it > 0 }
            }
            if (peerId != null) {
                decrypted = E2eeSessionOrchestrator.decryptPairwiseMessage(
                    context = context,
                    myUserId = currentUserId,
                    peerUserId = peerId,
                    drPayload = ciphertext
                )
            }
        }
        return decrypted
    }

    private fun parseFileMetadata(context: Context?, message: Message): FileMetadata? {
        if (message.tempId != null || message.message.isBlank()) return null
        val cacheKey = "${message.id}:${message.message.hashCode()}"
        fileMetadataCache[cacheKey]?.let { return it }
        val decrypted = decryptPayload(context, message.message) ?: return null
        val trimmed = decrypted.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        return try {
            val meta = Gson().fromJson(trimmed, FileMetadata::class.java)
            if (meta != null) {
                if (fileMetadataCache.size > 500) fileMetadataCache.clear()
                fileMetadataCache[cacheKey] = meta
            }
            meta
        } catch (_: Exception) {
            null
        }
    }

    private fun inferMessageTypeFromMime(mime: String?, fallback: String): String {
        val m = mime?.lowercase(Locale.ROOT) ?: ""
        return when {
            m.startsWith("image/") -> "image"
            m.startsWith("audio/") -> "audio"
            else -> fallback
        }
    }

    private fun resolveMessageWithMetadata(context: Context?, message: Message): Message {
        if (message.tempId != null || !message.fileName.isNullOrEmpty()) return message
        if (message.messageType == "text") return message
        val meta = parseFileMetadata(context, message) ?: return message
        val resolvedType = meta.msgType?.takeIf { it.isNotBlank() }
            ?: inferMessageTypeFromMime(meta.mime, message.messageType)
        return message.copy(
            fileName = meta.name,
            fileMimeType = meta.mime,
            fileSize = meta.size,
            audioDuration = message.audioDuration ?: meta.duration,
            messageType = resolvedType
        )
    }

    private fun isValidContextForGlide(context: Context?): Boolean {
        if (context == null) return false
        var ctx: Context? = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) {
                return !ctx.isDestroyed && !ctx.isFinishing
            }
            ctx = ctx.baseContext
        }
        return true
    }

    private fun loadAnimatedImage(context: Context, source: Any, imageView: ImageView) {
        if (!isValidContextForGlide(context)) return
        try {
            Glide.with(context)
                .asDrawable()
                .load(source)
                .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                    override fun onLoadFailed(
                        e: com.bumptech.glide.load.engine.GlideException?,
                        model: Any?,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                        isFirstResource: Boolean
                    ): Boolean = false

                    override fun onResourceReady(
                        resource: android.graphics.drawable.Drawable,
                        model: Any,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                        dataSource: com.bumptech.glide.load.DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (resource is android.graphics.drawable.Animatable) {
                            resource.start()
                        }
                        return false
                    }
                })
                .into(imageView)
        } catch (e: IllegalArgumentException) {
            Log.w("MessageAdapter", "Caricamento Glide ignorato: Activity distrutta.")
        } catch (e: Exception) {
            Log.e("MessageAdapter", "Errore durante il caricamento immagine con Glide", e)
        }
    }

    private fun getReadableErrorMessage(e: Throwable?): String {
        if (e == null) return "Errore durante il download"
        val msg = e.localizedMessage?.trim() ?: e.message?.trim() ?: ""
        if (msg.isNotEmpty() && msg != "null") return msg
        val causeMsg = e.cause?.localizedMessage?.trim() ?: e.cause?.message?.trim() ?: ""
        if (causeMsg.isNotEmpty() && causeMsg != "null") return causeMsg
        return when (e) {
            is java.io.EOFException -> "Connessione interrotta dal server (download incompleto)"
            is java.net.SocketTimeoutException -> "Tempo di attesa dal server scaduto"
            is java.net.UnknownHostException -> "Impossibile raggiungere il server (rete assente)"
            is javax.crypto.BadPaddingException -> "Chiave di decifratura errata o file corrotto"
            is javax.crypto.IllegalBlockSizeException -> "Dimensione blocco cifrato non valida"
            else -> e.javaClass.simpleName
        }
    }

    fun startFileDownload(
        context: Context,
        message: Message,
        groupKey: String?,
        groupIv: String?,
        currentUserId: Int,
        onFinish: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val fileId = message.fileId ?: return
        if (DownloadTracker.isDownloading(fileId)) return

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val initialCaps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val isConnected = initialCaps != null && initialCaps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)

        if (!isConnected) return

        val job = downloadScope.launch {
            val prefs = SecurePrefs.get(context)
            val msgGroupId = groupId
            val isZk = prefs.getBoolean("is_zk_$msgGroupId", false)

            val fileMeta = parseFileMetadata(context, message)
            val resolvedMessage = if (message.fileName.isNullOrEmpty() && fileMeta != null) {
                message.copy(
                    fileName = fileMeta.name,
                    fileMimeType = fileMeta.mime,
                    fileSize = fileMeta.size,
                    audioDuration = message.audioDuration ?: fileMeta.duration
                )
            } else {
                message
            }

            val metaKey = fileMeta?.fileKeyHex?.takeIf { it.isNotBlank() }
            val metaIv = fileMeta?.fileIvHex?.takeIf { it.isNotBlank() }

            val primaryKey = metaKey ?: if (isZk) prefs.getString("zk_key_$msgGroupId", groupKey) ?: groupKey else groupKey
            val primaryIv = metaIv ?: if (metaKey != null) "" else if (isZk) prefs.getString("zk_iv_$msgGroupId", groupIv) ?: groupIv else groupIv

            val fallbackKey = if (metaKey != null) {
                if (isZk) prefs.getString("zk_key_$msgGroupId", groupKey) ?: groupKey else groupKey
            } else {
                if (isZk) prefs.getString("key_$msgGroupId", null) else prefs.getString("zk_key_$msgGroupId", null)
            }
            val fallbackIv = if (metaKey != null) {
                if (isZk) prefs.getString("zk_iv_$msgGroupId", groupIv) ?: groupIv else groupIv
            } else {
                if (isZk) prefs.getString("iv_$msgGroupId", null) else prefs.getString("zk_iv_$msgGroupId", null)
            }

            if (primaryKey.isNullOrBlank() || (metaKey == null && primaryIv == null)) {
                withContext(Dispatchers.Main) { onError(Exception("Chiavi di cifratura mancanti.")) }
                DownloadTracker.finishDownload(fileId)
                return@launch
            }

            val tempEncryptedFile = File(context.cacheDir, "temp_enc_${fileId}.tmp")
            val finalDecryptedFile = FileUtils.getLocalFile(context, resolvedMessage)

            if (finalDecryptedFile.exists() && finalDecryptedFile.length() > 0) {
                withContext(Dispatchers.Main) {
                    DownloadTracker.updateProgress(fileId, 100)
                    DownloadTracker.finishDownload(fileId)
                    onFinish(finalDecryptedFile)
                }
                return@launch
            }

            var attempt = 0
            var success = false
            var lastException: Exception? = null
            val bufferSize = 64 * 1024

            while (attempt < 6 && !success && isActive) {
                try {
                    attempt++

                    val activeCaps = cm?.getNetworkCapabilities(cm.activeNetwork)
                    val onlineNow = activeCaps != null && activeCaps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    if (!onlineNow) {
                        throw java.net.UnknownHostException("Rete disconnessa")
                    }

                    val existingLength = if (tempEncryptedFile.exists()) tempEncryptedFile.length() else 0L
                    val downloadUrl = "${ApiClient.BASE_URL}download.php?user_id=$currentUserId&file_id=$fileId"
                    val rangeHeader = if (existingLength > 0L) "bytes=$existingLength-" else null

                    Log.d("MessageAdapter", "Tentativo $attempt: chiedo il file partendo dal byte $existingLength")

                    val response = ApiClient.instance.downloadFile(downloadUrl, rangeHeader).execute()

                    if (response.code() == 416) {
                        if (tempEncryptedFile.exists()) tempEncryptedFile.delete()
                        throw IOException("Range non valido o file modificato. Riavvio da zero.")
                    }

                    if (!response.isSuccessful && response.code() != 206) {
                        throw IOException("Errore server HTTP ${response.code()}")
                    }

                    val body = response.body() ?: throw IOException("Risposta vuota dal server")

                    val contentType = body.contentType()
                    if (contentType != null && (contentType.type == "text" || contentType.subtype.contains("html") || contentType.subtype.contains("json"))) {
                        if (tempEncryptedFile.exists()) tempEncryptedFile.delete()
                        throw IOException("Il file non esiste più sul server o l'accesso è negato.")
                    }

                    val isPartial = response.code() == 206
                    val isAppend = isPartial && existingLength > 0L

                    var bytesCopied = if (isAppend) existingLength else 0L
                    val totalContentLength = body.contentLength()
                    val totalSize = if (isPartial && totalContentLength > 0) existingLength + totalContentLength else totalContentLength

                    var readError: Exception? = null
                    try {
                        body.byteStream().use { inputStream ->
                            FileOutputStream(tempEncryptedFile, isAppend).use { outputStream ->
                                val buffer = ByteArray(bufferSize)
                                var bytesRead: Int
                                var lastProgress = -1
                                var lastProgressTime = 0L

                                while (inputStream.read(buffer).also { bytesRead = it } != -1 && isActive) {
                                    outputStream.write(buffer, 0, bytesRead)
                                    bytesCopied += bytesRead

                                    if (totalSize > 0) {
                                        val progress = (85 * bytesCopied / totalSize).toInt().coerceIn(0, 85)
                                        val now = System.currentTimeMillis()

                                        if (progress != lastProgress && (now - lastProgressTime > 250 || progress == 85)) {
                                            lastProgress = progress
                                            lastProgressTime = now
                                            DownloadTracker.updateProgress(fileId, progress)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        readError = e
                        val errMsg = e.message ?: "Connessione interrotta dal server"
                        Log.w("MessageAdapter", "Stream interrotto: $errMsg")
                    }

                    if (readError != null || (totalSize > 0 && bytesCopied < totalSize)) {
                        val errMsg = readError?.message ?: "Connessione chiusa prematuramente"
                        throw java.io.EOFException(errMsg)
                    }

                    if (tempEncryptedFile.length() < 16) {
                        if (tempEncryptedFile.exists()) tempEncryptedFile.delete()
                        throw IOException("File scaricato incompleto o non valido (${tempEncryptedFile.length()} byte).")
                    }

                    DownloadTracker.updateProgress(fileId, 90)

                    var decryptedOk = false
                    try {
                        FileInputStream(tempEncryptedFile).use { fis ->
                            FileOutputStream(finalDecryptedFile).use { fos ->
                                EncryptionHelper.decryptStream(fis, fos, primaryKey, primaryIv ?: "")
                            }
                        }
                        decryptedOk = true
                    } catch (e: Exception) {
                        if (fallbackKey != null && fallbackIv != null) {
                            try {
                                FileInputStream(tempEncryptedFile).use { fis ->
                                    FileOutputStream(finalDecryptedFile).use { fos ->
                                        EncryptionHelper.decryptStream(fis, fos, fallbackKey, fallbackIv)
                                    }
                                }
                                decryptedOk = true
                            } catch (e2: Exception) {
                                Log.e("MessageAdapter", "Decifratura fallita anche con chiave fallback", e2)
                            }
                        }
                    }

                    if (decryptedOk && finalDecryptedFile.exists() && finalDecryptedFile.length() > 0) {
                        DownloadTracker.updateProgress(fileId, 100)
                        success = true
                    } else {
                        if (tempEncryptedFile.exists()) tempEncryptedFile.delete()
                        if (finalDecryptedFile.exists()) finalDecryptedFile.delete()
                        throw IOException("Chiavi di cifratura errate o file corrotto.")
                    }

                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    lastException = e

                    if (e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketException) {
                        break
                    }

                    val msgLower = (e.message ?: "").lowercase(Locale.ROOT)
                    if (msgLower.contains("non esiste") || msgLower.contains("negato") || msgLower.contains("chiavi di cifratura errate")) {
                        if (tempEncryptedFile.exists()) tempEncryptedFile.delete()
                        if (finalDecryptedFile.exists()) finalDecryptedFile.delete()
                        break
                    }

                    if (attempt < 6 && isActive) {
                        if (!tempEncryptedFile.exists()) {
                            DownloadTracker.updateProgress(fileId, 0)
                        }
                        delay(1500)
                    }
                }
            }

            if (tempEncryptedFile.exists()) tempEncryptedFile.delete()

            withContext(Dispatchers.Main) {
                DownloadTracker.finishDownload(fileId)
                if (success) {
                    onFinish(finalDecryptedFile)
                } else {
                    val finalCaps = cm?.getNetworkCapabilities(cm.activeNetwork)
                    val isOnlineNow = finalCaps != null && finalCaps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    if (isOnlineNow) {
                        val errorMsg = getReadableErrorMessage(lastException)
                        onError(Exception(errorMsg, lastException))
                    }
                }
            }
        }

        DownloadTracker.startDownload(fileId, job)
    }

    companion object {
        private const val VIEW_TYPE_SENT_TEXT = 1
        private const val VIEW_TYPE_RECEIVED_TEXT = 2
        private const val VIEW_TYPE_SENT_IMAGE = 3
        private const val VIEW_TYPE_RECEIVED_IMAGE = 4
        private const val VIEW_TYPE_SENT_FILE = 5
        private const val VIEW_TYPE_RECEIVED_FILE = 6
        private const val VIEW_TYPE_DATE_HEADER = 7
        private const val VIEW_TYPE_SENT_AUDIO = 8
        private const val VIEW_TYPE_RECEIVED_AUDIO = 9
        private const val VIEW_TYPE_SENT_VIEW_ONCE = 10
        private const val VIEW_TYPE_RECEIVED_VIEW_ONCE = 11
    }

    private val audioViewHolders = HashMap<Int, BaseAudioViewHolder>()

    private var globalPlayingMessageId: Int = -1
    private var globalIsPlaying: Boolean = false

    private val audioUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioPlayerService.ACTION_PROGRESS_UPDATE -> {
                    val id = intent.getIntExtra(AudioPlayerService.EXTRA_MESSAGE_ID, -1)
                    val currentPos = intent.getIntExtra(AudioPlayerService.EXTRA_CURRENT_POSITION, 0)
                    val duration = intent.getIntExtra(AudioPlayerService.EXTRA_DURATION, 0)

                    if (id != -1) {
                        globalPlayingMessageId = id
                        globalIsPlaying = true

                        audioViewHolders[id]?.updateProgress(currentPos, duration, true)

                        audioViewHolders.keys.filter { it != id }.forEach { otherId ->
                            audioViewHolders[otherId]?.updatePlayState(false)
                        }
                    }
                }
                AudioPlayerService.ACTION_PAUSED_BROADCAST -> {
                    val id = intent.getIntExtra(AudioPlayerService.EXTRA_MESSAGE_ID, -1)
                    if (id == globalPlayingMessageId) {
                        globalIsPlaying = false
                    }
                    audioViewHolders[id]?.updatePlayState(isPlaying = false)
                }
                AudioPlayerService.ACTION_COMPLETE -> {
                    val id = intent.getIntExtra(AudioPlayerService.EXTRA_MESSAGE_ID, -1)
                    if (id == globalPlayingMessageId) {
                        globalIsPlaying = false
                        globalPlayingMessageId = -1
                    }

                    audioViewHolders[id]?.apply {
                        updatePlayState(isPlaying = false)
                        resetProgress()
                    }
                }
            }
        }
    }

    fun registerReceiver(context: Context) {
        val filter = IntentFilter().apply {
            addAction(AudioPlayerService.ACTION_PROGRESS_UPDATE)
            addAction(AudioPlayerService.ACTION_COMPLETE)
            addAction(AudioPlayerService.ACTION_PAUSED_BROADCAST)
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(audioUpdateReceiver, filter)

        syncAudioState()
    }

    private fun syncAudioState() {
        val serviceMsgId = AudioPlayerService.currentPlayingMessageId
        val serviceIsPlaying = AudioPlayerService.isPlayingAudio

        if (serviceMsgId != globalPlayingMessageId || serviceIsPlaying != globalIsPlaying) {
            val oldId = globalPlayingMessageId

            globalPlayingMessageId = serviceMsgId
            globalIsPlaying = serviceIsPlaying

            if (oldId != -1 && oldId != serviceMsgId) {
                audioViewHolders[oldId]?.apply {
                    updatePlayState(false)
                    resetProgress()
                }
            }

            if (serviceMsgId != -1) {
                audioViewHolders[serviceMsgId]?.updatePlayState(serviceIsPlaying)
            }
        }
    }

    fun unregisterReceiver(context: Context) {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(audioUpdateReceiver)
    }

    fun updateEncryptionKeys(key: String?, iv: String?) {
        this.groupKey = key
        this.groupIv = iv
        this.fileMetadataCache.clear()
    }

    fun updateItemAt(position: Int, item: ListItem) {
        if (position in 0 until items.size) {
            items[position] = item
            notifyItemChanged(position)
        }
    }

    fun cancelScope() {
        adapterScope.cancel()
        downloadScope.cancel()
        fileMetadataCache.clear()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        cancelScope()
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = items[position]) {
            is ListItem.DateItem -> VIEW_TYPE_DATE_HEADER
            is ListItem.MessageItem -> {
                val message = resolveMessageWithMetadata(null, item.message)
                val isSent = message.userId == currentUserId
                if (message.isViewOnce) {
                    if (isSent) VIEW_TYPE_SENT_VIEW_ONCE else VIEW_TYPE_RECEIVED_VIEW_ONCE
                } else {
                    when (message.messageType) {
                        "image" -> if (isSent) VIEW_TYPE_SENT_IMAGE else VIEW_TYPE_RECEIVED_IMAGE
                        "file" -> if (isSent) VIEW_TYPE_SENT_FILE else VIEW_TYPE_RECEIVED_FILE
                        "audio" -> if (isSent) VIEW_TYPE_SENT_AUDIO else VIEW_TYPE_RECEIVED_AUDIO
                        else -> if (isSent) VIEW_TYPE_SENT_TEXT else VIEW_TYPE_RECEIVED_TEXT
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SENT_TEXT -> SentMessageViewHolder(inflater.inflate(R.layout.item_message_sent, parent, false))
            VIEW_TYPE_RECEIVED_TEXT -> ReceivedMessageViewHolder(inflater.inflate(R.layout.item_message_received, parent, false))
            VIEW_TYPE_SENT_IMAGE -> SentImageViewHolder(inflater.inflate(R.layout.item_message_sent_image, parent, false))
            VIEW_TYPE_RECEIVED_IMAGE -> ReceivedImageViewHolder(inflater.inflate(R.layout.item_message_received_image, parent, false))
            VIEW_TYPE_SENT_FILE -> SentFileViewHolder(inflater.inflate(R.layout.item_message_sent_file, parent, false))
            VIEW_TYPE_RECEIVED_FILE -> ReceivedFileViewHolder(inflater.inflate(R.layout.item_message_received_file, parent, false))
            VIEW_TYPE_SENT_AUDIO -> SentAudioViewHolder(inflater.inflate(R.layout.item_message_sent_audio, parent, false))
            VIEW_TYPE_RECEIVED_AUDIO -> ReceivedAudioViewHolder(inflater.inflate(R.layout.item_message_received_audio, parent, false))
            VIEW_TYPE_DATE_HEADER -> DateHeaderViewHolder(inflater.inflate(R.layout.item_date_header, parent, false))
            VIEW_TYPE_SENT_VIEW_ONCE -> SentViewOnceViewHolder(inflater.inflate(R.layout.item_message_sent_view_once, parent, false))
            VIEW_TYPE_RECEIVED_VIEW_ONCE -> ReceivedViewOnceViewHolder(inflater.inflate(R.layout.item_message_received_view_once, parent, false))
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.DateItem -> (holder as DateHeaderViewHolder).bind(item)
            is ListItem.MessageItem -> {
                when (holder) {
                    is BaseMessageViewHolder -> {
                        holder.bind(item.message)
                        val isSent = item.message.userId == currentUserId
                        applyGlassmorphismToContainer(holder, isSent)
                    }
                }
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is BaseMessageViewHolder) {
            holder.cleanup()
        }
        if (holder is BaseAudioViewHolder) {
            audioViewHolders.remove(holder.messageId)
        }
    }

    override fun getItemCount(): Int = items.size

    fun getItemAt(position: Int): ListItem = items[position]

    fun getItems(): List<ListItem> {
        return items
    }

    fun findMessagePositionById(messageId: Int): Int {
        return items.indexOfFirst { it is ListItem.MessageItem && it.message.id == messageId }
    }

    fun updateItems(newItems: List<ListItem>) {
        val diffCallback = MessageDiffCallback(this.items, newItems)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        this.items.clear()
        this.items.addAll(newItems)
        diffResult.dispatchUpdatesTo(this)
    }

    fun addMessage(message: Message, context: Context) {
        val newItemsToAdd = mutableListOf<ListItem>()
        newItemsToAdd.add(ListItem.MessageItem(message))
        val positionStart = items.size
        items.addAll(newItemsToAdd)
        notifyItemRangeInserted(positionStart, newItemsToAdd.size)
    }

    fun updateMessageReactions(messageId: Int, newReactions: List<Reaction>?) {
        val index = findMessagePositionById(messageId)
        if (index != -1) {
            val item = items[index]
            if (item is ListItem.MessageItem) {
                val updatedMessage = item.message.copy(reactions = newReactions)
                items[index] = ListItem.MessageItem(updatedMessage)
                notifyItemChanged(index)
            }
        }
    }

    private fun parseTimestamp(timestamp: String): Date? {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            inputFormat.timeZone = TimeZone.getTimeZone("UTC")
            inputFormat.parse(timestamp)
        } catch (e: Exception) { null }
    }

    private fun getFormattedDate(context: Context, date: Date): String {
        val cal = Calendar.getInstance()
        val today = cal.time
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = cal.time
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return when (sdf.format(date)) {
            sdf.format(today) -> "Oggi"
            sdf.format(yesterday) -> "Ieri"
            else -> SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ITALIAN).format(date)
        }
    }

    private fun formatTimestamp(timestamp: String): String {
        return try {
            val date = parseTimestamp(timestamp)
            val outputFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            outputFormat.timeZone = TimeZone.getDefault()
            date?.let { outputFormat.format(it) } ?: ""
        } catch (e: Exception) { "" }
    }

    private fun bindReactions(reactionsLayout: FlexboxLayout, message: Message) {
        val reactions = message.reactions
        reactionsLayout.removeAllViews()

        val visibleReactions = reactions?.filter { it.reactionEmoji != "📋" }

        if (visibleReactions.isNullOrEmpty()) {
            reactionsLayout.visibility = View.GONE
            return
        }

        reactionsLayout.visibility = View.VISIBLE
        val groupedReactions = visibleReactions.groupingBy { it.reactionEmoji }.eachCount()

        groupedReactions.forEach { (emoji, count) ->
            val reactionView = LayoutInflater.from(reactionsLayout.context).inflate(R.layout.view_reaction, reactionsLayout, false) as TextView
            val text = if (count > 1) "$emoji $count" else emoji
            reactionView.text = text
            val currentUserReacted = visibleReactions.any { it.userId == currentUserId && it.reactionEmoji == emoji }
            if (currentUserReacted) reactionView.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(reactionsLayout.context, R.color.primary_container_light))
            reactionView.setOnClickListener { this@MessageAdapter.listener.onReactionChipClicked(message, emoji) }
            reactionsLayout.addView(reactionView)
        }
    }

    private fun getDecryptedReplyContent(message: Message, context: Context? = null): String? {
        if (message.replyToIsViewOnce == true) return "Messaggio effimero"
        val rawReply = message.replyToMessageContent
        if (message.replyToMessageType != "text") {
            val isEncryptedPayload = rawReply?.startsWith(SenderKeyHelper.SK_PREFIX) == true ||
                    rawReply?.startsWith(DoubleRatchetHelper.DR_PREFIX) == true
            if (isEncryptedPayload) {
                val decryptedMeta = decryptPayload(context, rawReply)?.trim()
                if (decryptedMeta != null && decryptedMeta.startsWith("{") && decryptedMeta.endsWith("}")) {
                    try {
                        val meta = Gson().fromJson(decryptedMeta, FileMetadata::class.java)
                        if (meta != null) {
                            return when {
                                meta.msgType == "image" || meta.mime.startsWith("image/") -> "📷 Foto"
                                meta.msgType == "audio" || meta.mime.startsWith("audio/") -> "🎵 Messaggio vocale"
                                meta.msgType == "video" || meta.mime.startsWith("video/") -> "🎥 Video"
                                meta.name.isNotBlank() -> "📎 ${meta.name}"
                                else -> "📎 File"
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            return rawReply
        }

        val decrypted = decryptPayload(context, rawReply)

        if (decrypted?.contains("[REQ_LOC]") == true) {
            return "📍 Richiesta posizione"
        }
        if (decrypted?.contains("https://maps.google.com/?q=") == true) {
            return "📍 Posizione condivisa"
        }

        return decrypted ?: "[Contenuto non disponibile]"
    }

    inner class DateHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dateText: TextView = itemView.findViewById(R.id.textViewDateHeader)
        fun bind(item: ListItem.DateItem) { dateText.text = item.date }
    }

    abstract inner class BaseMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        @SuppressLint("ClickableViewAccessibility")
        open fun bind(message: Message) {
            if (message.tempId != null) {
                itemView.setOnLongClickListener(null)
            } else {
                itemView.setOnLongClickListener { this@MessageAdapter.listener.onShowReactions(message); true }
            }
        }

        open fun cleanup() {
            if (this is BaseFileViewHolder) {
                detachProgressListener()
            }
        }
    }

    inner class SentMessageViewHolder(itemView: View) : BaseMessageViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.textViewMessage)
        private val timestampText: TextView = itemView.findViewById(R.id.textViewTimestamp)
        private val editedStatusText: TextView = itemView.findViewById(R.id.textViewEditedStatus)
        private val statusImage: ImageView = itemView.findViewById(R.id.imageViewStatus)
        private val replyContextLayout: View = itemView.findViewById(R.id.replyContextLayout)
        private val replyAuthorText: TextView = itemView.findViewById(R.id.textViewReplyAuthor)
        private val replyContentText: TextView = itemView.findViewById(R.id.textViewReplyContent)
        private val reactionsLayout: FlexboxLayout = itemView.findViewById(R.id.reactions_layout)

        override fun bind(message: Message) {
            super.bind(message)
            val decryptedMessage = if (message.tempId != null) message.message else decryptPayload(itemView.context, message.message)

            if (decryptedMessage?.contains("[REQ_LOC]") == true) {
                messageText.text = "📍 Hai richiesto la posizione."
            } else {
                messageText.text = decryptedMessage ?: "[Messaggio non decifrabile]"
            }

            timestampText.text = formatTimestamp(message.timestamp)
            editedStatusText.visibility = if (message.isEdited) View.VISIBLE else View.GONE
            if (message.replyToMessageId != null) {
                replyContextLayout.visibility = View.VISIBLE
                replyAuthorText.text = message.replyToUsername
                replyContentText.text = getDecryptedReplyContent(message, itemView.context)
                replyContextLayout.setOnClickListener { this@MessageAdapter.listener.onReplyClicked(message.replyToMessageId) }
            } else replyContextLayout.visibility = View.GONE

            val context = itemView.context
            val readColor = ContextCompat.getColor(context, R.color.read_receipt_yellow)
            val typedValue = TypedValue().also { context.theme.resolveAttribute(com.google.android.material.R.attr.colorOutline, it, true) }
            val grayColor = typedValue.data
            when (message.status) {
                -1 -> { statusImage.setImageResource(R.drawable.ic_clock); statusImage.imageTintList = ColorStateList.valueOf(grayColor) }
                0 -> { statusImage.setImageResource(R.drawable.ic_check); statusImage.imageTintList = ColorStateList.valueOf(grayColor) }
                1 -> { statusImage.setImageResource(R.drawable.ic_check_all); statusImage.imageTintList = ColorStateList.valueOf(grayColor) }
                2 -> { statusImage.setImageResource(R.drawable.ic_check_all); statusImage.imageTintList = ColorStateList.valueOf(readColor) }
            }
            if (message.status > 0) statusImage.setOnClickListener { this@MessageAdapter.listener.onStatusIconClicked(message) } else statusImage.setOnClickListener(null)
            bindReactions(reactionsLayout, message)
        }
    }

    inner class ReceivedMessageViewHolder(itemView: View) : BaseMessageViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.textViewMessage)
        private val usernameText: TextView = itemView.findViewById(R.id.textViewUsername)
        private val timestampText: TextView = itemView.findViewById(R.id.textViewTimestamp)
        private val editedStatusText: TextView = itemView.findViewById(R.id.textViewEditedStatus)
        private val profileImage: ShapeableImageView = itemView.findViewById(R.id.profile_image)
        private val replyContextLayout: View = itemView.findViewById(R.id.replyContextLayout)
        private val replyAuthorText: TextView = itemView.findViewById(R.id.textViewReplyAuthor)
        private val replyContentText: TextView = itemView.findViewById(R.id.textViewReplyContent)
        private val reactionsLayout: FlexboxLayout = itemView.findViewById(R.id.reactions_layout)

        private val locationRequestLayout: View? = itemView.findViewById(R.id.locationRequestLayout)
        private val btnAcceptLocation: MaterialButton? = itemView.findViewById(R.id.btnAcceptLocation)
        private val btnDeclineLocation: MaterialButton? = itemView.findViewById(R.id.btnDeclineLocation)
        private val textDistanceInfo: TextView? = itemView.findViewById(R.id.textViewDistanceInfo)

        @SuppressLint("MissingPermission")
        override fun bind(message: Message) {
            super.bind(message)
            usernameText.text = message.username
            val decryptedMessage = decryptPayload(itemView.context, message.message)

            textDistanceInfo?.visibility = View.GONE

            if (decryptedMessage != null) {
                if (decryptedMessage.contains("[REQ_LOC]")) {
                    messageText.text = "📍 Ti ha richiesto la posizione."
                    val hasAlreadyReplied = items.filterIsInstance<ListItem.MessageItem>().any {
                        it.message.replyToMessageId == message.id && it.message.userId == currentUserId
                    }

                    if (hasAlreadyReplied) {
                        locationRequestLayout?.visibility = View.GONE
                    } else {
                        locationRequestLayout?.visibility = View.VISIBLE
                        btnAcceptLocation?.setOnClickListener {
                            locationRequestLayout?.visibility = View.GONE
                            this@MessageAdapter.listener.onAcceptLocationRequest(message)
                        }
                        btnDeclineLocation?.setOnClickListener {
                            locationRequestLayout?.visibility = View.GONE
                            this@MessageAdapter.listener.onDeclineLocationRequest(message)
                        }
                    }
                }
                else if (decryptedMessage.contains("https://maps.google.com/?q=")) {
                    messageText.text = decryptedMessage
                    locationRequestLayout?.visibility = View.GONE

                    textDistanceInfo?.visibility = View.VISIBLE
                    textDistanceInfo?.text = "🔄 Calcolo distanza..."

                    val regex = Regex("q=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
                    val match = regex.find(decryptedMessage)

                    if (match != null) {
                        val targetLat = match.groupValues[1].toDoubleOrNull()
                        val targetLng = match.groupValues[2].toDoubleOrNull()

                        if (targetLat != null && targetLng != null) {
                            val context = itemView.context
                            val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

                            if (hasPermission) {
                                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                                    .addOnSuccessListener { location ->
                                        if (location != null && textDistanceInfo != null) {
                                            val results = FloatArray(1)
                                            android.location.Location.distanceBetween(location.latitude, location.longitude, targetLat, targetLng, results)
                                            val distanceKm = String.format(Locale.getDefault(), "%.1f", results[0] / 1000.0)
                                            textDistanceInfo.text = "📍 Distanza in linea d'aria: $distanceKm km"
                                        } else {
                                            textDistanceInfo?.text = "📍 Distanza GPS non disponibile"
                                        }
                                    }
                            } else {
                                textDistanceInfo?.text = "📍 Distanza nascosta (GPS non concesso)"
                            }
                        }
                    }
                } else {
                    messageText.text = decryptedMessage
                    locationRequestLayout?.visibility = View.GONE
                }
            } else {
                messageText.text = "[Messaggio non decifrabile]"
                locationRequestLayout?.visibility = View.GONE
            }

            timestampText.text = formatTimestamp(message.timestamp)
            editedStatusText.visibility = if (message.isEdited) View.VISIBLE else View.GONE

            if (!message.profilePictureUrl.isNullOrBlank()) {
                if (isValidContextForGlide(itemView.context)) {
                    Glide.with(itemView.context).load(ApiClient.BASE_URL + message.profilePictureUrl).placeholder(R.drawable.ic_person).into(profileImage)
                }
            } else {
                profileImage.setImageResource(R.drawable.ic_person)
            }

            if (message.replyToMessageId != null) {
                replyContextLayout.visibility = View.VISIBLE
                replyAuthorText.text = if (message.replyToUsername == currentUsername) "Tu" else message.replyToUsername
                replyContentText.text = getDecryptedReplyContent(message, itemView.context)
                replyContextLayout.setOnClickListener { this@MessageAdapter.listener.onReplyClicked(message.replyToMessageId) }
            } else {
                replyContextLayout.visibility = View.GONE
            }
            bindReactions(reactionsLayout, message)
        }
    }

    abstract inner class BaseFileViewHolder(itemView: View) : BaseMessageViewHolder(itemView) {
        var currentFileId: Int = -1
        var currentProgressListener: ((Int) -> Unit)? = null

        fun detachProgressListener() {
            if (currentFileId != -1 && currentProgressListener != null) {
                DownloadTracker.unregisterListener(currentFileId, currentProgressListener!!)
                currentProgressListener = null
            }
        }

        override fun cleanup() {
            super.cleanup()
            detachProgressListener()
        }

        protected fun isAutoDownloadAllowed(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val capabilities = cm?.getNetworkCapabilities(cm.activeNetwork)
            val isConnected = capabilities != null &&
                    capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (!isConnected) return false

            val prefs = SecurePrefs.get(context)
            val globalAuto = prefs.getBoolean("global_auto_download_media", true)
            if (!globalAuto) return false
            if (groupId != -1) {
                val chatAuto = prefs.getBoolean("auto_download_$groupId", true)
                if (!chatAuto) return false
            }
            return true
        }

        protected fun setupFileState(
            message: Message,
            initialIcon: View,
            progressContainer: View,
            progressBar: ProgressBar,
            progressText: TextView,
            finishedIcon: View? = null,
            imageView: ShapeableImageView? = null,
            onAutoDownloadFinished: ((File) -> Unit)? = null
        ) {
            detachProgressListener()

            val fileId = message.fileId ?: -1
            currentFileId = fileId

            (initialIcon.parent as? View)?.visibility = View.VISIBLE

            if (message.tempId != null) {
                initialIcon.visibility = View.GONE
                finishedIcon?.visibility = View.GONE
                progressContainer.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE
                progressText.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                progressText.text = "..."
                return
            }

            val resolvedMessage = resolveMessageWithMetadata(itemView.context, message)
            val localFile = FileUtils.getLocalFile(itemView.context, resolvedMessage)
            val isSentByMe = message.userId == currentUserId
            val shouldAutoDownload = isSentByMe || isAutoDownloadAllowed(itemView.context)

            if (localFile.exists() && localFile.length() > 0) {
                initialIcon.visibility = View.GONE
                progressContainer.visibility = View.GONE
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
                finishedIcon?.visibility = View.VISIBLE
            } else if (DownloadTracker.isDownloading(fileId)) {
                initialIcon.visibility = View.GONE
                finishedIcon?.visibility = View.GONE
                progressContainer.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE
                progressText.visibility = View.VISIBLE

                val currentProg = DownloadTracker.getProgress(fileId) ?: 0
                progressBar.isIndeterminate = (currentProg == 0)
                progressBar.progress = currentProg
                progressText.text = "$currentProg%"

                val uiProgressListener: (Int) -> Unit = { prog ->
                    if (currentProgressListener != null && currentFileId == fileId) {
                        initialIcon.visibility = View.GONE
                        finishedIcon?.visibility = View.GONE
                        progressContainer.visibility = View.VISIBLE
                        progressBar.visibility = View.VISIBLE
                        progressText.visibility = View.VISIBLE
                        progressBar.isIndeterminate = false
                        progressBar.progress = prog
                        progressText.text = "$prog%"
                    }
                }
                currentProgressListener = uiProgressListener
                DownloadTracker.registerListener(fileId, uiProgressListener)

            } else if (fileId != -1 && !message.isViewOnce && shouldAutoDownload) {
                initialIcon.visibility = View.GONE
                finishedIcon?.visibility = View.GONE
                progressContainer.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE
                progressText.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                progressText.text = "0%"

                val uiProgressListener: (Int) -> Unit = { prog ->
                    if (currentProgressListener != null && currentFileId == fileId) {
                        initialIcon.visibility = View.GONE
                        finishedIcon?.visibility = View.GONE
                        progressContainer.visibility = View.VISIBLE
                        progressBar.visibility = View.VISIBLE
                        progressText.visibility = View.VISIBLE
                        progressBar.isIndeterminate = false
                        progressBar.progress = prog
                        progressText.text = "$prog%"
                    }
                }
                currentProgressListener = uiProgressListener
                DownloadTracker.registerListener(fileId, uiProgressListener)

                startFileDownload(
                    context = itemView.context,
                    message = message,
                    groupKey = groupKey,
                    groupIv = groupIv,
                    currentUserId = currentUserId,
                    onFinish = { downloadedFile ->
                        detachProgressListener()
                        progressContainer.visibility = View.GONE
                        progressBar.visibility = View.GONE
                        progressText.visibility = View.GONE
                        finishedIcon?.visibility = View.VISIBLE
                        onAutoDownloadFinished?.invoke(downloadedFile)
                    },
                    onError = { e ->
                        detachProgressListener()
                        progressContainer.visibility = View.GONE
                        progressBar.visibility = View.GONE
                        progressText.visibility = View.GONE
                        initialIcon.visibility = View.VISIBLE
                        finishedIcon?.visibility = View.GONE
                        // Gestito silenziosamente in background senza popup Toast
                    }
                )
            } else {
                initialIcon.visibility = View.VISIBLE
                progressContainer.visibility = View.GONE
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
                finishedIcon?.visibility = View.GONE
            }
        }

        protected fun createClickListenerForFileInteraction(
            message: Message,
            initialIcon: View,
            progressContainer: View,
            progressBar: ProgressBar,
            progressText: TextView,
            finishedIcon: View? = null,
            imageView: ShapeableImageView? = null,
            onDownloadFinished: ((File) -> Unit)? = null
        ): View.OnClickListener {
            return View.OnClickListener {
                val resolvedMessage = resolveMessageWithMetadata(itemView.context, message)
                val currentLocalFile = FileUtils.getLocalFile(itemView.context, resolvedMessage)

                if (currentLocalFile.exists() && currentLocalFile.length() > 0) {
                    onDownloadFinished?.invoke(currentLocalFile) ?: openDownloadedFile(itemView.context, currentLocalFile, resolvedMessage)
                    return@OnClickListener
                }

                if (message.fileId == null && !message.fileUrl.isNullOrEmpty()) {
                    val pendingFile = File(message.fileUrl)
                    if (pendingFile.exists() && pendingFile.length() > 0) {
                        openDownloadedFile(itemView.context, pendingFile, resolvedMessage)
                        return@OnClickListener
                    }
                }

                val fileId = message.fileId
                if (fileId == null) {
                    Toast.makeText(itemView.context, "File non ancora pronto sul server.", Toast.LENGTH_SHORT).show()
                    return@OnClickListener
                }

                if (DownloadTracker.isDownloading(fileId)) {
                    (initialIcon.parent as? View)?.visibility = View.VISIBLE
                    initialIcon.visibility = View.GONE
                    finishedIcon?.visibility = View.GONE
                    progressContainer.visibility = View.VISIBLE
                    progressBar.visibility = View.VISIBLE
                    progressText.visibility = View.VISIBLE

                    val currentProg = DownloadTracker.getProgress(fileId) ?: 0
                    progressBar.isIndeterminate = (currentProg == 0)
                    progressBar.progress = currentProg
                    progressText.text = "$currentProg%"

                    detachProgressListener()
                    val uiProgressListener: (Int) -> Unit = { prog ->
                        if (currentProgressListener != null && currentFileId == fileId) {
                            initialIcon.visibility = View.GONE
                            finishedIcon?.visibility = View.GONE
                            progressContainer.visibility = View.VISIBLE
                            progressBar.visibility = View.VISIBLE
                            progressText.visibility = View.VISIBLE
                            progressBar.isIndeterminate = false
                            progressBar.progress = prog
                            progressText.text = "$prog%"
                        }
                    }
                    currentProgressListener = uiProgressListener
                    DownloadTracker.registerListener(fileId, uiProgressListener)
                    return@OnClickListener
                }

                initialIcon.visibility = View.GONE
                finishedIcon?.visibility = View.GONE
                progressContainer.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE
                progressText.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                progressText.text = "0%"

                detachProgressListener()
                val uiProgressListener: (Int) -> Unit = { prog ->
                    if (currentProgressListener != null && currentFileId == fileId) {
                        initialIcon.visibility = View.GONE
                        finishedIcon?.visibility = View.GONE
                        progressContainer.visibility = View.VISIBLE
                        progressBar.visibility = View.VISIBLE
                        progressText.visibility = View.VISIBLE
                        progressBar.isIndeterminate = false
                        progressBar.progress = prog
                        progressText.text = "$prog%"
                    }
                }
                currentProgressListener = uiProgressListener
                DownloadTracker.registerListener(fileId, uiProgressListener)

                startFileDownload(
                    context = itemView.context,
                    message = message,
                    groupKey = groupKey,
                    groupIv = groupIv,
                    currentUserId = currentUserId,
                    onFinish = { downloadedFile ->
                        detachProgressListener()
                        progressContainer.visibility = View.GONE
                        progressBar.visibility = View.GONE
                        progressText.visibility = View.GONE
                        finishedIcon?.visibility = View.VISIBLE
                        onDownloadFinished?.invoke(downloadedFile) ?: openDownloadedFile(itemView.context, downloadedFile, resolvedMessage)
                    },
                    onError = { e ->
                        detachProgressListener()
                        progressContainer.visibility = View.GONE
                        progressBar.visibility = View.GONE
                        progressText.visibility = View.GONE
                        initialIcon.visibility = View.VISIBLE
                        finishedIcon?.visibility = View.GONE
                        Toast.makeText(itemView.context, "Errore download: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        protected fun openDownloadedFile(context: Context, file: File, message: Message) {
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                val extension = file.extension.lowercase(Locale.ROOT)

                val mimeType = if (extension == "apk") {
                    "application/vnd.android.package-archive"
                } else {
                    message.fileMimeType ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                }

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, "Nessuna app trovata per aprire questo formato di file.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Impossibile aprire il file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    abstract inner class BaseImageViewHolder(itemView: View) : BaseFileViewHolder(itemView) {
        protected abstract val imageView: ShapeableImageView
        protected abstract val progressContainer: FrameLayout
        protected abstract val progressBar: ProgressBar
        protected abstract val progressText: TextView
        protected abstract val downloadIcon: ImageView
        protected abstract val captionText: TextView?

        fun bindImage(message: Message) {
            setupFileState(
                message = message,
                initialIcon = downloadIcon,
                progressContainer = progressContainer,
                progressBar = progressBar,
                progressText = progressText,
                imageView = imageView,
                onAutoDownloadFinished = { downloadedFile ->
                    if (isValidContextForGlide(itemView.context)) {
                        imageView.visibility = View.VISIBLE
                        loadAnimatedImage(itemView.context, downloadedFile, imageView)
                    }
                }
            )

            val resolvedImageMessage = resolveMessageWithMetadata(itemView.context, message)
            val currentLocalFile = FileUtils.getLocalFile(itemView.context, resolvedImageMessage)
            if (currentLocalFile.exists() && currentLocalFile.length() > 0) {
                if (isValidContextForGlide(itemView.context)) {
                    imageView.visibility = View.VISIBLE
                    loadAnimatedImage(itemView.context, currentLocalFile, imageView)
                }
            }

            val clickListener = View.OnClickListener {
                val fileId = message.fileId ?: return@OnClickListener
                val resolvedMessage = resolveMessageWithMetadata(itemView.context, message)
                val localFile = FileUtils.getLocalFile(itemView.context, resolvedMessage)

                if (localFile.exists() && localFile.length() > 0) {
                    this@MessageAdapter.listener.onImageMessageClicked(resolvedMessage)
                } else {
                    if (DownloadTracker.isDownloading(fileId)) {
                        downloadIcon.visibility = View.GONE
                        progressContainer.visibility = View.VISIBLE
                        val currentProg = DownloadTracker.getProgress(fileId) ?: 0
                        progressBar.isIndeterminate = (currentProg == 0)
                        progressBar.progress = currentProg
                        progressText.text = "$currentProg%"

                        detachProgressListener()
                        val uiProgressListener: (Int) -> Unit = { prog ->
                            if (currentProgressListener != null && currentFileId == fileId) {
                                downloadIcon.visibility = View.GONE
                                progressContainer.visibility = View.VISIBLE
                                progressBar.isIndeterminate = false
                                progressBar.progress = prog
                                progressText.text = "$prog%"
                            }
                        }
                        currentProgressListener = uiProgressListener
                        DownloadTracker.registerListener(fileId, uiProgressListener)
                        return@OnClickListener
                    }

                    downloadIcon.visibility = View.GONE
                    progressContainer.visibility = View.VISIBLE
                    progressBar.isIndeterminate = true
                    progressText.text = "0%"

                    detachProgressListener()
                    val uiProgressListener: (Int) -> Unit = { prog ->
                        if (currentProgressListener != null && currentFileId == fileId) {
                            downloadIcon.visibility = View.GONE
                            progressContainer.visibility = View.VISIBLE
                            progressBar.isIndeterminate = false
                            progressBar.progress = prog
                            progressText.text = "$prog%"
                        }
                    }
                    currentProgressListener = uiProgressListener
                    DownloadTracker.registerListener(fileId, uiProgressListener)

                    startFileDownload(
                        context = itemView.context,
                        message = message,
                        groupKey = groupKey,
                        groupIv = groupIv,
                        currentUserId = currentUserId,
                        onFinish = { downloadedFile ->
                            detachProgressListener()
                            progressContainer.visibility = View.GONE
                            if (isValidContextForGlide(itemView.context)) {
                                imageView.visibility = View.VISIBLE
                                loadAnimatedImage(itemView.context, downloadedFile, imageView)
                                this@MessageAdapter.listener.onImageMessageClicked(resolvedMessage)
                            }
                        },
                        onError = { e ->
                            detachProgressListener()
                            progressContainer.visibility = View.GONE
                            downloadIcon.visibility = View.VISIBLE
                            Toast.makeText(itemView.context, "Errore: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            if (message.tempId != null) {
                imageView.setOnClickListener(null)
                downloadIcon.setOnClickListener(null)
            } else {
                imageView.setOnClickListener(clickListener)
                downloadIcon.setOnClickListener(clickListener)
            }

            if (captionText != null) {
                if (message.tempId != null) {
                    if (!message.message.isNullOrBlank()) {
                        captionText!!.text = message.message
                        captionText!!.visibility = View.VISIBLE
                    } else {
                        captionText!!.visibility = View.GONE
                    }
                } else {
                    captionText!!.visibility = View.GONE
                    val metadata = parseFileMetadata(itemView.context, message)
                    if (metadata != null && !metadata.caption.isNullOrBlank()) {
                        captionText!!.text = metadata.caption
                        captionText!!.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    inner class SentImageViewHolder(itemView: View) : BaseImageViewHolder(itemView) {
        override val imageView: ShapeableImageView = itemView.findViewById(R.id.imageViewSent)
        override val progressContainer: FrameLayout = itemView.findViewById(R.id.progressContainer)
        override val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        override val progressText: TextView = itemView.findViewById(R.id.progressText)
        override val downloadIcon: ImageView = itemView.findViewById(R.id.downloadIcon)
        override val captionText: TextView? = itemView.findViewById(R.id.textImageCaption)
        private val timestampText: TextView = itemView.findViewById(R.id.textViewTimestamp)
        private val statusImage: ImageView = itemView.findViewById(R.id.imageViewStatus)
        private val reactionsLayout: FlexboxLayout = itemView.findViewById(R.id.reactions_layout)

        override fun bind(message: Message) {
            super.bind(message)
            timestampText.text = formatTimestamp(message.timestamp)
            bindImage(message)
            bindReactions(reactionsLayout, message)

            val context = itemView.context
            val readColor = ContextCompat.getColor(context, R.color.read_receipt_yellow)
            val typedValue = TypedValue().also { context.theme.resolveAttribute(com.google.android.material.R.attr.colorOutline, it, true) }
            val grayColor = typedValue.data

            when (message.status) {
                -1 -> { statusImage.setImageResource(R.drawable.ic_clock); statusImage.imageTintList = ColorStateList.valueOf(grayColor) }
                0 -> { statusImage.setImageResource(R.drawable.ic_check); statusImage.imageTintList = ColorStateList.valueOf(grayColor) }
                1 -> { statusImage.setImageResource(R.drawable.ic_check_all); statusImage.imageTintList = ColorStateList.valueOf(grayColor) }
                2 -> { statusImage.setImageResource(R.drawable.ic_check_all); statusImage.imageTintList = ColorStateList.valueOf(readColor) }
            }

            if (message.status > 0) statusImage.setOnClickListener { this@MessageAdapter.listener.onStatusIconClicked(message) } else statusImage.setOnClickListener(null)
        }
    }

    inner class ReceivedImageViewHolder(itemView: View) : BaseImageViewHolder(itemView) {
        override val imageView: ShapeableImageView = itemView.findViewById(R.id.imageViewReceived)
        override val progressContainer: FrameLayout = itemView.findViewById(R.id.progressContainer)
        override val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        override val progressText: TextView = itemView.findViewById(R.id.progressText)
        override val downloadIcon: ImageView = itemView.findViewById(R.id.downloadIcon)
        override val captionText: TextView? = itemView.findViewById(R.id.textImageCaption)
        private val timestampText: TextView = itemView.findViewById(R.id.textViewTimestamp)
        private val usernameText: TextView = itemView.findViewById(R.id.textViewUsername)
        private val profileImage: ShapeableImageView = itemView.findViewById(R.id.profile_image)
        private val reactionsLayout: FlexboxLayout = itemView.findViewById(R.id.reactions_layout)

        override fun bind(message: Message) {
            super.bind(message)
            timestampText.text = formatTimestamp(message.timestamp)
            usernameText.text = message.username
            if (!message.profilePictureUrl.isNullOrBlank()) {
                if (isValidContextForGlide(itemView.context)) {
                    Glide.with(itemView.context).load(ApiClient.BASE_URL + message.profilePictureUrl).placeholder(R.drawable.ic_person).into(profileImage)
                }
            } else profileImage.setImageResource(R.drawable.ic_person)
            bindImage(message)
            bindReactions(reactionsLayout, message)
        }
    }

    inner class SentFileViewHolder(itemView: View) : BaseFileViewHolder(itemView) {
        private val fileName: TextView = itemView.findViewById(R.id.textViewFileName)
        private val fileSize: TextView = itemView.findViewById(R.id.textViewFileSize)
        private val timestampText: TextView = itemView.findViewById(R.id.textViewTimestamp)
        private val fileIcon: ImageView = itemView.findViewById(R.id.fileIcon)
        private val downloadIcon: ImageView = itemView.findViewById(R.id.downloadIcon)
        private val progressContainer: FrameLayout = itemView.findViewById(R.id.progressContainer)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val progressText: TextView = itemView.findViewById(R.id.progressText)
        private val statusImage: ImageView = itemView.findViewById(R.id.imageViewStatus)
        private val reactionsLayout: FlexboxLayout = itemView.findViewById(R.id.reactions_layout)
        private val messageBubble: View = itemView.findViewById(R.id.message_bubble_container)

        override fun bind(message: Message) {
            super.bind(message)

            val resolvedMessage = resolveMessageWithMetadata(itemView.context, message)
            fileName.text = resolvedMessage.fileName ?: "Documento"
            fileSize.text = FileUtils.getFormattedFileSize(resolvedMessage.fileSize ?: 0L)

            timestampText.text = formatTimestamp(message.timestamp)
            bindReactions(reactionsLayout, message)

            val context = itemView.context
            val readColor = ContextCompat.getColor(context, R.color.read_receipt_yellow)
            val typedValue = TypedValue().also { context.theme.resolveAttribute(com.google.android.material.R.attr.colorOutline, it, true) }
            val grayColor = typedValue.data

            when (message.status) {
                -1 -> { statusImage.setImageResource(R.drawable.ic_clock); statusImage.imageTintList = ColorStateList.valueOf(grayColor) }
                0 -> { statusImage.setImageResource(R.drawable.ic_check); statusImage.imageTintList = ColorStateList.valueOf(grayColor) }
                1 -> { statusImage.setImageResource(R.drawable.ic_check_all); statusImage.imageTintList = ColorStateList.valueOf(grayColor) }
                2 -> { statusImage.setImageResource(R.drawable.ic_check_all); statusImage.imageTintList = ColorStateList.valueOf(readColor) }
                else -> statusImage.setImageDrawable(null)
            }

            if (message.status > 0) statusImage.setOnClickListener { this@MessageAdapter.listener.onStatusIconClicked(message) } else statusImage.setOnClickListener(null)

            setupFileState(message = message, initialIcon = fileIcon, finishedIcon = downloadIcon, progressContainer = progressContainer, progressBar = progressBar, progressText = progressText)
            val clickListener = createClickListenerForFileInteraction(message = message, initialIcon = fileIcon, finishedIcon = downloadIcon, progressContainer = progressContainer, progressBar = progressBar, progressText = progressText)

            if (message.tempId != null) {
                messageBubble.setOnClickListener(null)
            } else {
                messageBubble.setOnClickListener(clickListener)
            }
        }
    }

    inner class ReceivedFileViewHolder(itemView: View) : BaseFileViewHolder(itemView) {
        private val fileName: TextView = itemView.findViewById(R.id.textViewFileName)
        private val fileSize: TextView = itemView.findViewById(R.id.textViewFileSize)
        private val timestampText: TextView = itemView.findViewById(R.id.textViewTimestamp)
        private val usernameText: TextView = itemView.findViewById(R.id.textViewUsername)
        private val profileImage: ShapeableImageView = itemView.findViewById(R.id.profile_image)
        private val fileIcon: ImageView = itemView.findViewById(R.id.fileIcon)
        private val downloadIcon: ImageView = itemView.findViewById(R.id.downloadIcon)
        private val progressContainer: FrameLayout = itemView.findViewById(R.id.progressContainer)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val progressText: TextView = itemView.findViewById(R.id.progressText)
        private val reactionsLayout: FlexboxLayout = itemView.findViewById(R.id.reactions_layout)
        private val messageBubble: View = itemView.findViewById(R.id.message_bubble_container)

        override fun bind(message: Message) {
            super.bind(message)

            val resolvedMessage = resolveMessageWithMetadata(itemView.context, message)
            fileName.text = resolvedMessage.fileName ?: "Documento"
            fileSize.text = FileUtils.getFormattedFileSize(resolvedMessage.fileSize ?: 0L)

            timestampText.text = formatTimestamp(message.timestamp)
            usernameText.text = message.username
            if (!message.profilePictureUrl.isNullOrBlank()) {
                if (isValidContextForGlide(itemView.context)) {
                    Glide.with(itemView.context).load(ApiClient.BASE_URL + message.profilePictureUrl).placeholder(R.drawable.ic_person).into(profileImage)
                }
            } else profileImage.setImageResource(R.drawable.ic_person)
            bindReactions(reactionsLayout, message)

            setupFileState(message = message, initialIcon = fileIcon, finishedIcon = downloadIcon, progressContainer = progressContainer, progressBar = progressBar, progressText = progressText)
            val clickListener = createClickListenerForFileInteraction(message = message, initialIcon = fileIcon, finishedIcon = downloadIcon, progressContainer = progressContainer, progressBar = progressBar, progressText = progressText)
            messageBubble.setOnClickListener(clickListener)
        }
    }

    abstract inner class BaseAudioViewHolder(itemView: View) : BaseFileViewHolder(itemView) {
        protected abstract val playPauseButton: MaterialButton
        protected abstract val seekBar: SeekBar
        protected abstract val durationText: TextView
        protected abstract val transcribeButton: MaterialButton

        protected val transcriptionContainer: android.widget.LinearLayout = itemView.findViewById(R.id.transcription_container)
        protected val transcriptionProgressLayout: android.widget.LinearLayout = itemView.findViewById(R.id.transcription_progress_layout)
        protected val transcriptionProgressBar: com.google.android.material.progressindicator.LinearProgressIndicator = itemView.findViewById(R.id.transcription_progress_bar)
        protected val transcriptionStatusText: TextView = itemView.findViewById(R.id.transcription_status_text)
        protected val transcriptionText: TextView = itemView.findViewById(R.id.transcription_text)

        var messageId: Int = -1
        private var transcriptionListener: ((TranscriptionTracker.TranscriptionState) -> Unit)? = null

        private fun detachTranscriptionListener() {
            if (messageId != -1 && transcriptionListener != null) {
                TranscriptionTracker.unregisterListener(messageId, transcriptionListener!!)
                transcriptionListener = null
            }
        }

        override fun cleanup() {
            super.cleanup()
            detachTranscriptionListener()
        }

        fun updateProgress(pos: Int, duration: Int, isPlaying: Boolean) {
            if (duration > 0 && seekBar.max != duration) {
                seekBar.max = duration
            }
            if (pos <= seekBar.max) {
                seekBar.progress = pos
            }
            playPauseButton.setIconResource(R.drawable.ic_pause)
            durationText.text = "${formatDuration(pos)} / ${formatDuration(seekBar.max)}"
            seekBar.postInvalidate()
        }

        fun updatePlayState(isPlaying: Boolean) {
            playPauseButton.setIconResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        }

        fun resetProgress() {
            seekBar.progress = 0
            durationText.text = "${formatDuration(0)} / ${formatDuration(seekBar.max)}"
            seekBar.postInvalidate()
        }

        protected fun formatDuration(millis: Int): String {
            return String.format("%02d:%02d", java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(millis.toLong()), java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(millis.toLong()) - java.util.concurrent.TimeUnit.MINUTES.toSeconds(java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(millis.toLong())))
        }

        fun bindAudio(message: Message) {
            detachTranscriptionListener()
            messageId = message.id
            audioViewHolders[message.id] = this

            val resolvedMessage = resolveMessageWithMetadata(itemView.context, message)
            val localFile = FileUtils.getLocalFile(itemView.context, resolvedMessage)

            val isPlayingNow = (globalPlayingMessageId == message.id && globalIsPlaying)
            playPauseButton.setIconResource(if (isPlayingNow) R.drawable.ic_pause else R.drawable.ic_play_arrow)

            val displayDuration = resolvedMessage.audioDuration
            if (displayDuration != null) {
                seekBar.max = displayDuration
                durationText.text = formatDuration(displayDuration)
            } else {
                durationText.text = "00:00"
            }

            if (!isPlayingNow) {
                seekBar.progress = 0
            }

            val fileId = message.fileId
            val isSentByMe = message.userId == currentUserId
            val shouldAutoDownload = isSentByMe || isAutoDownloadAllowed(itemView.context)

            if (fileId != null && fileId != -1 && (!localFile.exists() || localFile.length() == 0L) && !message.isViewOnce && message.tempId == null && shouldAutoDownload) {
                if (!DownloadTracker.isDownloading(fileId)) {
                    playPauseButton.setIconResource(R.drawable.ic_file_download)
                    playPauseButton.isEnabled = false

                    startFileDownload(
                        context = itemView.context,
                        message = message,
                        groupKey = groupKey,
                        groupIv = groupIv,
                        currentUserId = currentUserId,
                        onFinish = {
                            playPauseButton.isEnabled = true
                            playPauseButton.setIconResource(R.drawable.ic_play_arrow)
                        },
                        onError = {
                            playPauseButton.isEnabled = true
                            playPauseButton.setIconResource(R.drawable.ic_play_arrow)
                        }
                    )
                }
            }

            playPauseButton.setOnClickListener {
                if (localFile.exists() && localFile.length() > 0L) {
                    val action = if (globalPlayingMessageId == message.id && globalIsPlaying) {
                        AudioPlayerService.ACTION_PAUSE
                    } else {
                        AudioPlayerService.ACTION_PLAY
                    }

                    val intent = Intent(itemView.context, AudioPlayerService::class.java).apply {
                        this.action = action
                        putExtra(AudioPlayerService.EXTRA_FILE_PATH, localFile.absolutePath)
                        putExtra(AudioPlayerService.EXTRA_MESSAGE_ID, message.id)
                        putExtra(AudioPlayerService.EXTRA_USERNAME, message.username)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        itemView.context.startForegroundService(intent)
                    } else {
                        itemView.context.startService(intent)
                    }
                } else {
                    if (localFile.exists()) {
                        localFile.delete()
                    }
                    downloadAndPlay(message)
                }
            }

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val intent = Intent(itemView.context, AudioPlayerService::class.java).apply {
                            action = AudioPlayerService.ACTION_SEEK
                            putExtra(AudioPlayerService.EXTRA_SEEK_POSITION, progress)
                        }
                        itemView.context.startService(intent)
                    }
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {}
            })

            // --- TRASCRIZIONE DENTRO LA BOLLA ---
            val uiTranscriptionListener: (TranscriptionTracker.TranscriptionState) -> Unit = { state ->
                if (messageId == message.id) {
                    if (state.error != null || (!state.isCompleted && state.status.isEmpty())) {
                        transcriptionContainer.visibility = View.GONE
                    } else if (state.isCompleted) {
                        transcriptionContainer.visibility = View.VISIBLE
                        transcriptionProgressLayout.visibility = View.GONE
                        transcriptionText.visibility = View.VISIBLE
                        transcriptionText.text = state.text ?: ""
                    } else {
                        transcriptionContainer.visibility = View.VISIBLE
                        transcriptionProgressLayout.visibility = View.VISIBLE
                        transcriptionText.visibility = View.GONE
                        transcriptionStatusText.text = state.status
                        if (state.progress in 0..100) {
                            transcriptionProgressBar.isIndeterminate = false
                            transcriptionProgressBar.progress = state.progress
                        } else {
                            transcriptionProgressBar.isIndeterminate = true
                        }
                    }
                }
            }

            transcriptionListener = uiTranscriptionListener
            TranscriptionTracker.registerListener(message.id, uiTranscriptionListener)

            transcribeButton.setOnClickListener {
                val currentState = TranscriptionTracker.getState(message.id)
                if (currentState?.isCompleted == true) {
                    TranscriptionTracker.clear(message.id)
                } else {
                    this@MessageAdapter.listener.onTranscribeAudioClicked(message)
                }
            }
        }

        private fun downloadAndPlay(message: Message) {
            val fileId = message.fileId ?: return
            if (DownloadTracker.isDownloading(fileId)) {
                Toast.makeText(itemView.context, "Download già in corso.", Toast.LENGTH_SHORT).show()
                return
            }

            playPauseButton.setIconResource(R.drawable.ic_file_download)
            playPauseButton.isEnabled = false

            startFileDownload(
                context = itemView.context,
                message = message,
                groupKey = groupKey,
                groupIv = groupIv,
                currentUserId = currentUserId,
                onFinish = { downloadedFile ->
                    playPauseButton.isEnabled = true
                    val intent = Intent(itemView.context, AudioPlayerService::class.java).apply {
                        action = AudioPlayerService.ACTION_PLAY
                        putExtra(AudioPlayerService.EXTRA_FILE_PATH, downloadedFile.absolutePath)
                        putExtra(AudioPlayerService.EXTRA_MESSAGE_ID, message.id)
                        putExtra(AudioPlayerService.EXTRA_USERNAME, message.username)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        itemView.context.startForegroundService(intent)
                    } else {
                        itemView.context.startService(intent)
                    }
                },
                onError = { e ->
                    playPauseButton.isEnabled = true
                    playPauseButton.setIconResource(R.drawable.ic_play_arrow)
                    Toast.makeText(itemView.context, "Errore: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    inner class SentAudioViewHolder(itemView: View) : BaseAudioViewHolder(itemView) {
        override val playPauseButton: MaterialButton = itemView.findViewById(R.id.play_pause_button)
        override val seekBar: SeekBar = itemView.findViewById(R.id.seek_bar)
        override val durationText: TextView = itemView.findViewById(R.id.duration_text)
        override val transcribeButton: MaterialButton = itemView.findViewById(R.id.btn_transcribe)
        private val timestampText: TextView = itemView.findViewById(R.id.textViewTimestamp)
        private val statusImage: ImageView = itemView.findViewById(R.id.imageViewStatus)
        private val reactionsLayout: FlexboxLayout = itemView.findViewById(R.id.reactions_layout)
        private val replyContextLayout: View = itemView.findViewById(R.id.replyContextLayout)
        private val replyAuthorText: TextView = itemView.findViewById(R.id.textViewReplyAuthor)
        private val replyContentText: TextView = itemView.findViewById(R.id.textViewReplyContent)

        override fun bind(message: Message) {
            super.bind(message); timestampText.text = formatTimestamp(message.timestamp); bindReactions(reactionsLayout, message)
            val context = itemView.context; val readColor = ContextCompat.getColor(context, R.color.read_receipt_yellow); val typedValue = TypedValue().also { context.theme.resolveAttribute(com.google.android.material.R.attr.colorOutline, it, true) }; val grayColor = typedValue.data
            when (message.status) { -1 -> { statusImage.setImageResource(R.drawable.ic_clock); statusImage.imageTintList = ColorStateList.valueOf(grayColor) }; 0 -> { statusImage.setImageResource(R.drawable.ic_check); statusImage.imageTintList = ColorStateList.valueOf(grayColor) }; 1 -> { statusImage.setImageResource(R.drawable.ic_check_all); statusImage.imageTintList = ColorStateList.valueOf(grayColor) }; 2 -> { statusImage.setImageResource(R.drawable.ic_check_all); statusImage.imageTintList = ColorStateList.valueOf(readColor) } }
            if (message.status > 0) statusImage.setOnClickListener { this@MessageAdapter.listener.onStatusIconClicked(message) } else statusImage.setOnClickListener(null)
            if (message.replyToMessageId != null) { replyContextLayout.visibility = View.VISIBLE; replyAuthorText.text = message.replyToUsername; replyContentText.text = getDecryptedReplyContent(message, itemView.context); replyContextLayout.setOnClickListener { this@MessageAdapter.listener.onReplyClicked(message.replyToMessageId) } } else replyContextLayout.visibility = View.GONE

            bindAudio(message)

            if (message.tempId != null) {
                playPauseButton.isEnabled = false
                playPauseButton.alpha = 0.5f
                transcribeButton.isEnabled = false
                transcribeButton.alpha = 0.5f
            } else {
                playPauseButton.isEnabled = true
                playPauseButton.alpha = 1.0f
                transcribeButton.isEnabled = true
                transcribeButton.alpha = 1.0f
            }
        }
    }

    inner class ReceivedAudioViewHolder(itemView: View) : BaseAudioViewHolder(itemView) {
        override val playPauseButton: MaterialButton = itemView.findViewById(R.id.play_pause_button)
        override val seekBar: SeekBar = itemView.findViewById(R.id.seek_bar)
        override val durationText: TextView = itemView.findViewById(R.id.duration_text)
        override val transcribeButton: MaterialButton = itemView.findViewById(R.id.btn_transcribe)
        private val timestampText: TextView = itemView.findViewById(R.id.textViewTimestamp)
        private val usernameText: TextView = itemView.findViewById(R.id.textViewUsername)
        private val profileImage: ShapeableImageView = itemView.findViewById(R.id.profile_image)
        private val reactionsLayout: FlexboxLayout = itemView.findViewById(R.id.reactions_layout)
        private val replyContextLayout: View = itemView.findViewById(R.id.replyContextLayout)
        private val replyAuthorText: TextView = itemView.findViewById(R.id.textViewReplyAuthor)
        private val replyContentText: TextView = itemView.findViewById(R.id.textViewReplyContent)

        override fun bind(message: Message) {
            super.bind(message); timestampText.text = formatTimestamp(message.timestamp); usernameText.text = message.username
            if (!message.profilePictureUrl.isNullOrBlank()) {
                if (isValidContextForGlide(itemView.context)) {
                    Glide.with(itemView.context).load(ApiClient.BASE_URL + message.profilePictureUrl).placeholder(R.drawable.ic_person).into(profileImage)
                }
            } else profileImage.setImageResource(R.drawable.ic_person)
            bindReactions(reactionsLayout, message)
            if (message.replyToMessageId != null) { replyContextLayout.visibility = View.VISIBLE; replyAuthorText.text = if (message.replyToUsername == currentUsername) "Tu" else message.replyToUsername; replyContentText.text = getDecryptedReplyContent(message, itemView.context); replyContextLayout.setOnClickListener { this@MessageAdapter.listener.onReplyClicked(message.replyToMessageId) } } else replyContextLayout.visibility = View.GONE
            bindAudio(message)
        }
    }

    inner class SentViewOnceViewHolder(itemView: View) : BaseMessageViewHolder(itemView) {
        private val bubble: View = itemView.findViewById(R.id.view_once_bubble); private val label: TextView = itemView.findViewById(R.id.view_once_label); private val timestampText: TextView = itemView.findViewById(R.id.textViewTimestamp); private val statusImage: ImageView = itemView.findViewById(R.id.imageViewStatus)
        override fun bind(message: Message) {
            super.bind(message)
            val resolvedMessage = resolveMessageWithMetadata(itemView.context, message)
            label.text = when(resolvedMessage.messageType) { "image" -> "Foto • Inviata"; "file" -> "File • Inviato"; "audio" -> "Messaggio vocale • Inviato"; else -> "Messaggio • Inviato" }; timestampText.text = formatTimestamp(message.timestamp)
            val context = itemView.context; val readColor = ContextCompat.getColor(context, R.color.read_receipt_yellow); val typedValue = TypedValue().also { context.theme.resolveAttribute(com.google.android.material.R.attr.colorOutline, it, true) }; val grayColor = typedValue.data
            when (message.status) { -1 -> { statusImage.setImageResource(R.drawable.ic_clock); statusImage.imageTintList = ColorStateList.valueOf(grayColor) }; 0 -> { statusImage.setImageResource(R.drawable.ic_check); statusImage.imageTintList = ColorStateList.valueOf(grayColor) }; 1 -> { statusImage.setImageResource(R.drawable.ic_check_all); statusImage.imageTintList = ColorStateList.valueOf(grayColor) }; 2 -> { statusImage.setImageResource(R.drawable.ic_check_all); statusImage.imageTintList = ColorStateList.valueOf(readColor) } }
            if (message.status > 0) statusImage.setOnClickListener { this@MessageAdapter.listener.onStatusIconClicked(message) } else statusImage.setOnClickListener(null)
        }
    }

    inner class ReceivedViewOnceViewHolder(itemView: View) : BaseMessageViewHolder(itemView) {
        private val bubble: View = itemView.findViewById(R.id.view_once_bubble); private val label: TextView = itemView.findViewById(R.id.view_once_label); private val icon: ImageView = itemView.findViewById(R.id.view_once_icon); private val usernameText: TextView = itemView.findViewById(R.id.textViewUsername); private val timestampText: TextView = itemView.findViewById(R.id.textViewTimestamp); private val profileImage: ShapeableImageView = itemView.findViewById(R.id.profile_image)
        override fun bind(message: Message) {
            super.bind(message); usernameText.text = message.username; timestampText.text = formatTimestamp(message.timestamp)
            if (!message.profilePictureUrl.isNullOrBlank()) {
                if (isValidContextForGlide(itemView.context)) {
                    Glide.with(itemView.context).load(ApiClient.BASE_URL + message.profilePictureUrl).placeholder(R.drawable.ic_person).into(profileImage)
                }
            } else profileImage.setImageResource(R.drawable.ic_person)
            val resolvedMessage = resolveMessageWithMetadata(itemView.context, message)
            val defaultColor = ContextCompat.getColor(itemView.context, R.color.on_secondary_container_light).let { if (it == 0) android.graphics.Color.BLACK else it }; val disabledColor = ContextCompat.getColor(itemView.context, android.R.color.darker_gray)
            if (message.isViewedByMe) { label.text = "Aperto"; label.setTextColor(disabledColor); icon.imageTintList = ColorStateList.valueOf(disabledColor); bubble.setOnClickListener { Toast.makeText(itemView.context, "Già visualizzato.", Toast.LENGTH_SHORT).show() } } else { label.setTextColor(defaultColor); icon.imageTintList = ColorStateList.valueOf(defaultColor); label.text = when(resolvedMessage.messageType) { "image" -> "Foto • Tocca per visualizzare"; "file" -> "File • Tocca per visualizzare"; "audio" -> "Messaggio vocale • Tocca per ascoltare"; else -> "Messaggio • Tocca per visualizzare" }; bubble.setOnClickListener { this@MessageAdapter.listener.onViewOnceMessageClicked(message) } }
        }
    }

    private class MessageDiffCallback(private val oldList: List<ListItem>, private val newList: List<ListItem>) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            val oldItem = oldList[oldPos]; val newItem = newList[newPos]
            return when {
                oldItem is ListItem.MessageItem && newItem is ListItem.MessageItem -> { if (oldItem.message.tempId != null || newItem.message.tempId != null) oldItem.message.tempId == newItem.message.tempId else oldItem.message.id == newItem.message.id }
                oldItem is ListItem.DateItem && newItem is ListItem.DateItem -> oldItem.date == newItem.date
                else -> false
            }
        }
        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean { val oldItem = oldList[oldPos]; val newItem = newList[newPos]; return oldItem == newItem }
    }

    private fun getGradientDrawable(drawable: android.graphics.drawable.Drawable?): android.graphics.drawable.GradientDrawable? {
        var current = drawable?.mutate()
        while (current != null) {
            if (current is android.graphics.drawable.GradientDrawable) {
                return current
            }
            if (current is android.graphics.drawable.RippleDrawable) {
                if (current.numberOfLayers > 0) {
                    current = current.getDrawable(0)?.mutate()
                    continue
                }
            }
            if (current is android.graphics.drawable.LayerDrawable) {
                if (current.numberOfLayers > 0) {
                    current = current.getDrawable(0)?.mutate()
                    continue
                }
            }
            break
        }
        return null
    }

    private fun resolveAttributeColor(context: Context, attrName: String): Int {
        val typedValue = android.util.TypedValue()
        val attrId = context.resources.getIdentifier(attrName, "attr", context.packageName)
        if (attrId != 0 && context.theme.resolveAttribute(attrId, typedValue, true)) {
            return typedValue.data
        }

        val fallbackAttrId = context.resources.getIdentifier(attrName, "attr", "androidx.appcompat")
        if (fallbackAttrId != 0 && context.theme.resolveAttribute(fallbackAttrId, typedValue, true)) {
            return typedValue.data
        }

        return when (attrName) {
            "colorPrimary" -> ContextCompat.getColor(context, R.color.primary_light)
            "colorSecondaryContainer" -> ContextCompat.getColor(context, R.color.secondary_container_light)
            else -> android.graphics.Color.GRAY
        }
    }

    private fun applyGlassmorphism(view: View, isSent: Boolean) {
        val prefs = SecurePrefs.get(view.context)
        val transparency = prefs.getInt("chat_card_transparency", 15)
        val reflection = prefs.getInt("chat_card_reflection", 100)
        val strokeWidth = prefs.getInt("chat_card_stroke_width", 0)

        val alpha = ((100 - transparency) * 255 / 100).coerceIn(0, 255)
        val reflectionAlpha = (reflection * 255 / 100).coerceIn(0, 255)

        val context = view.context

        val bgColorAttrName = if (isSent) "colorPrimary" else "colorSecondaryContainer"
        val baseColor = resolveAttributeColor(context, bgColorAttrName)
        val bgColor = androidx.core.graphics.ColorUtils.setAlphaComponent(baseColor, alpha)

        val baseOutlineColor = resolveAttributeColor(context, "colorOutlineVariant")
        val strokeColor = androidx.core.graphics.ColorUtils.setAlphaComponent(baseOutlineColor, reflectionAlpha)

        val strokeWidthPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            strokeWidth.toFloat(),
            context.resources.displayMetrics
        ).toInt()

        val cardView = view as? com.google.android.material.card.MaterialCardView
        if (cardView != null) {
            cardView.setCardBackgroundColor(bgColor)
            cardView.strokeColor = strokeColor
            cardView.strokeWidth = strokeWidthPx

            if (transparency > 0) {
                cardView.cardElevation = 0f
            } else {
                cardView.cardElevation = android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP,
                    1.5f,
                    context.resources.displayMetrics
                )
            }
        } else {
            val gd = getGradientDrawable(view.background)
            if (gd != null) {
                gd.setColor(bgColor)
                gd.setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    private fun applyGlassmorphismToContainer(viewHolder: RecyclerView.ViewHolder, isSent: Boolean) {
        val view = when (viewHolder) {
            is SentMessageViewHolder -> viewHolder.itemView.findViewById<TextView>(R.id.textViewMessage)
            is ReceivedMessageViewHolder -> viewHolder.itemView.findViewById<TextView>(R.id.textViewMessage)
            is SentFileViewHolder -> viewHolder.itemView.findViewById<View>(R.id.message_bubble_container)
            is ReceivedFileViewHolder -> viewHolder.itemView.findViewById<View>(R.id.message_bubble_container)
            is SentViewOnceViewHolder -> viewHolder.itemView.findViewById<View>(R.id.view_once_bubble)
            is ReceivedViewOnceViewHolder -> viewHolder.itemView.findViewById<View>(R.id.view_once_bubble)
            is BaseImageViewHolder, is BaseAudioViewHolder -> {
                var card: com.google.android.material.card.MaterialCardView? = null
                fun findCard(v: View) {
                    if (v is com.google.android.material.card.MaterialCardView && v.id != R.id.replyContextLayout) {
                        card = v
                        return
                    }
                    if (v is ViewGroup) {
                        for (i in 0 until v.childCount) {
                            findCard(v.getChildAt(i))
                            if (card != null) return
                        }
                    }
                }
                findCard(viewHolder.itemView)
                card
            }
            else -> null
        }
        view?.let { applyGlassmorphism(it, isSent) }
    }
}