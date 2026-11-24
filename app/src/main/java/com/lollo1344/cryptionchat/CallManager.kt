package com.lollo1344.cryptionchat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.ConcurrentLinkedQueue

object CallManager {

    private const val TAG = "CallManager"
    private const val RECONNECTION_TIMEOUT_MS = 15000L // 15 secondi per timeout riconnessione

    var isActive = false
    var isAnswered = false
    var callId: Int = -1
    var myUserId: Int = -1
    var otherUserId: Int = -1
    var groupId: Int = -1
    var groupKey: String? = null
    var groupIv: String? = null
    var otherUserName: String = "User"
    var otherUserProfilePic: String? = null
    var isIncoming: Boolean = false

    var callStartTime: Long = 0
    var currentNetworkQuality: NetworkQualityMonitor.NetworkStatus = NetworkQualityMonitor.NetworkStatus.GOOD

    private var webRtcClient: WebRtcClient? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var networkQualityMonitor: NetworkQualityMonitor? = null
    private var appContext: Context? = null

    private var callWakeLock: PowerManager.WakeLock? = null
    private var callWifiLock: WifiManager.WifiLock? = null

    private var deferredAnswerSdp: SessionDescription? = null
    private var pendingOfferSdp: SessionDescription? = null
    private val pendingOutgoingSignals = ConcurrentLinkedQueue<Pair<String, Any>>()
    private val uiHandler = Handler(Looper.getMainLooper())

    private var poorConnectionCounter = 0

    @Volatile
    private var userAcceptedCall = false

    // Variabili di stato interne per garantire l'allineamento hardware con la UI
    private var isMuted = false
    private var isSpeakerOn = false

    // Strutture per l'accumulo e batching dei candidati ICE
    private val iceCandidateBuffer = mutableListOf<IceCandidate>()
    private var isIceBufferTimerRunning = false

    // Task per chiudere la chiamata se l'altro utente scompare improvvisamente dalla rete
    private val reconnectionRunnable = Runnable {
        Log.w(TAG, "Tempo limite di riconnessione superato. Termino la chiamata rimasta appesa.")
        endCall()
    }

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (isActive && webRtcClient != null) {
                val isProtectedMode = appContext?.let {
                    SecurePrefs.get(it).getBoolean("temp_call_protect_ip", false)
                } ?: false

                webRtcClient?.checkIsRelayConnection { isRelay ->
                    webRtcClient?.getAudioStats { rxKbps, txKbps ->
                        uiHandler.post {
                            val displayString = "▼$rxKbps ▲$txKbps kbps"
                            val isTrafficDead = rxKbps < 2 && txKbps < 2
                            if (isTrafficDead) poorConnectionCounter++ else if (poorConnectionCounter > 0) poorConnectionCounter--

                            val isGracePeriod = (System.currentTimeMillis() - callStartTime) < 10000
                            val forceSurvival = !isGracePeriod && poorConnectionCounter >= 10

                            webRtcClient?.adjustAudioQuality(forceSurvival, isRelay, isProtectedMode)
                            val showHdBadge = !forceSurvival && !isRelay && !isProtectedMode && (rxKbps > 40 || txKbps > 40)
                            stateListener?.onBitrateUpdate(displayString, showHdBadge)
                        }
                    }
                }
            }
            if (isActive) uiHandler.postDelayed(this, 1000)
        }
    }

    interface CallStateListener {
        fun onStatusChanged(status: String)
        fun onCallEnded()
        fun onNetworkQualityChanged(status: NetworkQualityMonitor.NetworkStatus)
        fun onBitrateUpdate(bitrate: String, isHd: Boolean)
    }

    private var stateListener: CallStateListener? = null
    private var currentStatus: String = ""

    fun setListener(listener: CallStateListener?) {
        this.stateListener = listener
        if (isActive) {
            if (currentStatus.isNotEmpty()) listener?.onStatusChanged(currentStatus)
            listener?.onNetworkQualityChanged(currentNetworkQuality)
        }
    }

    fun init(context: Context, myId: Int) {
        if (isActive) return
        this.myUserId = myId
        this.appContext = context.applicationContext
        setupAudioManager(context)
        setupNetworkMonitor(context)
    }

    private fun acquireCallLocks(context: Context) {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            callWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CryptionChat:ActiveCallWakeLock")
            callWakeLock?.acquire(4 * 60 * 60 * 1000L)

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            callWifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CryptionChat:ActiveCallWifiLock")
            callWifiLock?.acquire()
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante l'acquisizione dei Call WakeLocks", e)
        }
    }

    private fun releaseCallLocks() {
        try {
            if (callWakeLock?.isHeld == true) callWakeLock?.release()
            callWakeLock = null

            if (callWifiLock?.isHeld == true) callWifiLock?.release()
            callWifiLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante il rilascio dei Call WakeLocks", e)
        }
    }

    private fun setupNetworkMonitor(context: Context) {
        if (networkQualityMonitor == null) {
            networkQualityMonitor = NetworkQualityMonitor(context) { status ->
                currentNetworkQuality = status
                uiHandler.post {
                    stateListener?.onNetworkQualityChanged(status)
                    sendNetworkStatusSignal(status == NetworkQualityMonitor.NetworkStatus.POOR)
                    if (status == NetworkQualityMonitor.NetworkStatus.DISCONNECTED) updateStatus(appContext?.getString(R.string.call_status_no_network) ?: "No network...")
                }
            }
        }
    }

    private fun sendNetworkStatusSignal(isPoor: Boolean) {
        if (isActive && callId > 0) {
            val signal = WebRtcSignal(type = "network_status", isNetworkPoor = isPoor)
            sendSignal("network_status", signal)
        }
    }

    fun startOutgoingCall(context: Context, targetId: Int, gId: Int, key: String?, iv: String?, targetName: String?, targetPic: String?) {
        reset(); isActive = true; isIncoming = false; isAnswered = true; otherUserId = targetId; groupId = gId; groupKey = key; groupIv = iv; otherUserName = targetName ?: context.getString(R.string.default_user_label); otherUserProfilePic = targetPic; this.appContext = context.applicationContext
        networkQualityMonitor?.register(); updateStatus(context.getString(R.string.call_status_ringing))
        acquireCallLocks(context)
        setupAudioManager(context)

        initWebRtc(context) {
            webRtcClient?.createOffer { sdp ->
                uiHandler.post {
                    pendingOfferSdp = sdp
                    if (callId != -1) { sendSignal("offer", sdp); pendingOfferSdp = null }
                }
            }
        }

        ApiClient.instance.initiateCall(callerId = myUserId, receiverId = otherUserId, groupId = groupId).enqueue(object : Callback<CallResponse> {
            override fun onResponse(call: Call<CallResponse>, response: Response<CallResponse>) {
                if (response.isSuccessful && response.body()?.status == "success") {
                    callId = response.body()?.data?.callId ?: -1
                    NotificationService.activeCallIdForPolling = callId
                    flushOutgoingBuffer()
                    pendingOfferSdp?.let { sendSignal("offer", it); pendingOfferSdp = null }
                } else endCall()
            }
            override fun onFailure(call: Call<CallResponse>, t: Throwable) { endCall() }
        })
    }

    fun prepareIncomingCall(context: Context, cId: Int, gId: Int, callerId: Int, key: String?, iv: String?, callerName: String?, callerPic: String?) {
        if (isActive && callId == cId) return
        reset(); isActive = true; isIncoming = true; isAnswered = false; callId = cId; groupId = gId; otherUserId = callerId; groupKey = key; groupIv = iv; otherUserName = callerName ?: context.getString(R.string.default_user_label); otherUserProfilePic = callerPic; this.appContext = context.applicationContext
        NotificationService.activeCallIdForPolling = callId; networkQualityMonitor?.register(); updateStatus(context.getString(R.string.call_status_incoming))
        acquireCallLocks(context)
        setupAudioManager(context)

        initWebRtc(context) {
            SignalBuffer.popAll().forEach { processSignal(it) }
        }
    }

    fun acceptCall() {
        updateStatus(appContext?.getString(R.string.call_status_connecting) ?: "Connecting...")
        userAcceptedCall = true
        isAnswered = true

        if (webRtcClient?.isRemoteDescriptionSet() == true) {
            if (deferredAnswerSdp != null) {
                sendSignal("answer", deferredAnswerSdp!!)
                deferredAnswerSdp = null
                userAcceptedCall = false
            } else {
                webRtcClient?.createAnswer { sdp ->
                    sendSignal("answer", sdp)
                    userAcceptedCall = false
                }
            }
        } else {
            Log.i(TAG, "acceptCall chiamato prima dell'SDP offer. L'invio della risposta avverrà automaticamente non appena l'offerta sarà configurata.")
        }
    }

    fun endCall() {
        if (!isActive) return

        isActive = false
        isAnswered = false

        stateListener?.onCallEnded()
        uiHandler.removeCallbacks(statsRunnable)
        uiHandler.removeCallbacks(reconnectionRunnable)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                webRtcClient?.stopAudio()
                delay(200)

                webRtcClient?.close()
                webRtcClient = null

                withContext(Dispatchers.Main) {
                    resetAudioManager()
                    networkQualityMonitor?.unregister()

                    if (callId > 0) {
                        ApiClient.instance.endCall(callId = callId, userId = myUserId).enqueue(object : Callback<BaseResponse> {
                            override fun onResponse(call: Call<BaseResponse>, r: Response<BaseResponse>) {}
                            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {}
                        })
                    }

                    appContext?.let { SecurePrefs.get(it).edit().putBoolean("temp_call_protect_ip", false).apply() }
                    NotificationService.activeCallIdForPolling = 0
                    reset()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore durante chiusura chiamata", e)
            }
        }
    }

    fun isSpeakerOn(): Boolean = isSpeakerOn

    fun isMuted(): Boolean = isMuted

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        webRtcClient?.setAudioEnabled(!isMuted)
        return isMuted
    }

    fun toggleSpeaker(): Boolean {
        isSpeakerOn = !isSpeakerOn
        val am = audioManager ?: return isSpeakerOn
        am.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isSpeakerOn) {
                val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                var speakerDevice: AudioDeviceInfo? = null
                for (device in devices) {
                    if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        speakerDevice = device
                        break
                    }
                }
                if (speakerDevice != null) {
                    am.setCommunicationDevice(speakerDevice)
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = true
                }
            } else {
                am.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = isSpeakerOn
        }
        return isSpeakerOn
    }

    fun processSignalJson(json: String) { try { Gson().fromJson(json, Array<SignalData>::class.java).forEach { processSignal(it) } } catch (e: Exception) { } }

    private fun initWebRtc(context: Context, onReady: () -> Unit = {}) {
        if (webRtcClient != null) {
            onReady()
            return
        }
        webRtcClient = WebRtcClient(context, object : WebRtcClient.PeerConnectionObserver {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    synchronized(iceCandidateBuffer) {
                        iceCandidateBuffer.add(it)
                    }
                    if (!isIceBufferTimerRunning) {
                        isIceBufferTimerRunning = true

                        // Batching per ridurre le chiamate concorrenti
                        uiHandler.postDelayed({
                            synchronized(iceCandidateBuffer) {
                                if (iceCandidateBuffer.isNotEmpty()) {
                                    sendSignal("candidates_batch", iceCandidateBuffer.toList())
                                    iceCandidateBuffer.clear()
                                }
                                isIceBufferTimerRunning = false
                            }
                        }, 350)
                    }
                }
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                uiHandler.post {
                    when(state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            uiHandler.removeCallbacks(reconnectionRunnable)
                            if (callStartTime == 0L) {
                                callStartTime = System.currentTimeMillis()
                                poorConnectionCounter = 0
                                uiHandler.post(statsRunnable)
                            }
                            updateStatus(appContext?.getString(R.string.call_status_connected) ?: "Connected")
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            updateStatus(appContext?.getString(R.string.call_status_reconnecting) ?: "Reconnecting...")
                            uiHandler.removeCallbacks(reconnectionRunnable)
                            uiHandler.postDelayed(reconnectionRunnable, RECONNECTION_TIMEOUT_MS)
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            val retryingText = appContext?.getString(R.string.call_status_retrying) ?: "Retrying connection..."
                            updateStatus(retryingText)
                            sendSignal("call_status", WebRtcSignal(type = "call_status", callStatus = retryingText))
                            webRtcClient?.restartIce()
                            webRtcClient?.createOffer { sendSignal("offer", it) }

                            uiHandler.removeCallbacks(reconnectionRunnable)
                            uiHandler.postDelayed(reconnectionRunnable, RECONNECTION_TIMEOUT_MS)
                        }
                        else -> {}
                    }
                }
            }

            override fun onRenegotiationNeeded() { if (!isIncoming && isActive && webRtcClient?.getSignalingState() == PeerConnection.SignalingState.STABLE) webRtcClient?.createOffer { sendSignal("offer", it) } }
            override fun onAddStream(p0: MediaStream?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })

        // Recupero asincrono delle credenziali per l'account TURN ottimale
        CoroutineScope(Dispatchers.IO).launch {
            val iceServers = MeteredcaManager.fetchIceServers(context)
            withContext(Dispatchers.Main) {
                webRtcClient?.initConnection(iceServers)
                onReady()
            }
        }
    }

    private fun processSignal(signal: SignalData) {
        val key = groupKey ?: return; val iv = groupIv ?: return
        val decrypted = EncryptionHelper.decrypt(signal.payload, key, iv) ?: return

        uiHandler.post {
            try {
                // Se WebRTC non è pronto (connessione in fase di caricamento asincrono), rimettiamo il segnale in coda
                if (webRtcClient == null || webRtcClient?.isInitialized() == false) {
                    Log.d(TAG, "WebRTC non pronto, rimetto segnale in coda: ${signal.type}")
                    SignalBuffer.add(signal)
                    return@post
                }

                if (signal.type == "candidates_batch") {
                    val type = object : com.google.gson.reflect.TypeToken<List<WebRtcSignal>>() {}.type
                    val candidates: List<WebRtcSignal> = Gson().fromJson(decrypted, type)
                    candidates.forEach { rtcSignal ->
                        if (rtcSignal.candidate != null) {
                            webRtcClient?.addIceCandidate(IceCandidate(rtcSignal.sdpMid, rtcSignal.sdpMLineIndex, rtcSignal.candidate))
                        }
                    }
                    return@post
                }

                val rtcSignal = Gson().fromJson(decrypted, WebRtcSignal::class.java) ?: return@post
                when (rtcSignal.type) {
                    "call_status" -> rtcSignal.callStatus?.let {
                        val localized = if (it == "Riprovo connessione..." || it == "Retrying connection...") (appContext?.getString(R.string.call_status_retrying) ?: it) else it
                        updateStatus(localized)
                    }
                    "offer" -> {
                        webRtcClient?.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, rtcSignal.sdp)) {
                            if (isIncoming) {
                                webRtcClient?.createAnswer { sdp ->
                                    deferredAnswerSdp = sdp
                                    Log.d(TAG, "SDP Answer differito generato con successo.")

                                    if (userAcceptedCall) {
                                        sendSignal("answer", sdp)
                                        deferredAnswerSdp = null
                                        userAcceptedCall = false
                                        Log.i(TAG, "Risposta inviata automaticamente su ricezione SDP Offer.")
                                    }
                                }
                            }
                        }
                    }
                    "answer" -> {
                        if (webRtcClient?.getSignalingState() != PeerConnection.SignalingState.STABLE) {
                            webRtcClient?.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, rtcSignal.sdp))
                        }
                        updateStatus(appContext?.getString(R.string.call_status_connecting) ?: "Connecting...")
                    }
                    "candidate" -> {
                        if (rtcSignal.candidate != null) webRtcClient?.addIceCandidate(IceCandidate(rtcSignal.sdpMid, rtcSignal.sdpMLineIndex, rtcSignal.candidate))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore processamento segnale", e)
            }
        }
    }

    private fun sendSignal(type: String, data: Any, attempt: Int = 1) {
        if (callId <= 0) { pendingOutgoingSignals.add(Pair(type, data)); return }
        val key = groupKey ?: return; val iv = groupIv ?: return

        val signalObj = when (type) {
            "candidates_batch" -> {
                val list = data as List<IceCandidate>
                list.map { WebRtcSignal("candidate", candidate = it.sdp, sdpMid = it.sdpMid, sdpMLineIndex = it.sdpMLineIndex) }
            }
            else -> when (data) {
                is SessionDescription -> WebRtcSignal(type, sdp = data.description)
                is IceCandidate -> WebRtcSignal(type, candidate = data.sdp, sdpMid = data.sdpMid, sdpMLineIndex = data.sdpMLineIndex)
                else -> data
            }
        }

        val enc = EncryptionHelper.encryptDirect(Gson().toJson(signalObj), key, iv) ?: return

        ApiClient.instance.sendCallSignal(callId = callId, senderId = myUserId, type = type, payload = enc).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, r: Response<BaseResponse>) {
                if (!r.isSuccessful && attempt < 5) uiHandler.postDelayed({ sendSignal(type, data, attempt + 1) }, 1000)
            }
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                if (attempt < 5) uiHandler.postDelayed({ sendSignal(type, data, attempt + 1) }, 1000)
            }
        })
    }

    private fun flushOutgoingBuffer() { while (pendingOutgoingSignals.isNotEmpty()) { val (t, d) = pendingOutgoingSignals.poll() ?: break; sendSignal(t, d) } }

    private fun setupAudioManager(context: Context) {
        if (audioManager == null) audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.let { am ->
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isMicrophoneMute = isMuted

            // Applica lo stato reale ed esplicito dello speakerphone all'avvio
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isSpeakerOn) {
                    val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    val speaker = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) am.setCommunicationDevice(speaker)
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = isSpeakerOn
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { }
                    .build()
                am.requestAudioFocus(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        }
    }

    private fun resetAudioManager() {
        audioManager?.let { am ->
            am.mode = AudioManager.MODE_NORMAL
            am.isMicrophoneMute = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
        audioManager = null
    }

    private fun updateStatus(status: String) { currentStatus = status; stateListener?.onStatusChanged(status) }

    private fun reset() {
        releaseCallLocks()
        callId = -1; groupId = -1; groupKey = null; groupIv = null; deferredAnswerSdp = null; pendingOfferSdp = null; pendingOutgoingSignals.clear(); currentStatus = ""; callStartTime = 0; poorConnectionCounter = 0; uiHandler.removeCallbacks(statsRunnable)
        uiHandler.removeCallbacks(reconnectionRunnable)
        synchronized(iceCandidateBuffer) { iceCandidateBuffer.clear(); isIceBufferTimerRunning = false }
        userAcceptedCall = false
        isAnswered = false
        isMuted = false
        isSpeakerOn = false
        appContext?.let { SecurePrefs.get(it).edit().remove("active_call_metered_app").apply() }
    }
}