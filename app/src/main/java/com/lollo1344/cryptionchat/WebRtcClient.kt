package com.lollo1344.cryptionchat

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.math.BigInteger

class WebRtcClient(private val context: Context, private val observer: PeerConnectionObserver) {

    private val rootEglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null

    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    private val BITRATE_SURVIVAL = 8000
    private val BITRATE_RELAY_SAFE = 24000
    private val BITRATE_UNLIMITED = 510000

    private var previousBytesReceived: Long = 0
    private var previousBytesSent: Long = 0
    private var previousTimestamp: Double = 0.0
    private var currentAppliedBitrate: Int = -1

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
            .createPeerConnectionFactory()

        audioDeviceModule.release()
    }

    fun isInitialized(): Boolean {
        return peerConnection != null
    }

    fun initConnection(iceServers: List<PeerConnection.IceServer>) {
        val protectIp = SecurePrefs.get(context).getBoolean("temp_call_protect_ip", false)

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            iceTransportsType = if (protectIp) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL

            // Potatura delle interfacce costose per accelerare i tempi di connessione
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.LOW_COST

            pruneTurnPorts = true

            // Pre-gathering aggressivo per accelerare lo scambio ICE
            iceCandidatePoolSize = 5
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)

        val mediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "false"))
        }

        audioSource = peerConnectionFactory.createAudioSource(mediaConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("ARDAMSa0", audioSource)

        // Inizializza la traccia audio rispettando lo stato mutato/attivo configurato a monte
        localAudioTrack?.setEnabled(!CallManager.isMuted())

        peerConnection?.addTrack(localAudioTrack, listOf("ARDAMS"))
    }

    fun checkIsRelayConnection(callback: (Boolean) -> Unit) {
        peerConnection?.getStats { report ->
            var isRelay = false
            for (stats in report.statsMap.values) {
                if (stats.type == "transport") {
                    val activePairId = stats.members["selectedCandidatePairId"] as? String
                    if (activePairId != null) {
                        val pairStats = report.statsMap[activePairId]
                        val localId = pairStats?.members?.get("localCandidateId") as? String
                        val localType = report.statsMap[localId]?.members?.get("candidateType") as? String
                        val remoteId = pairStats?.members?.get("remoteCandidateId") as? String
                        val remoteType = report.statsMap[remoteId]?.members?.get("candidateType") as? String

                        if (localType == "relay" || remoteType == "relay") isRelay = true
                    }
                }
            }
            callback(isRelay)
        }
    }

    fun getAudioStats(callback: (Int, Int) -> Unit) {
        peerConnection?.getStats { report ->
            var rx = 0L; var tx = 0L; var ts = 0.0; var found = false
            for (stats in report.statsMap.values) {
                if (stats.type == "inbound-rtp") { rx = parseBytes(stats.members["bytesReceived"]); ts = stats.timestampUs / 1000.0; found = true }
                if (stats.type == "outbound-rtp") { tx = parseBytes(stats.members["bytesSent"]); found = true }
            }
            if (found && previousTimestamp > 0) {
                val diff = ts - previousTimestamp
                if (diff > 0) {
                    val rxKbps = ((rx - previousBytesReceived) * 8 / diff).toInt()
                    val txKbps = ((tx - previousBytesSent) * 8 / diff).toInt()
                    callback(rxKbps, txKbps)

                    // Tracciamento dei consumi reali dell'Account 2 (Account 2) se attivo nella sessione corrente
                    val activeApp = SecurePrefs.get(context).getString("active_call_metered_app", "")
                    if (activeApp == "YOUR_METERED_APP2_NAME") {
                        val deltaRx = rx - previousBytesReceived
                        val deltaTx = tx - previousBytesSent
                        val totalDelta = (if (deltaRx > 0) deltaRx else 0L) + (if (deltaTx > 0) deltaTx else 0L)
                        if (totalDelta > 0L) {
                            MeteredcaManager.addBytesUsed(context, totalDelta)
                        }
                    }
                }
            }
            if (found) { previousBytesReceived = rx; previousBytesSent = tx; previousTimestamp = ts }
        }
    }

    fun adjustAudioQuality(isLowQuality: Boolean, isRelay: Boolean, isProtectedMode: Boolean) {
        val targetBitrate = when {
            isLowQuality -> BITRATE_SURVIVAL
            isRelay || isProtectedMode -> BITRATE_RELAY_SAFE
            else -> BITRATE_UNLIMITED
        }
        if (currentAppliedBitrate == targetBitrate) return
        val senders = peerConnection?.senders ?: return
        for (sender in senders) {
            if (sender.track()?.kind() == "audio") {
                val parameters = sender.parameters
                if (parameters.encodings.isNotEmpty()) {
                    parameters.encodings[0].maxBitrateBps = targetBitrate
                    sender.parameters = parameters
                    currentAppliedBitrate = targetBitrate
                }
            }
        }
    }

    private fun parseBytes(value: Any?): Long = when(value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        is BigInteger -> value.toLong()
        else -> 0L
    }

    fun restartIce() { peerConnection?.restartIce() }
    fun getSignalingState() = peerConnection?.signalingState()
    fun isRemoteDescriptionSet() = peerConnection?.remoteDescription != null

    fun setRemoteDescription(sdp: SessionDescription, onSetSuccess: (() -> Unit)? = null) {
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                super.onSetSuccess()
                Log.d("WebRtcClient", "setRemoteDescription successo, elaborazione ICE candidates in coda")
                drainRemoteCandidates()
                onSetSuccess?.invoke()
            }

            override fun onSetFailure(error: String?) {
                super.onSetFailure(error)
                Log.e("WebRtcClient", "setRemoteDescription fallito: $error")
            }
        }, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        if (peerConnection?.remoteDescription != null) {
            peerConnection?.addIceCandidate(candidate)
        } else {
            synchronized(pendingIceCandidates) {
                pendingIceCandidates.add(candidate)
            }
            Log.d("WebRtcClient", "ICE candidate accodato: remoteDescription non ancora impostato.")
        }
    }

    private fun drainRemoteCandidates() {
        synchronized(pendingIceCandidates) {
            for (candidate in pendingIceCandidates) {
                peerConnection?.addIceCandidate(candidate)
                Log.d("WebRtcClient", "ICE candidate rimosso dalla coda e applicato correttamente.")
            }
            pendingIceCandidates.clear()
        }
    }

    fun setAudioEnabled(enabled: Boolean) { localAudioTrack?.setEnabled(enabled) }
    fun isAudioEnabled() = localAudioTrack?.enabled() ?: false

    fun createOffer(cb: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let { peerConnection?.setLocalDescription(SimpleSdpObserver(), it); cb(it) }
            }
        }, constraints)
    }

    fun createAnswer(cb: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let { peerConnection?.setLocalDescription(SimpleSdpObserver(), it); cb(it) }
            }
        }, constraints)
    }

    fun stopAudio() {
        try {
            localAudioTrack?.setEnabled(false)
            localAudioTrack?.dispose()
            localAudioTrack = null
            audioSource?.dispose()
            audioSource = null
        } catch (e: Exception) { Log.e("WebRtcClient", "Errore stopAudio", e) }
    }

    fun close() {
        try {
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
            Thread.sleep(100)
            peerConnectionFactory.dispose()
            rootEglBase.release()
        } catch (e: Exception) { Log.e("WebRtcClient", "Errore chiusura WebRTC", e) }
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
    interface PeerConnectionObserver : PeerConnection.Observer
}