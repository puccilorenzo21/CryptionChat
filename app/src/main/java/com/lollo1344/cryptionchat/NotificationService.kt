package com.lollo1344.cryptionchat

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.IOException
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class NotificationService : Service() {

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine del servizio ha catturato un'eccezione non gestita.", throwable)
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob + coroutineExceptionHandler)

    private var forcedMediaPlayer: MediaPlayer? = null
    private var forcedVibrator: Vibrator? = null

    private val audioStopHandler = Handler(Looper.getMainLooper())
    private val audioStopRunnable = Runnable { stopForcedSound() }

    private var userId: Int = -1
    private var wakeLock: PowerManager.WakeLock? = null
    private var soundWakeLock: PowerManager.WakeLock? = null

    private var currentRingingCallId: Int = -1
    private var lastDistSyncTimeMs: Long = 0L

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP_SOUND -> stopForcedSound()
                ACTION_SET_ONGOING_CALL -> {
                    val show = intent.getBooleanExtra("SHOW", false)
                    if (show) {
                        val username = intent.getStringExtra("USERNAME") ?: "Sconosciuto"
                        updateForegroundState(isCallActive = true, callUsername = username)
                    } else {
                        updateForegroundState(isCallActive = false)
                    }
                }
            }
        }
    }

    companion object {
        const val NEW_MESSAGE_CHANNEL_ID = "new_message_channel"
        const val MENTION_CHANNEL_ID = "mention_channel_forced_v4"
        const val CALL_CHANNEL_ID = "incoming_call_channel"
        const val SERVICE_CHANNEL_ID = "service_status_channel"
        const val ONGOING_CALL_CHANNEL_ID = "ongoing_call_channel"

        private const val TAG = "NotificationService"
        const val LAST_TIMESTAMP_KEY = "last_notification_timestamp"
        const val LAST_MESSAGE_ID_KEY = "last_notification_message_id"
        private const val DEFAULT_TIMESTAMP = "1970-01-01 00:00:00"

        const val ACTION_NEW_MESSAGE_RECEIVED = "com.lollo1344.cryptionchat.NEW_MESSAGE_RECEIVED"
        const val EXTRA_NEW_MESSAGE_JSON = "extra_new_message_json"

        const val ACTION_STOP_SOUND = "com.lollo1344.cryptionchat.STOP_SOUND"
        const val ACTION_SET_ONGOING_CALL = "com.lollo1344.cryptionchat.SET_ONGOING_CALL"
        const val ACTION_WEBRTC_SIGNALS = "com.lollo1344.cryptionchat.WEBRTC_SIGNALS"
        const val ACTION_CALL_ENDED = "com.lollo1344.cryptionchat.CALL_ENDED"

        const val CALL_NOTIFICATION_ID = 12345
        const val MENTION_ALARM_NOTIFICATION_ID = 55555

        const val FOREGROUND_NOTIFICATION_ID = 1

        @Volatile
        var activeCallIdForPolling: Int = 0
    }

    override fun onCreate() {
        super.onCreate()
        IdentityKeyManager.init(applicationContext)
        X3dhHelper.init(applicationContext)
        SenderKeyHelper.init(applicationContext)
        createNotificationChannels()

        // Inizializza nello stato di "attesa" senza occupare il microfono
        updateForegroundState(isCallActive = false)

        val filter = IntentFilter().apply {
            addAction(ACTION_STOP_SOUND)
            addAction(ACTION_SET_ONGOING_CALL)
        }

        // Utilizza ContextCompat per registrare il receiver in sicurezza su tutte le versioni Android
        ContextCompat.registerReceiver(
            this,
            actionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CryptionChat:NotificationService:NetworkWakeLock")
        wakeLock?.setReferenceCounted(false)

        soundWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CryptionChat:SoundWakeLock")
        soundWakeLock?.setReferenceCounted(false)
    }

    @Synchronized
    private fun updateForegroundState(isCallActive: Boolean, callUsername: String? = null) {
        val notification: Notification
        val serviceType: Int

        if (isCallActive) {
            val intent = Intent(this, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            notification = NotificationCompat.Builder(this, ONGOING_CALL_CHANNEL_ID)
                .setContentTitle("Chiamata in corso")
                .setContentText(callUsername ?: "Sconosciuto")
                .setSmallIcon(R.drawable.ic_call)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setUsesChronometer(true)
                .build()

            serviceType = if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else 0
        } else {
            val notificationIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

            notification = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
                .setContentTitle("CryptionChat attiva")
                .setContentText("Connesso in background")
                .setSmallIcon(R.drawable.ic_notification_chat)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

            serviceType = if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else 0
        }

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore updateForegroundState: ${e.message}")
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(FOREGROUND_NOTIFICATION_ID, notification)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Errore fallback: ${e2.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!CallManager.isActive) {
            updateForegroundState(isCallActive = false)
        }

        userId = intent?.getIntExtra("USER_ID", -1) ?: SecurePrefs.get(this).getInt("user_id", -1)

        if (userId == -1) {
            Log.w(TAG, "Nessun utente loggato, arresto servizio.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (serviceScope.coroutineContext.job.children.count() == 0) {
            startLongPollingLoop()
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed (app chiusa dalle recenti), riavvio servizio tra 1 secondo.")

        val prefs = SecurePrefs.get(this)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("session_postponed_") }.forEach {
            editor.remove(it)
        }
        editor.commit()

        val restartServiceIntent = Intent(applicationContext, NotificationService::class.java).apply {
            setPackage(packageName)
            putExtra("USER_ID", userId)
        }

        val restartServicePendingIntent = PendingIntent.getService(
            applicationContext, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            alarmService.set(
                AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Impossibile impostare allarme di riavvio: permessi mancanti.")
        }
    }

    private fun startLongPollingLoop() {
        serviceScope.launch {
            Log.i(TAG, "Polling loop avviato per utente $userId")
            while (isActive) {
                try {
                    wakeLock?.acquire(65000L)
                    performUnifiedPoll()
                } catch (e: Exception) {
                    Log.e(TAG, "Errore nel polling loop", e)
                } finally {
                    try {
                        if (wakeLock?.isHeld == true) {
                            wakeLock?.release()
                        }
                    } catch (e: Exception) { }
                }

                if (activeCallIdForPolling > 0) {
                    delay(1200)
                } else {
                    delay(2500)
                }
            }
        }
    }

    private fun resolvePeerIdForPrivateChat(groupId: Int): Int {
        val prefs = SecurePrefs.get(this)
        val cached = prefs.getInt("private_peer_uid_$groupId", -1)
        if (cached > 0) return cached
        return try {
            val resp = ApiClient.instance.getGroupMembers(groupId = groupId).execute()
            val peerId = if (resp.isSuccessful) {
                resp.body()?.data?.firstOrNull { it.userId != userId }?.userId ?: -1
            } else -1
            if (peerId > 0) {
                prefs.edit().putInt("private_peer_uid_$groupId", peerId).commit()
            }
            peerId
        } catch (_: Exception) {
            -1
        }
    }

    private fun decryptNotificationPayload(msg: NewMessageNotification): String? {
        val raw = msg.encryptedMessage
        if (raw.startsWith(SenderKeyHelper.SK_PREFIX)) {
            SenderKeyHelper.decryptGroupMessage(raw)?.let { return it }
            E2eeSessionOrchestrator.syncDistributions(applicationContext, userId, msg.groupId)
            return SenderKeyHelper.decryptGroupMessage(raw)
        }
        if (raw.startsWith(DoubleRatchetHelper.DR_PREFIX)) {
            val peerId = if (msg.isPrivateChat) resolvePeerIdForPrivateChat(msg.groupId) else -1
            if (peerId > 0) {
                E2eeSessionOrchestrator.decryptPairwiseMessage(applicationContext, userId, peerId, raw)?.let { return it }
            }
            val zkKey = SecurePrefs.get(this).getString("zk_key_${msg.groupId}", null)
            if (!zkKey.isNullOrBlank()) {
                DoubleRatchetHelper.decryptMessage(zkKey, raw)?.let { return it }
            }
            return null
        }

        val prefs = SecurePrefs.get(this)
        val zkKey = prefs.getString("zk_key_${msg.groupId}", null)
        val zkIv = prefs.getString("zk_iv_${msg.groupId}", null)
        if (!zkKey.isNullOrBlank() && !zkIv.isNullOrBlank()) {
            EncryptionHelper.decrypt(raw, zkKey, zkIv)?.let { return it }
        }
        val stdKey = prefs.getString("key_${msg.groupId}", null)
        val stdIv = prefs.getString("iv_${msg.groupId}", null)
        if (!stdKey.isNullOrBlank() && !stdIv.isNullOrBlank()) {
            return EncryptionHelper.decrypt(raw, stdKey, stdIv)
        }
        return null
    }

    private suspend fun performUnifiedPoll() {
        val sharedPreferences = SecurePrefs.get(this)
        val lastCheckTimestamp = sharedPreferences.getString(LAST_TIMESTAMP_KEY, DEFAULT_TIMESTAMP) ?: DEFAULT_TIMESTAMP
        val lastMsgId = sharedPreferences.getInt(LAST_MESSAGE_ID_KEY, 0)
        val callId = activeCallIdForPolling
        val deviceId = DeviceUtils.getDeviceId(this)

        try {
            val response = withContext(Dispatchers.IO) {
                ApiClient.instance.unifiedPoll(
                    userId = userId,
                    lastTimestamp = lastCheckTimestamp,
                    activeCallId = if (callId > 0) callId else null,
                    deviceId = deviceId,
                    lastMessageId = lastMsgId
                ).execute()
            }

            if (response.body()?.status == "banned") {
                withContext(Dispatchers.Main) {
                    BanHelper.executeBan(this@NotificationService)
                }
                stopSelf()
                return
            }

            if (response.isSuccessful && response.body()?.status == "success") {
                val data = response.body()?.data
                val nowMs = System.currentTimeMillis()
                val hasPendingDistHint = data?.hasKeyDistributions == true
                val hasMessages = !data?.messages.isNullOrEmpty()
                if (hasPendingDistHint || hasMessages || (nowMs - lastDistSyncTimeMs) >= 10_000L) {
                    E2eeSessionOrchestrator.syncDistributions(applicationContext, userId, 0)
                    lastDistSyncTimeMs = nowMs
                }

                if (data == null) return

                data.incomingCall?.let { call ->
                    if (call.callId != currentRingingCallId && activeCallIdForPolling == 0) {
                        Log.i(TAG, "Nuova chiamata in arrivo: ${call.callId}")
                        currentRingingCallId = call.callId
                        handleIncomingCall(call)
                    }
                }

                if (!data.messages.isNullOrEmpty()) {
                    val prefs = SecurePrefs.get(this@NotificationService)
                    val remoteKillCode = prefs.getString("remote_kill_code", null)
                    var killSwitchTriggered = false

                    data.messages.forEach { msg ->
                        if (killSwitchTriggered) return@forEach

                        val decrypted = if (!msg.isViewOnce && (msg.messageType == "text" || msg.encryptedMessage.startsWith(SenderKeyHelper.SK_PREFIX) || msg.encryptedMessage.startsWith(DoubleRatchetHelper.DR_PREFIX))) {
                            decryptNotificationPayload(msg)
                        } else null

                        if (!remoteKillCode.isNullOrBlank() && msg.messageType == "text" && !msg.isViewOnce) {
                            val suffix = PanicHelper.parseRemoteKillSuffix(
                                decryptedText = decrypted,
                                remoteKillCode = remoteKillCode
                            )
                            if (suffix != null) {
                                killSwitchTriggered = true

                                Thread {
                                    try {
                                        if (prefs.getBoolean("send_read_receipts", true)) {
                                            ApiClient.instance.markSpecificMessagesAsRead(userId = userId, messageIdsJson = "[${msg.id}]").execute()
                                        }
                                        ApiClient.instance.deleteZkMessage(userId = userId, messageId = msg.id).execute()
                                    } catch (_: Exception) {}

                                    Thread.sleep(1500)

                                    if (suffix.isEmpty()) {
                                        Handler(Looper.getMainLooper()).post {
                                            PanicHelper.executePanicProtocol(this@NotificationService)
                                        }
                                    } else {
                                        ShizukuHelper.executePanicCommand(suffix, packageName)
                                    }
                                }.start()

                                return@forEach
                            }
                        }

                        if (msg.groupId == ChatStatusHolder.currentlyVisibleGroupId) {
                            broadcastNewMessageToActivity(msg)
                            if (msg.isMention) {
                                playForcedSound(isCall = false)
                            }
                        } else {
                            showNotification(msg)
                        }
                    }

                    if (killSwitchTriggered) return

                    val newestTimestamp = data.messages.maxByOrNull { it.timestamp }?.timestamp
                    val highestMsgId = data.messages.maxOfOrNull { it.id } ?: lastMsgId
                    val editor = prefs.edit()
                    if (newestTimestamp != null) {
                        editor.putString(LAST_TIMESTAMP_KEY, newestTimestamp)
                    }
                    if (highestMsgId > lastMsgId) {
                        editor.putInt(LAST_MESSAGE_ID_KEY, highestMsgId)
                    }
                    editor.apply()
                }

                if (!data.signals.isNullOrEmpty()) {
                    if (CallManager.isActive) {
                        CallManager.processSignalJson(Gson().toJson(data.signals))
                    } else {
                        SignalBuffer.addAll(data.signals)
                        val intent = Intent(ACTION_WEBRTC_SIGNALS)
                        intent.putExtra("SIGNALS_JSON", Gson().toJson(data.signals))
                        LocalBroadcastManager.getInstance(this@NotificationService).sendBroadcast(intent)
                    }
                }

                if (data.callEnded == true) {
                    if (CallManager.isActive) {
                        CallManager.endCall()
                    } else {
                        LocalBroadcastManager.getInstance(this@NotificationService).sendBroadcast(Intent(ACTION_CALL_ENDED))
                    }
                    stopForcedSound()
                    activeCallIdForPolling = 0
                    updateForegroundState(isCallActive = false)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Timeout di rete o nessuna connessione durante il polling.")
        }
    }

    private fun handleIncomingCall(call: IncomingCallDetails) {
        val useProxy = SecurePrefs.get(this).getBoolean("use_proxy", false)
        if (useProxy) {
            Log.w(TAG, "Chiamata ${call.callId} ignorata: Proxy/Tor attivo.")
            return
        }

        val fullScreenIntent = Intent(this, CallActivity::class.java).apply {
            putExtra("CALL_ID", call.callId)
            putExtra("GROUP_ID", call.groupId)
            putExtra("OTHER_USER_ID", call.callerId)
            putExtra("OTHER_USERNAME", call.callerUsername)
            putExtra("PROFILE_PIC", call.profilePictureUrl)
            putExtra("IS_INCOMING", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, call.callId, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deleteIntent = Intent(ACTION_STOP_SOUND).setPackage(packageName)
        val deletePendingIntent = PendingIntent.getBroadcast(
            this, call.callId + 1000, deleteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Chiamata in arrivo")
            .setContentText(call.callerUsername)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setDeleteIntent(deletePendingIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .setDefaults(0)

        playForcedSound(isCall = true)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(CALL_NOTIFICATION_ID, notificationBuilder.build())

        try {
            startActivity(fullScreenIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Impossibile forzare apertura Activity: ${e.message}")
        }
    }

    private fun broadcastNewMessageToActivity(msg: NewMessageNotification) {
        val intent = Intent(ACTION_NEW_MESSAGE_RECEIVED).apply {
            putExtra(EXTRA_NEW_MESSAGE_JSON, Gson().toJson(msg))
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun playForcedSound(isCall: Boolean = false) {
        if (forcedMediaPlayer?.isPlaying == true) return

        stopForcedSound(resetId = false)

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val prefs = SecurePrefs.get(this)

            if (!isCall) {
                val respectDnd = prefs.getBoolean("mention_respect_dnd", false)
                if (respectDnd) {
                    val ringerMode = audioManager.ringerMode
                    if (ringerMode == AudioManager.RINGER_MODE_SILENT || ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                        Log.i(TAG, "Menzione ricevuta ma ignorata: 'Rispetta Silenzioso/DND' è attivo ed il telefono è in silenzioso.")
                        return
                    }
                } else {
                    val maxAlarmVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    val currentAlarmVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                    if (currentAlarmVol < (maxAlarmVol * 0.7).toInt()) {
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, (maxAlarmVol * 0.85).toInt(), 0)
                    }
                }
            }

            var alert = RingtoneManager.getDefaultUri(if (isCall) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION)
            if (alert == null) {
                alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            forcedMediaPlayer = MediaPlayer().apply {
                setDataSource(this@NotificationService, alert)
                setAudioAttributes(audioAttributes)
                setVolume(1.0f, 1.0f)
                isLooping = true
                prepare()
                start()
            }

            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            forcedVibrator = vibrator

            val pattern = if (isCall) longArrayOf(0, 1000, 1000) else longArrayOf(0, 400, 200)

            if (vibrator.hasVibrator()) {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), audioAttributes)
            }

            if (!isCall) {
                showMentionAlarmNotification()
            }

            val durationMs: Long
            if (isCall) {
                durationMs = 60000L
            } else {
                val durationSec = prefs.getInt("mention_sound_duration", 10)
                durationMs = durationSec * 1000L
            }

            soundWakeLock?.acquire(durationMs + 1000L)

            audioStopHandler.removeCallbacks(audioStopRunnable)
            audioStopHandler.postDelayed(audioStopRunnable, durationMs)

            Log.i(TAG, "🔊 SUONO FORZATO MENZIONE AVVIATO (Durata: ${durationMs / 1000}s)")

        } catch (e: Exception) { Log.e(TAG, "Errore avvio audio/vibrazione menzione", e) }
    }

    private fun showMentionAlarmNotification() {
        val stopIntent = Intent(ACTION_STOP_SOUND).setPackage(packageName)
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 999, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, MENTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_chat)
            .setContentTitle("Menzione Rilevata")
            .setContentText("Tocca per fermare il suono")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(stopPendingIntent)
            .addAction(R.drawable.ic_stop, "Ferma Suono", stopPendingIntent)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(MENTION_ALARM_NOTIFICATION_ID, notification)
    }

    private fun stopForcedSound(resetId: Boolean = true) {
        if (resetId) currentRingingCallId = -1
        audioStopHandler.removeCallbacks(audioStopRunnable)

        try {
            forcedMediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (e: Exception) { Log.e(TAG, "Errore stop MediaPlayer", e) }
        forcedMediaPlayer = null

        try { forcedVibrator?.cancel() } catch (e: Exception) { Log.e(TAG, "Errore stop Vibrator", e) }
        forcedVibrator = null

        if (soundWakeLock?.isHeld == true) {
            try { soundWakeLock?.release() } catch (e: Exception) {}
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(CALL_NOTIFICATION_ID)
        notificationManager.cancel(MENTION_ALARM_NOTIFICATION_ID)
    }

    private fun showNotification(msg: NewMessageNotification) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val prefs = SecurePrefs.get(this)

        val authIntent = Intent(this, AuthActivity::class.java).apply {
            putExtra("GROUP_ID", msg.groupId)
            val titleName = if (msg.isPrivateChat) msg.senderUsername else msg.groupName
            putExtra("GROUP_NAME", titleName)
            putExtra("USER_ID", userId)
            putExtra("USERNAME", prefs.getString("username", ""))
            putExtra("IS_PRIVATE_CHAT", msg.isPrivateChat)
            putExtra("IS_FROM_NOTIFICATION", true)
            putExtra("STOP_SOUND", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this, msg.groupId, authIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deleteIntent = Intent(ACTION_STOP_SOUND).setPackage(packageName)
        val deletePendingIntent = PendingIntent.getBroadcast(
            this, msg.id, deleteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (msg.isMention) MENTION_CHANNEL_ID else NEW_MESSAGE_CHANNEL_ID
        val contentTitle = if (msg.isPrivateChat) msg.senderUsername else msg.groupName

        val showContent = prefs.getBoolean("show_notification_content", false)
        val textPrefix = if (msg.isPrivateChat) "" else "${msg.senderUsername}: "

        var notificationText = if (msg.isPrivateChat) "Nuovo messaggio ricevuto" else "${msg.senderUsername} ha inviato un messaggio"

        if (showContent) {
            if (msg.isViewOnce) {
                notificationText = "${textPrefix}Messaggio effimero \uD83D\uDCA3"
            } else {
                when (msg.messageType) {
                    "image" -> notificationText = "${textPrefix}\uD83D\uDCF7 Foto"
                    "audio" -> notificationText = "${textPrefix}\uD83C\uDFB5 Messaggio vocale"
                    "file" -> {
                        val decryptedMeta = decryptNotificationPayload(msg)
                        val meta = decryptedMeta?.let {
                            try { Gson().fromJson(it, FileMetadata::class.java) } catch (_: Exception) { null }
                        }
                        notificationText = when (meta?.msgType) {
                            "image" -> "${textPrefix}\uD83D\uDCF7 Foto"
                            "audio" -> "${textPrefix}\uD83C\uDFB5 Messaggio vocale"
                            else -> if (!meta?.name.isNullOrBlank()) "${textPrefix}\uD83D\uDCCE ${meta!!.name}" else "${textPrefix}\uD83D\uDCCE File"
                        }
                    }
                    else -> {
                        val decrypted = decryptNotificationPayload(msg)

                        if (!decrypted.isNullOrBlank()) {
                            if (decrypted.contains("[REQ_LOC]")) {
                                notificationText = "${textPrefix}\uD83D\uDCCD Ti ha richiesto la posizione."
                            } else {
                                notificationText = "${textPrefix}$decrypted"
                            }
                        } else {
                            notificationText = "${textPrefix}Messaggio criptato (Apri per leggere)"
                        }
                    }
                }
            }
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification_chat)
            .setContentTitle(contentTitle)
            .setContentText(notificationText)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setAutoCancel(true)

        if (showContent && msg.messageType == "text" && !msg.isViewOnce) {
            notificationBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
        }

        notificationManager.notify(msg.groupId, notificationBuilder.build())

        if (msg.isMention) {
            playForcedSound(isCall = false)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(NotificationChannel(NEW_MESSAGE_CHANNEL_ID, "Messaggi", NotificationManager.IMPORTANCE_HIGH))

            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val mentionChannel = NotificationChannel(MENTION_CHANNEL_ID, "Menzioni", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(defaultSoundUri, audioAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setBypassDnd(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(mentionChannel)

            manager.createNotificationChannel(NotificationChannel(CALL_CHANNEL_ID, "Chiamate in Arrivo", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                enableVibration(true)
                setSound(null, null)
            })

            manager.createNotificationChannel(NotificationChannel(ONGOING_CALL_CHANNEL_ID, "Chiamata in Corso", NotificationManager.IMPORTANCE_LOW))

            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Stato del Servizio",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        stopForcedSound()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Errore stopForeground in onDestroy: ${e.message}")
        }
        serviceJob.cancel()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {}
        try { unregisterReceiver(actionReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}