package com.lollo1344.cryptionchat

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bumptech.glide.Glide
import com.lollo1344.cryptionchat.databinding.ActivityCallBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Locale

class CallActivity : BaseActivity(), CallManager.CallStateListener {

    private lateinit var binding: ActivityCallBinding

    companion object {
        private const val TAG = "CallActivity"
    }

    private var intentCallId: Int = -1
    private var intentGroupId: Int = -1
    private var isIncoming: Boolean = false
    private var otherUserId: Int = -1
    private var intentGroupKey: String? = null
    private var intentGroupIv: String? = null
    private var intentOtherUserName: String = "Utente"
    private var intentProfilePic: String? = null

    private var hasPlayedConnectionFeedback = false
    private var isInitializingCallKeys = false

    private fun isValidAesHexKey(key: String?): Boolean {
        return !key.isNullOrBlank() && key.matches(Regex("^[0-9a-fA-F]{32,64}$"))
    }

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateTimerUI()
            timerHandler.postDelayed(this, 1000)
        }
    }

    private val signalsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                NotificationService.ACTION_WEBRTC_SIGNALS -> {
                    val json = intent.getStringExtra("SIGNALS_JSON")
                    if (json != null) CallManager.processSignalJson(json)
                }
                NotificationService.ACTION_CALL_ENDED -> {
                    CallManager.endCall()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // OVERRIDE DEL TASTO INDIETRO: Sposta l'applicazione in background senza terminare la chiamata
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        val prefs = SecurePrefs.get(this)
        val myUserId = prefs.getInt("user_id", -1)
        intentCallId = intent.getIntExtra("CALL_ID", -1)
        intentGroupId = intent.getIntExtra("GROUP_ID", -1)
        isIncoming = intent.getBooleanExtra("IS_INCOMING", false)
        otherUserId = intent.getIntExtra("OTHER_USER_ID", -1)
        intentOtherUserName = intent.getStringExtra("OTHER_USERNAME") ?: "Utente"
        intentProfilePic = intent.getStringExtra("PROFILE_PIC")

        val isZk = prefs.getBoolean("is_zk_$intentGroupId", false)
        intentGroupKey = intent.getStringExtra("GROUP_KEY")?.takeIf { isValidAesHexKey(it) }
            ?: (if (isZk) prefs.getString("zk_key_$intentGroupId", null) else prefs.getString("key_$intentGroupId", null))?.takeIf { isValidAesHexKey(it) }
        intentGroupIv = intent.getStringExtra("GROUP_IV")?.takeIf { it.isNotBlank() }
            ?: (if (isZk) prefs.getString("zk_iv_$intentGroupId", null) else prefs.getString("iv_$intentGroupId", null))?.takeIf { it.isNotBlank() }

        val filter = IntentFilter().apply {
            addAction(NotificationService.ACTION_WEBRTC_SIGNALS)
            addAction(NotificationService.ACTION_CALL_ENDED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(signalsReceiver, filter)

        CallManager.init(applicationContext, myUserId)
        CallManager.setListener(this)

        if (CallManager.isActive) {
            restoreUiFromManager()
        } else {
            if (intentGroupKey.isNullOrBlank() || intentGroupIv.isNullOrBlank()) {
                val isV2Chat = prefs.getInt("e2ee_version_$intentGroupId", 1) >= 2
                if (isV2Chat && myUserId > 0 && otherUserId > 0) {
                    isInitializingCallKeys = true
                    binding.textStatus.text = "Derivazione chiavi E2EE..."
                    lifecycleScope.launch {
                        val callKeys = withContext(Dispatchers.IO) {
                            E2eeSessionOrchestrator.deriveZeroKnowledgeCallKeysSync(this@CallActivity, myUserId, otherUserId)
                        }
                        if (isFinishing || isDestroyed) return@launch
                        if (callKeys != null) {
                            isInitializingCallKeys = false
                            intentGroupKey = callKeys.first
                            intentGroupIv = callKeys.second
                            checkPermissionsAndStart()
                        } else {
                            fetchGroupKeysAndStart()
                        }
                    }
                } else {
                    fetchGroupKeysAndStart()
                }
            } else {
                checkPermissionsAndStart()
            }
        }

        setupClickListeners()
        timerHandler.post(timerRunnable)
    }

    private fun playConnectionFeedback() {
        if (hasPlayedConnectionFeedback) return
        hasPlayedConnectionFeedback = true

        Log.d(TAG, "Tentativo di feedback: Vibrazione + Suono")

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }

            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(300, android.os.VibrationEffect.DEFAULT_AMPLITUDE), audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(300)
            }

            val toneGen = android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100)
            toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_ANSWER, 400)

            Handler(Looper.getMainLooper()).postDelayed({
                try { toneGen.release() } catch (e: Exception) {}
            }, 500)

        } catch (e: Exception) {
            Log.e(TAG, "Errore feedback connessione: ${e.message}")
        }
    }

    private fun setupClickListeners() {
        binding.btnAnswerCall.setOnClickListener {
            stopRinging()
            binding.btnAnswerCall.visibility = View.GONE
            binding.controlsLayout.visibility = View.VISIBLE
            CallManager.acceptCall()
            setOngoingCallNotification(true)
        }

        binding.btnEndCall.setOnClickListener {
            stopRinging()
            CallManager.endCall()
            finish() // Forza l'uscita immediata dall'Activity
        }

        binding.btnMute.setOnClickListener {
            val isMuted = CallManager.toggleMute()
            binding.btnMute.isChecked = isMuted
            binding.btnMute.setIconResource(if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        }

        binding.btnSpeaker.setOnClickListener {
            val isSpeaker = CallManager.toggleSpeaker()
            binding.btnSpeaker.isChecked = isSpeaker
        }
    }

    private fun restoreUiFromManager() {
        binding.textUsername.text = CallManager.otherUserName
        if (!CallManager.otherUserProfilePic.isNullOrBlank()) {
            val url = ApiClient.BASE_URL + CallManager.otherUserProfilePic
            Glide.with(this).load(url).into(binding.profileImage)
            Glide.with(this).load(url).override(20).into(binding.backgroundBlur)
        }

        binding.actionLayout.visibility = View.VISIBLE

        // Verifica se la chiamata è stata risposta per adattare asincronamente la visualizzazione
        if (CallManager.isAnswered) {
            binding.controlsLayout.visibility = View.VISIBLE
            binding.btnAnswerCall.visibility = View.GONE
        } else {
            binding.controlsLayout.visibility = View.GONE
            binding.btnAnswerCall.visibility = View.VISIBLE
        }

        val isMuted = CallManager.isMuted()
        binding.btnMute.isChecked = isMuted
        binding.btnMute.setIconResource(if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)

        binding.btnSpeaker.isChecked = CallManager.isSpeakerOn()

        if (CallManager.callStartTime > 0) {
            binding.activeCallInfoLayout.visibility = View.VISIBLE
            binding.textStatus.visibility = View.GONE
            onNetworkQualityChanged(CallManager.currentNetworkQuality)
        }
    }

    private fun startCallLogic() {
        if (CallManager.isActive) return

        if (isIncoming) {
            binding.actionLayout.visibility = View.VISIBLE
            binding.btnAnswerCall.visibility = View.VISIBLE
            binding.controlsLayout.visibility = View.GONE
            binding.textUsername.text = intentOtherUserName

            CallManager.prepareIncomingCall(
                applicationContext, intentCallId, intentGroupId, otherUserId,
                intentGroupKey, intentGroupIv, intentOtherUserName, intentProfilePic
            )
        } else {
            binding.actionLayout.visibility = View.VISIBLE
            binding.controlsLayout.visibility = View.VISIBLE
            binding.btnAnswerCall.visibility = View.GONE
            binding.textUsername.text = intentOtherUserName

            CallManager.startOutgoingCall(
                applicationContext, otherUserId, intentGroupId,
                intentGroupKey, intentGroupIv, intentOtherUserName, intentProfilePic
            )
            setOngoingCallNotification(true)
        }

        if (!intentProfilePic.isNullOrBlank()) {
            val url = ApiClient.BASE_URL + intentProfilePic
            Glide.with(this).load(url).into(binding.profileImage)
            Glide.with(this).load(url).override(20).into(binding.backgroundBlur)
        }
    }

    private fun updateTimerUI() {
        if (CallManager.callStartTime > 0) {
            val duration = System.currentTimeMillis() - CallManager.callStartTime
            val seconds = (duration / 1000) % 60
            val minutes = (duration / (1000 * 60)) % 60
            val hours = (duration / (1000 * 60 * 60))

            val timeString = if (hours > 0) {
                String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            }

            binding.textDuration.text = timeString

            binding.activeCallInfoLayout.visibility = View.VISIBLE
            binding.textStatus.visibility = View.GONE
        } else {
            binding.activeCallInfoLayout.visibility = View.GONE
            binding.textStatus.visibility = View.VISIBLE
        }
    }

    override fun onStatusChanged(status: String) {
        runOnUiThread {
            Log.d(TAG, "Stato chiamata ricevuto: $status")

            if (status.equals("Connesso", ignoreCase = true) && !isFinishing) {
                playConnectionFeedback()
            }

            if (status.equals("Connesso", ignoreCase = true) || status.equals("Chiamata attiva", ignoreCase = true)) {
                binding.textStatus.visibility = View.GONE
                binding.activeCallInfoLayout.visibility = View.VISIBLE
            } else {
                binding.textStatus.text = status
                binding.textStatus.visibility = View.VISIBLE
                binding.activeCallInfoLayout.visibility = View.GONE
            }
        }
    }

    override fun onNetworkQualityChanged(status: NetworkQualityMonitor.NetworkStatus) {
        runOnUiThread {
            if (binding.activeCallInfoLayout.visibility == View.VISIBLE) {
                val (iconRes, colorRes) = when(status) {
                    NetworkQualityMonitor.NetworkStatus.GOOD -> Pair(R.drawable.ic_signal_good, R.color.primary_light)
                    NetworkQualityMonitor.NetworkStatus.POOR -> Pair(R.drawable.ic_signal_poor, R.color.warning_container_light)
                    NetworkQualityMonitor.NetworkStatus.DISCONNECTED -> Pair(R.drawable.ic_signal_off, R.color.error_light)
                }

                binding.networkQualityIcon.setImageResource(iconRes)
                binding.networkQualityIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
            }
        }
    }

    override fun onBitrateUpdate(bitrate: String, isHd: Boolean) {
        runOnUiThread {
            if (binding.activeCallInfoLayout.visibility == View.VISIBLE) {
                binding.textBitrate.text = bitrate
                binding.hdAudioBadge.visibility = if (isHd) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onCallEnded() {
        runOnUiThread {
            stopRinging()
            setOngoingCallNotification(false)
            Toast.makeText(this, "Chiamata terminata", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun stopRinging() {
        val stopIntent = Intent(NotificationService.ACTION_STOP_SOUND).setPackage(packageName)
        sendBroadcast(stopIntent)
    }

    private fun setOngoingCallNotification(show: Boolean) {
        val intent = Intent(NotificationService.ACTION_SET_ONGOING_CALL).apply {
            setPackage(packageName)
            putExtra("SHOW", show)
            putExtra("USERNAME", binding.textUsername.text.toString())
        }
        sendBroadcast(intent)
    }

    private fun fetchGroupKeysAndStart() {
        isInitializingCallKeys = true
        binding.textStatus.text = "Recupero chiavi..."
        val prefs = SecurePrefs.get(this)
        val myUserId = prefs.getInt("user_id", -1)

        ApiClient.instance.getMessages(
            groupId = intentGroupId,
            userId = myUserId,
            cacheBuster = System.currentTimeMillis()
        ).enqueue(object : Callback<ApiResponse<GetMessagesData>> {
            override fun onResponse(call: Call<ApiResponse<GetMessagesData>>, response: Response<ApiResponse<GetMessagesData>>) {
                if (isFinishing || isDestroyed) return
                if (response.isSuccessful && response.body()?.status == "success") {
                    val data = response.body()?.data
                    if (data != null) {
                        prefs.edit()
                            .putInt("e2ee_version_$intentGroupId", data.e2eeVersion)
                            .putInt("membership_epoch_$intentGroupId", data.membershipEpoch)
                            .apply()

                        if (!data.encryptionKey.isNullOrBlank() && !data.encryptionIv.isNullOrBlank() && isValidAesHexKey(data.encryptionKey)) {
                            isInitializingCallKeys = false
                            intentGroupKey = data.encryptionKey
                            intentGroupIv = data.encryptionIv
                            prefs.edit()
                                .putString("key_$intentGroupId", intentGroupKey)
                                .putString("iv_$intentGroupId", intentGroupIv)
                                .apply()
                            checkPermissionsAndStart()
                        } else if (myUserId > 0 && otherUserId > 0) {
                            binding.textStatus.text = "Derivazione chiavi E2EE..."
                            lifecycleScope.launch {
                                val callKeys = withContext(Dispatchers.IO) {
                                    E2eeSessionOrchestrator.deriveZeroKnowledgeCallKeysSync(this@CallActivity, myUserId, otherUserId)
                                }
                                if (isFinishing || isDestroyed) return@launch
                                isInitializingCallKeys = false
                                if (callKeys != null) {
                                    intentGroupKey = callKeys.first
                                    intentGroupIv = callKeys.second
                                    checkPermissionsAndStart()
                                } else {
                                    Toast.makeText(this@CallActivity, "Impossibile derivare le chiavi E2EE per la chiamata.", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                            }
                        } else {
                            isInitializingCallKeys = false
                            finish()
                        }
                    } else {
                        isInitializingCallKeys = false
                        finish()
                    }
                } else {
                    isInitializingCallKeys = false
                    finish()
                }
            }
            override fun onFailure(call: Call<ApiResponse<GetMessagesData>>, t: Throwable) {
                if (isFinishing || isDestroyed) return
                if (myUserId > 0 && otherUserId > 0) {
                    lifecycleScope.launch {
                        val callKeys = withContext(Dispatchers.IO) {
                            E2eeSessionOrchestrator.deriveZeroKnowledgeCallKeysSync(this@CallActivity, myUserId, otherUserId)
                        }
                        if (isFinishing || isDestroyed) return@launch
                        isInitializingCallKeys = false
                        if (callKeys != null) {
                            intentGroupKey = callKeys.first
                            intentGroupIv = callKeys.second
                            checkPermissionsAndStart()
                        } else {
                            finish()
                        }
                    }
                } else {
                    isInitializingCallKeys = false
                    finish()
                }
            }
        })
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCallLogic()
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCallLogic()
        } else {
            Toast.makeText(this, "Permesso microfono negato.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // CONTROLLO DI STATO AL RIENTRO IN FOREGROUND: Se la chiamata è stata disconnessa o
        // terminata asincronamente in background, l'Activity chiude se stessa istantaneamente.
        if (!CallManager.isActive && !isInitializingCallKeys) {
            Log.d(TAG, "onResume: Rilevato stato inattivo. Chiudo l'interfaccia di chiamata.")
            stopRinging()
            setOngoingCallNotification(false)
            finish()
            return
        }
        restoreUiFromManager()
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        CallManager.setListener(null)
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(signalsReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}