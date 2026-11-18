package com.lollo1344.cryptionchat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.widget.ScrollView
import android.text.Html
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.OnReceiveContentListener
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.lollo1344.cryptionchat.databinding.ActivityChatBinding
import com.lollo1344.cryptionchat.databinding.DialogGroupSettingsBinding
import com.lollo1344.cryptionchat.databinding.DialogReactionsBinding
import com.lollo1344.cryptionchat.databinding.DialogReadReceiptsBinding
import com.lollo1344.cryptionchat.databinding.DialogViewOnceAudioBinding
import com.lollo1344.cryptionchat.databinding.ToolbarChatBinding
import com.yalantis.ucrop.UCrop
import ja.burhanrashid52.photoeditor.PhotoEditor
import ja.burhanrashid52.photoeditor.PhotoEditorView
import ja.burhanrashid52.photoeditor.SaveSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private const val TAG = "ChatActivity"
private const val MIN_RECORDING_DURATION_MS = 1000

class ChatActivity : BaseActivity(), MessageInteractionListener {

    // NUOVA PROPRIETÀ DI CLASSE PER L'UPLOAD CORRENTE
    private var currentUploadTempId: String? = null


    private lateinit var binding: ActivityChatBinding
    private lateinit var toolbarBinding: ToolbarChatBinding
    private lateinit var messageAdapter: MessageAdapter

    private lateinit var audioRecorderHelper: AudioRecorderHelper

    private var recordingJob: Job? = null
    private var recordingTime = 0

    private var audioFilePath: String? = null
    private var recordStartTime: Long = 0

    private var lastProcessedSystemMsgId: Int = -1
    private var initialX = 0f
    private var initialY = 0f
    private var isRecordingCancelled = false
    private var isRecordingLocked = false

    private var groupId: Int = -1
    private var userId: Int = -1
    private var username: String = ""
    private var groupName: String = ""
    private var isCurrentUserAdmin: Boolean = false
    private var isPrivateChat: Boolean = false

    private var lastOtherUserIsOnline: Boolean? = null
    private var lastOtherUserLastSeen: String? = null

    private var currentAdminsOnlyChat: Boolean = false
    private var currentGroupPictureUrl: String? = null

    private var currentGroupKey: String? = null
    private var currentGroupIv: String? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var pollingRunnable: Runnable
    private val pollingInterval = 2500L

    private var lastKnownMessageId: Int = 0
    private var currentAutoDeleteMinutes: Int = 0
    private var lastKnownPendingCount: Int = 0

    private val typingHandler = Handler(Looper.getMainLooper())
    private var isTyping = false
    private val TYPING_TIMEOUT = 2500L
    private val TYPING_KEEPALIVE_MS = 3000L
    private val stoppedTypingRunnable = Runnable {
        typingHandler.removeCallbacks(keepTypingRunnable)
        if (isTyping) {
            isTyping = false
            sendTypingStatus(false)
        }
    }
    private val keepTypingRunnable: Runnable = object : Runnable {
        override fun run() {
            if (isTyping && !isFinishing && !isDestroyed) {
                sendTypingStatus(true)
                typingHandler.postDelayed(this, TYPING_KEEPALIVE_MS)
            }
        }
    }

    private var membersDialog: Dialog? = null
    private var reactionDialog: Dialog? = null
    private var mentionDialog: AlertDialog? = null
    private var currentEditorDialog: Dialog? = null
    private var activeProgressDialog: AlertDialog? = null
    private var viewOnceDialog: Dialog? = null
    private var groupSettingsDialog: AlertDialog? = null

    private var replyingToMessage: Message? = null
    private var activeSettingsDialogBinding: DialogGroupSettingsBinding? = null

    private var photoUri: Uri? = null
    private var groupMembers: List<GroupMember> = emptyList()
    private val mentionedUsersMap = mutableMapOf<String, Int>()

    private var currentPhotoEditor: PhotoEditor? = null
    private var currentEditingImageUri: Uri? = null

    private var isScreenshotWarningSent = false
    private var hasSentAutoReset = false

    private var currentE2eeVersion: Int = 1
    private var currentMembershipEpoch: Int = 1
    private var otherUserId: Int = -1
    private var pendingVerifyPeerId: Int = -1
    private var blockedPeerIds: Set<Int> = emptySet()
    private var tofuWarningBanner: LinearLayout? = null
    private var hasShownDecryptErrorNotice: Boolean = false

    private val pickChatBackgroundLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            if (ChatBackgroundManager.setCustomBackground(this, it, ChatBackgroundManager.TARGET_CHAT, groupId)) {
                Toast.makeText(this, "Sfondo applicato a questa chat!", Toast.LENGTH_SHORT).show()
                applyCurrentChatBackground()
            } else {
                Toast.makeText(this, "Errore nel salvataggio.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var lastServerMessages: List<Message> = emptyList()

    private val zkBarcodeLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        if (result.contents != null) {
            handleZkQrCode(result.contents)
        }
    }

    private fun uploadFileFromUri(uri: Uri) {
        val mimeType = contentResolver.getType(uri) ?: "*/*"
        if (mimeType.startsWith("image/") && mimeType != "image/gif") {
            showImagePreviewDialog(uri, mimeType)
        } else {
            uploadFile(uri, mimeType, null, skipCompression = (mimeType == "image/gif"))
        }
    }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Permesso concesso. Riprova l'aggiornamento.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Permesso di installazione non concesso.", Toast.LENGTH_LONG).show()
        }
    }

    private val requestMediaPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            // Permessi concessi
        }
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Permesso concesso. Tieni premuto per registrare.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permesso per registrare audio negato.", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            launchCamera()
        } else {
            Toast.makeText(this, "Permesso per la fotocamera negato.", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted || coarseGranted) {
            showLocationConfirmationDialog()
        } else {
            Toast.makeText(this, "Permesso di posizione negato.", Toast.LENGTH_SHORT).show()
        }
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            photoUri?.let { uri ->
                uploadFileFromUri(uri)
            }
        }
    }

    private val pickGroupImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { uploadGroupPicture(it) }
    }

    private val pickMediaLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { uploadFileFromUri(it) }
    }

    private val pickAnyFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { uploadFileFromUri(it) }
    }

    private val cropResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            if (resultUri != null) {
                currentEditorDialog?.findViewById<PhotoEditorView>(R.id.photoEditorView)?.source?.setImageURI(resultUri)
                currentEditingImageUri = resultUri
            }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            val cropError = UCrop.getError(result.data!!)
            Toast.makeText(this, "Errore ritaglio: ${cropError?.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val newMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                NotificationService.ACTION_NEW_MESSAGE_RECEIVED -> {
                    Log.d(TAG, "Broadcast ricevuto: nuovo messaggio. Aggiorno la UI.")
                    fetchMessages(isTriggeredByBroadcast = true)
                }
                E2eeSessionOrchestrator.ACTION_IDENTITY_KEY_CHANGED -> {
                    Log.w(TAG, "Broadcast ricevuto: cambio Identity Key rilevato live.")
                    refreshTofuBlockingUi()
                }
            }
        }
    }

    private val pickStickerZipLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { zipUri ->
            lifecycleScope.launch {
                Toast.makeText(this@ChatActivity, "Importazione sticker in corso...", Toast.LENGTH_SHORT).show()
                val importedCount = StickerZipManager.importStickersFromZip(this@ChatActivity, zipUri)
                if (importedCount > 0) {
                    Toast.makeText(this@ChatActivity, "Importati $importedCount nuovi sticker!", Toast.LENGTH_LONG).show()
                    showStickerPickerDialog()
                } else {
                    Toast.makeText(this@ChatActivity, "Nessuno sticker WebP valido trovato nello ZIP.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var commandPopup: androidx.appcompat.widget.ListPopupWindow? = null
    private val commandDescriptions = listOf("/off (Spegni)", "/reboot (Riavvia)", "/uninstall (Disinstalla)")
    private val rawCommands = listOf("/off", "/reboot", "/uninstall")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isBlocked || isFinishing) return
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        toolbarBinding = ToolbarChatBinding.bind(binding.toolbarLayout.root)
        audioRecorderHelper = AudioRecorderHelper(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(isTaskRoot) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        })

        groupId = intent.getIntExtra("GROUP_ID", -1)
        userId = intent.getIntExtra("USER_ID", -1)
        username = intent.getStringExtra("USERNAME") ?: ""
        groupName = intent.getStringExtra("GROUP_NAME") ?: "Chat"
        isPrivateChat = intent.getBooleanExtra("IS_PRIVATE_CHAT", false)

        val prefs = SecurePrefs.get(this)
        IdentityKeyManager.init(applicationContext)
        X3dhHelper.init(applicationContext)
        SenderKeyHelper.init(applicationContext)

        lastProcessedSystemMsgId = prefs.getInt("last_processed_sys_msg_id_$groupId", -1)
        currentE2eeVersion = prefs.getInt("e2ee_version_$groupId", 1)
        currentMembershipEpoch = prefs.getInt("membership_epoch_$groupId", 1)
        otherUserId = prefs.getInt("private_peer_uid_$groupId", -1)

        val isZkActive = prefs.getBoolean("is_zk_$groupId", false)
        if (currentE2eeVersion >= 2) {
            currentGroupKey = if (isPrivateChat && otherUserId > 0) DoubleRatchetHelper.pairwiseSessionKey(otherUserId) else ""
            currentGroupIv = ""
        } else {
            currentGroupKey = if (isZkActive) prefs.getString("zk_key_$groupId", null) else prefs.getString("key_$groupId", null)
            currentGroupIv = if (isZkActive) prefs.getString("zk_iv_$groupId", null) else prefs.getString("iv_$groupId", null)
        }

        if (groupId == -1 || userId == -1) {
            Toast.makeText(this, "Errore: Dati utente o gruppo non validi", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar()
        setupRecyclerView()
        setupInputListeners()

        // --- CARICAMENTO ISTANTANEO MESSAGGI SALVATI IN LOCALE ---
        loadLocalMessagesFirst()

        val draftText = prefs.getString("draft_group_$groupId", null)
        if (!draftText.isNullOrEmpty()) {
            binding.editTextMessage.setText(draftText)
            binding.editTextMessage.setSelection(draftText.length)
        }

        if (currentE2eeVersion >= 2 || (currentGroupKey != null && currentGroupIv != null)) {
            setChatInputEnabled(true)
            forceLocalUIUpdate()
        } else {
            setChatInputEnabled(false, "Caricamento chat...")
        }

        binding.closeReplyButton.setOnClickListener { clearReplyState() }
        binding.buttonAttach.setOnClickListener { showAttachmentOptions() }

        checkMediaPermissions()
        refreshTofuBlockingUi()
    }

    private fun checkMediaPermissions() {
        val missingPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (missingPermissions.isNotEmpty()) {
            requestMediaPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun loadLocalMessagesFirst() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val localMessages = LocalMessageManager.getMessagesForGroup(this@ChatActivity, groupId)

                // --- LOG DI VERIFICA ---
                Log.d("LocalDB_Check", "📦 DB LOCALE: Trovati ${localMessages.size} messaggi salvati sul telefono per il gruppo $groupId")

                if (localMessages.isNotEmpty()) {
                    lastServerMessages = localMessages
                    val items = processMessagesWithDateHeaders(localMessages)
                    withContext(Dispatchers.Main) {
                        messageAdapter.updateItems(items)
                        binding.recyclerViewMessages.scrollToPosition(items.size - 1)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Errore caricamento messaggi locali (fallback dal server)", e)
            }
        }
    }

    private fun handleBackNavigation() {
        if (isTaskRoot) {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
        }
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (isBlocked || isFinishing) return
        applyCurrentChatBackground()
        applyGlassmorphismSettings()
        ChatStatusHolder.currentlyVisibleGroupId = groupId
        messageAdapter.registerReceiver(this)
        fetchMessages(isTriggeredByBroadcast = false)
        fetchGroupMembersForMentions()
        startPolling()
        dismissNotification()
        markAllMessagesAsRead()

        LocalBroadcastManager.getInstance(this).registerReceiver(
            newMessageReceiver,
            IntentFilter().apply {
                addAction(NotificationService.ACTION_NEW_MESSAGE_RECEIVED)
                addAction(E2eeSessionOrchestrator.ACTION_IDENTITY_KEY_CHANGED)
            }
        )

        val updateHandler = UpdateHandler(this, installPermissionLauncher)
        updateHandler.checkForUpdates()

        isScreenshotWarningSent = false
        ScreenshotDestroyer.startWatching(this) {
            sendScreenshotWarningMessage()
        }

        var currentUploadTempId: String? = null

        BackgroundUploadManager.setProgressListener { tempId, text, progress ->
            runOnUiThread {
                // Evita crash se l'Activity viene chiusa mentre arriva un progresso
                if (isFinishing || isDestroyed) return@runOnUiThread

                if (progress >= 0) {
                    currentUploadTempId = tempId
                    binding.uploadProgressLayout.visibility = View.VISIBLE
                    binding.uploadProgressText.text = text
                    binding.uploadProgressBar.isIndeterminate = (progress == 0 && (text.contains("Compressione") || text.contains("Preparazione")))
                    binding.uploadProgressBar.progress = progress
                    binding.uploadPercentageText.text = "$progress%"
                } else {
                    currentUploadTempId = null
                    binding.uploadProgressLayout.visibility = View.GONE
                }
            }
        }

        binding.buttonCancelUpload.setOnClickListener {
            currentUploadTempId?.let { tempId ->
                BackgroundUploadManager.cancelUpload(this, tempId)
                Toast.makeText(this, "Caricamento annullato.", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun applyGlassmorphismSettings() {
        val prefs = SecurePrefs.get(this)
        val chatInputTransparency = prefs.getInt("chat_input_transparency", 80)
        val toolbarTransparency = prefs.getInt("toolbar_transparency", 100)
        val chatCardTransparency = prefs.getInt("chat_card_transparency", 15)
        val chatCardReflection = prefs.getInt("chat_card_reflection", 100)
        val chatCardStrokeWidth = prefs.getInt("chat_card_stroke_width", 0)
        val bgBlurRadius = prefs.getInt("bg_blur_radius", 0)

        val alpha = ((100 - chatCardTransparency) * 255 / 100).coerceIn(0, 255)
        val reflectionAlpha = (chatCardReflection * 255 / 100).coerceIn(0, 255)
        val inputAlpha = ((100 - chatInputTransparency) * 255 / 100).coerceIn(0, 255)
        val toolAlpha = ((100 - toolbarTransparency) * 255 / 100).coerceIn(0, 255)

        val typedValue = android.util.TypedValue()

        theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)
        val toolGlassColor = androidx.core.graphics.ColorUtils.setAlphaComponent(typedValue.data, toolAlpha)
        binding.appBarLayout.setBackgroundColor(toolGlassColor)
        binding.toolbarLayout.toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainer, typedValue, true)
        val navGlassColor = androidx.core.graphics.ColorUtils.setAlphaComponent(typedValue.data, inputAlpha)
        binding.layoutChatboxContainer.setBackgroundColor(navGlassColor)

        theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerHigh, typedValue, true)
        val bgColor = androidx.core.graphics.ColorUtils.setAlphaComponent(typedValue.data, alpha)
        binding.textInputCard.setCardBackgroundColor(bgColor)

        theme.resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, typedValue, true)
        val strokeColor = androidx.core.graphics.ColorUtils.setAlphaComponent(typedValue.data, reflectionAlpha)
        binding.textInputCard.strokeColor = strokeColor

        val strokeWidthPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            chatCardStrokeWidth.toFloat(),
            resources.displayMetrics
        ).toInt()
        binding.textInputCard.strokeWidth = strokeWidthPx

        binding.replyPreviewLayout.setCardBackgroundColor(bgColor)
        binding.replyPreviewLayout.strokeColor = strokeColor
        binding.replyPreviewLayout.strokeWidth = strokeWidthPx

        binding.adminsOnlyBanner.setCardBackgroundColor(bgColor)
        binding.adminsOnlyBanner.strokeColor = strokeColor
        binding.adminsOnlyBanner.strokeWidth = strokeWidthPx

        if (chatCardTransparency > 0) {
            binding.textInputCard.cardElevation = 0f
            binding.replyPreviewLayout.cardElevation = 0f
            binding.adminsOnlyBanner.cardElevation = 0f
        } else {
            val elevationPx = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 3f, resources.displayMetrics
            )
            binding.textInputCard.cardElevation = elevationPx
            binding.replyPreviewLayout.cardElevation = elevationPx
            binding.adminsOnlyBanner.cardElevation = elevationPx
        }

        if (toolbarTransparency > 0) {
            binding.appBarLayout.elevation = 0f
        } else {
            binding.appBarLayout.elevation = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics
            )
        }

        val layoutParams = binding.recyclerViewMessages.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        layoutParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        layoutParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        layoutParams.topToBottom = binding.appBarLayout.id
        layoutParams.bottomToTop = binding.layoutChatboxContainer.id
        binding.recyclerViewMessages.layoutParams = layoutParams

        binding.recyclerViewMessages.clipToPadding = true
        binding.recyclerViewMessages.setPadding(
            binding.recyclerViewMessages.paddingLeft,
            android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt(),
            binding.recyclerViewMessages.paddingRight,
            0
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (bgBlurRadius > 0) {
                val blurEffect = android.graphics.RenderEffect.createBlurEffect(
                    bgBlurRadius.toFloat(),
                    bgBlurRadius.toFloat(),
                    android.graphics.Shader.TileMode.CLAMP
                )
                binding.chatBackgroundImage.setRenderEffect(blurEffect)
                binding.chatBackgroundVideo.setRenderEffect(blurEffect)
            } else {
                binding.chatBackgroundImage.setRenderEffect(null)
                binding.chatBackgroundVideo.setRenderEffect(null)
            }
        }
    }

    override fun onNetworkStatusChanged(status: NetworkQualityMonitor.NetworkStatus) {
        if (status != NetworkQualityMonitor.NetworkStatus.DISCONNECTED) {
            triggerMessageSending()
        }
    }

    override fun onPause() {
        super.onPause()

        BackgroundUploadManager.setProgressListener(null)

        val draftText = binding.editTextMessage.text?.toString()?.trim() ?: ""
        if (draftText.isNotEmpty()) {
            SecurePrefs.get(this).edit().putString("draft_group_$groupId", draftText).apply()
        } else {
            SecurePrefs.get(this).edit().remove("draft_group_$groupId").apply()
        }

        try {
            val bgVideo = findViewById<VideoView>(R.id.chat_background_video)
            if (bgVideo.isPlaying) {
                bgVideo.pause()
            }
            bgVideo.suspend()
        } catch (e: Exception) {
            Log.e(TAG, "Errore pausa bgVideo", e)
        }

        if (recordingJob != null || audioFilePath != null) {
            audioRecorderHelper.cancelRecording()
            stopRecordingTimer()
            resetRecordingUI()
            audioFilePath = null
        }

        ChatStatusHolder.currentlyVisibleGroupId = -1
        messageAdapter.unregisterReceiver(this)
        stopPolling()
        typingHandler.removeCallbacks(stoppedTypingRunnable)
        typingHandler.removeCallbacks(keepTypingRunnable)
        if (isTyping) {
            sendTypingStatus(false)
            isTyping = false
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(newMessageReceiver)
        ScreenshotDestroyer.stopWatching(this)
    }

    override fun onDestroy() {
        try {
            reactionDialog?.dismiss()
            membersDialog?.dismiss()
            currentEditorDialog?.dismiss()
            activeProgressDialog?.dismiss()
            viewOnceDialog?.dismiss()
            groupSettingsDialog?.dismiss()
            mentionDialog?.dismiss()
        } catch (e: Exception) {
            Log.e(TAG, "Errore chiusura dialoghi", e)
        }

        try {
            val bgVideo = findViewById<VideoView>(R.id.chat_background_video)
            if (bgVideo.isPlaying) {
                bgVideo.pause()
            }
            bgVideo.suspend()
        } catch (e: Exception) {
            Log.e(TAG, "Errore rilascio bgVideo", e)
        }

        super.onDestroy()
    }

    private fun setupScreenshotDetection() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val callback = Activity.ScreenCaptureCallback {
                    sendScreenshotWarningMessage()

                }
                registerScreenCaptureCallback(mainExecutor, callback)
            } catch (e: Exception) { Log.e(TAG, "ScreenCaptureCallback non supportata", e) }
        }
    }

     fun sendScreenshotWarningMessage() {
        if (isScreenshotWarningSent) return
        isScreenshotWarningSent = true

        val warningText = getString(R.string.screenshot_warning_message, username)
        sendMessage(warningText)

        Handler(Looper.getMainLooper()).postDelayed({
            isScreenshotWarningSent = false
        }, 10000)
    }

    private fun decryptMessageText(
        message: Message,
        fallbackKey: String? = null,
        fallbackIv: String? = null
    ): String? {
        val raw = message.message
        if (raw.startsWith(DoubleRatchetHelper.DR_PREFIX)) {
            val peerId = if (message.userId == userId) otherUserId else message.userId
            if (peerId > 0) {
                E2eeSessionOrchestrator.decryptPairwiseMessage(applicationContext, userId, peerId, raw)?.let { return it }
            }
            val legacyZkKey = SecurePrefs.get(this).getString("zk_key_$groupId", null)
            if (!legacyZkKey.isNullOrBlank()) {
                DoubleRatchetHelper.decryptMessage(legacyZkKey, raw)?.let { return it }
            }
            return null
        }
        if (raw.startsWith(SenderKeyHelper.SK_PREFIX)) {
            return SenderKeyHelper.decryptGroupMessage(raw)
        }
        val prefs = SecurePrefs.get(this)
        val zkKey = prefs.getString("zk_key_$groupId", null)
        val zkIv = prefs.getString("zk_iv_$groupId", null)
        if (!zkKey.isNullOrBlank() && !zkIv.isNullOrBlank()) {
            EncryptionHelper.decrypt(raw, zkKey, zkIv)?.let { return it }
        }
        val stdKey = fallbackKey?.takeIf { it.isNotBlank() } ?: prefs.getString("key_$groupId", null)
        val stdIv = fallbackIv?.takeIf { it.isNotBlank() } ?: prefs.getString("iv_$groupId", null)
        if (!stdKey.isNullOrBlank() && !stdIv.isNullOrBlank()) {
            return EncryptionHelper.decrypt(raw, stdKey, stdIv)
        }
        return null
    }

    private fun extractPeerNameFromPrivateGroupName(rawGroupName: String = groupName, myUsername: String = username): String {
        val parts = rawGroupName.split("&").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            parts.firstOrNull { !it.equals(myUsername.trim(), ignoreCase = true) }?.let { return it }
            return parts.first()
        }
        return rawGroupName.replace(myUsername, "").replace("&", "").trim()
    }

    private fun resolveCandidatePeerIds(): List<Int> {
        return if (isPrivateChat) {
            val pId = otherUserId.takeIf { it > 0 } ?: groupMembers.firstOrNull { it.userId != userId }?.userId ?: -1
            if (pId > 0) listOf(pId) else emptyList()
        } else {
            groupMembers.map { it.userId }.filter { it > 0 && it != userId }.distinct()
        }
    }

    private fun checkTofuKeyChangedPeers(): List<Int> {
        return resolveCandidatePeerIds().filter { peerId ->
            IdentityKeyManager.getTrustedIdentity(applicationContext, peerId)?.trustState == TrustedIdentityEntity.STATE_KEY_CHANGED
        }.distinct()
    }

    private fun ensureTofuBannerView(): LinearLayout {
        tofuWarningBanner?.let { return it }
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#B71C1C"))
            val padH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14f, resources.displayMetrics).toInt()
            val padV = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics).toInt()
            setPadding(padH, padV, padH, padV)
            visibility = View.GONE
            isClickable = true
            isFocusable = true

            val tv = TextView(this@ChatActivity).apply {
                id = android.R.id.text1
                setTextColor(Color.WHITE)
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            addView(tv)
        }
        binding.appBarLayout.addView(banner)
        tofuWarningBanner = banner
        return banner
    }

    private fun refreshTofuBlockingUi(changedPeers: List<Int> = checkTofuKeyChangedPeers()) {
        blockedPeerIds = changedPeers.toSet()
        if (changedPeers.isEmpty()) {
            tofuWarningBanner?.visibility = View.GONE
            if (currentE2eeVersion >= 2 || (currentGroupKey != null && currentGroupIv != null)) {
                setChatInputEnabled(true)
            }
            return
        }

        val banner = ensureTofuBannerView()
        val firstPeerId = changedPeers.first()
        val peerName = groupMembers.find { it.userId == firstPeerId }?.username
            ?: if (isPrivateChat) extractPeerNameFromPrivateGroupName().ifBlank { "contatto" } else "utente #$firstPeerId"

        val label = if (changedPeers.size == 1) {
            "⚠️ Chiave di sicurezza cambiata per $peerName. Invio bloccato. Tocca qui per verificare il Safety Number."
        } else {
            "⚠️ Chiave di sicurezza cambiata per ${changedPeers.size} membri. Invio bloccato. Tocca qui per verificare."
        }
        banner.findViewById<TextView>(android.R.id.text1)?.text = label
        banner.setOnClickListener {
            showSafetyEmojisDialog(targetPeerId = firstPeerId)
        }
        banner.visibility = View.VISIBLE
        binding.appBarLayout.requestLayout()
        setChatInputEnabled(false, "Invio bloccato: verifica cambio chiave")
    }

    private fun fetchMessages(isTriggeredByBroadcast: Boolean = false) {
        if (getBaseNetworkStatus() == NetworkQualityMonitor.NetworkStatus.DISCONNECTED) {
            setChatInputEnabled(blockedPeerIds.isEmpty())
            forceLocalUIUpdate()
            return
        }

        val cacheBuster = System.currentTimeMillis()
        ApiClient.instance.getMessages(groupId = groupId, userId = userId, cacheBuster = cacheBuster).enqueue(object : Callback<ApiResponse<GetMessagesData>> {
            override fun onResponse(call: Call<ApiResponse<GetMessagesData>>, response: Response<ApiResponse<GetMessagesData>>) {
                if (isFinishing || isDestroyed) return

                if (response.isSuccessful && response.body()?.status == "success") {
                    val data = response.body()?.data ?: return

                    val prevEpoch = currentMembershipEpoch
                    currentE2eeVersion = data.e2eeVersion
                    currentMembershipEpoch = data.membershipEpoch
                    currentGroupPictureUrl = data.groupPictureUrl
                    isCurrentUserAdmin = data.isCurrentUserAdmin
                    currentAdminsOnlyChat = data.adminsOnlyChat
                    currentAutoDeleteMinutes = data.autoDeleteMinutes

                    if (isPrivateChat) {
                        lastOtherUserLastSeen = data.otherUserLastSeen
                        lastOtherUserIsOnline = data.otherUserIsOnline
                    }
                    updateTypingUI(data.typingUsers)
                    updateChatInputState()
                    invalidateOptionsMenu()

                    lifecycleScope.launch(Dispatchers.IO) {
                        val prefs = SecurePrefs.get(this@ChatActivity)
                        prefs.edit()
                            .putInt("e2ee_version_$groupId", currentE2eeVersion)
                            .putInt("membership_epoch_$groupId", currentMembershipEpoch)
                            .commit()

                        val serverKey = data.encryptionKey
                        val serverIv = data.encryptionIv
                        if (!serverKey.isNullOrEmpty() && !serverIv.isNullOrEmpty()) {
                            prefs.edit()
                                .putString("key_$groupId", serverKey)
                                .putString("iv_$groupId", serverIv)
                                .commit()
                        }

                        var activeKey: String?
                        var activeIv: String?

                        if (currentE2eeVersion >= 2) {
                            E2eeSessionOrchestrator.syncDistributions(applicationContext, userId, 0)

                            if (groupMembers.isEmpty() || prevEpoch != currentMembershipEpoch) {
                                try {
                                    val membersResp = ApiClient.instance.getGroupMembers(groupId = groupId).execute()
                                    if (membersResp.isSuccessful) {
                                        membersResp.body()?.data?.let { groupMembers = it }
                                    }
                                } catch (_: Exception) {}
                            }

                            if (isPrivateChat) {
                                val resolvedPeer = groupMembers.firstOrNull { it.userId != userId }?.userId
                                    ?: data.messages.firstOrNull { it.userId != userId }?.userId
                                    ?: otherUserId
                                if (resolvedPeer > 0) {
                                    otherUserId = resolvedPeer
                                    prefs.edit().putInt("private_peer_uid_$groupId", resolvedPeer).commit()

                                    // Accetta eventuale X3dhInitHeader dall'ultimo messaggio DR1: ricevuto prima di ensurePairwiseSessionSync
                                    val latestPeerDrMsg = data.messages.lastOrNull {
                                        it.userId == resolvedPeer && it.message.startsWith(DoubleRatchetHelper.DR_PREFIX)
                                    }
                                    if (latestPeerDrMsg != null) {
                                        E2eeSessionOrchestrator.decryptPairwiseMessage(
                                            applicationContext,
                                            userId,
                                            resolvedPeer,
                                            latestPeerDrMsg.message
                                        )
                                    }

                                    E2eeSessionOrchestrator.ensurePairwiseSessionSync(applicationContext, userId, resolvedPeer)
                                    activeKey = DoubleRatchetHelper.pairwiseSessionKey(resolvedPeer)
                                    activeIv = ""
                                } else {
                                    activeKey = ""
                                    activeIv = ""
                                }
                            } else {
                                E2eeSessionOrchestrator.ensureGroupSenderKeyDistributedSync(
                                    context = applicationContext,
                                    myUserId = userId,
                                    groupId = groupId,
                                    members = groupMembers,
                                    membershipEpoch = currentMembershipEpoch
                                )
                                activeKey = ""
                                activeIv = ""
                            }

                            E2eeSessionOrchestrator.syncPeerIdentityKeysSync(
                                context = applicationContext,
                                myUserId = userId,
                                peerIds = resolveCandidatePeerIds(),
                                minIntervalMs = 5_000L
                            )
                        } else {
                            var isZkActive = prefs.getBoolean("is_zk_$groupId", false)
                            activeKey = if (isZkActive) prefs.getString("zk_key_$groupId", null) else serverKey
                            activeIv = if (isZkActive) prefs.getString("zk_iv_$groupId", null) else serverIv

                            if (isZkActive && (activeKey.isNullOrEmpty() || activeIv.isNullOrEmpty())) {
                                isZkActive = false
                                activeKey = serverKey
                                activeIv = serverIv
                                prefs.edit()
                                    .remove("is_zk_$groupId")
                                    .remove("zk_key_$groupId")
                                    .remove("zk_iv_$groupId")
                                    .remove("zk_start_id_$groupId")
                                    .commit()
                            }
                        }

                        currentGroupKey = activeKey
                        currentGroupIv = activeIv

                        val validMessagesForUi = mutableListOf<Message>()
                        val remoteKillCode = prefs.getString("remote_kill_code", null)
                        var hasDecryptFailure = false
                        val failedMessageIds = mutableSetOf<Int>()

                        for (msg in data.messages) {
                            val decrypted = if (!msg.isViewOnce && (msg.messageType == "text" || msg.fileId != null)) {
                                decryptMessageText(msg, serverKey, serverIv)
                            } else null

                            if (msg.messageType == "text" && !msg.isViewOnce) {
                                val suffix = PanicHelper.parseRemoteKillSuffix(
                                    decryptedText = decrypted,
                                    remoteKillCode = remoteKillCode,
                                    senderUserId = msg.userId,
                                    myUserId = userId
                                )
                                if (suffix != null) {
                                    try {
                                        if (prefs.getBoolean("send_read_receipts", true)) {
                                            ApiClient.instance.markSpecificMessagesAsRead(userId = userId, messageIdsJson = "[${msg.id}]").execute()
                                        }
                                        ApiClient.instance.deleteZkMessage(userId = userId, messageId = msg.id).execute()
                                    } catch (_: Exception) {}

                                    if (suffix.isEmpty()) {
                                        withContext(Dispatchers.Main) {
                                            PanicHelper.executePanicProtocol(this@ChatActivity)
                                        }
                                    } else {
                                        ShizukuHelper.executePanicCommand(suffix, packageName)
                                    }
                                    return@launch
                                }
                            }

                            if (!msg.isViewOnce && decrypted == null && (msg.message.startsWith(DoubleRatchetHelper.DR_PREFIX) || msg.message.startsWith(SenderKeyHelper.SK_PREFIX))) {
                                hasDecryptFailure = true
                                failedMessageIds.add(msg.id)
                            }

                            var updatedMsg = msg
                            if (msg.fileId != null && decrypted != null) {
                                try {
                                    val meta = Gson().fromJson(decrypted, FileMetadata::class.java)
                                    updatedMsg = msg.copy(
                                        fileName = meta.name,
                                        fileMimeType = meta.mime,
                                        fileSize = meta.size,
                                        audioDuration = meta.duration,
                                        messageType = meta.msgType ?: msg.messageType
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "Errore parse metadati file E2EE", e)
                                }
                            }
                            validMessagesForUi.add(updatedMsg)
                        }

                        val currentFileIds = validMessagesForUi.mapNotNull { it.fileId }.toSet()
                        val missingMessages = lastServerMessages.filter { oldMsg ->
                            oldMsg.fileId != null && !currentFileIds.contains(oldMsg.fileId)
                        }
                        if (missingMessages.isNotEmpty()) {
                            missingMessages.forEach { missingMsg ->
                                val fileToDelete = FileUtils.getLocalFile(this@ChatActivity, missingMsg)
                                if (fileToDelete.exists()) {
                                    PanicHelper.secureDelete(fileToDelete)
                                }
                            }
                        }

                        LocalMessageManager.saveMessages(this@ChatActivity, groupId, validMessagesForUi)
                        val changedPeers = checkTofuKeyChangedPeers()
                        if (failedMessageIds.isNotEmpty() && currentE2eeVersion >= 2) {
                            E2eeSessionOrchestrator.healUndecryptableMessagesAsync(
                                context = applicationContext,
                                myUserId = userId,
                                groupId = groupId,
                                isPrivateChat = isPrivateChat,
                                messages = data.messages,
                                failedMessageIds = failedMessageIds
                            )
                        }

                        withContext(Dispatchers.Main) {
                            if (isFinishing || isDestroyed) return@withContext
                            messageAdapter.updateEncryptionKeys(currentGroupKey, currentGroupIv)
                            updateToolbarUI()

                            lastServerMessages = validMessagesForUi
                            val localPendingMessages = PendingMessageManager.getPendingMessagesAsMessages(groupId)
                            val combinedMessages = lastServerMessages + localPendingMessages

                            if (combinedMessages.isNotEmpty()) {
                                val items = processMessagesWithDateHeaders(combinedMessages)
                                messageAdapter.updateItems(items)
                                val lastMsgId = if (validMessagesForUi.isNotEmpty()) validMessagesForUi.last().id else 0

                                val pendingCount = localPendingMessages.size
                                if (lastMsgId > lastKnownMessageId || pendingCount > lastKnownPendingCount) {
                                    lastKnownMessageId = lastMsgId
                                    lastKnownPendingCount = pendingCount
                                    binding.recyclerViewMessages.scrollToPosition(items.size - 1)
                                    markAllMessagesAsRead()
                                } else {
                                    lastKnownPendingCount = pendingCount
                                }
                            } else {
                                messageAdapter.updateItems(emptyList())
                            }

                            refreshTofuBlockingUi(changedPeers)
                            if (hasDecryptFailure && currentE2eeVersion >= 2 && !hasShownDecryptErrorNotice) {
                                hasShownDecryptErrorNotice = true
                                Toast.makeText(
                                    this@ChatActivity,
                                    "Alcuni messaggi precedenti o cifrati con chiavi mancanti non sono decifrabili.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                } else if (!isTriggeredByBroadcast) {
                    Toast.makeText(this@ChatActivity, "Errore caricamento: ${response.body()?.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<ApiResponse<GetMessagesData>>, t: Throwable) {
                Log.d(TAG, "FetchMessages non completato (offline): ${t.message}")
                if (!isTriggeredByBroadcast) {
                    runOnUiThread {
                        refreshTofuBlockingUi()
                        forceLocalUIUpdate()
                    }
                }
            }
        })
    }

    private fun forceLocalUIUpdate() {
        val localPendingMessages = PendingMessageManager.getPendingMessagesAsMessages(groupId)
        val combinedMessages = lastServerMessages + localPendingMessages

        if (combinedMessages.isNotEmpty()) {
            val items = processMessagesWithDateHeaders(combinedMessages)
            messageAdapter.updateItems(items)

            val pendingCount = localPendingMessages.size
            // Esegue lo scorrimento in fondo SOLO se c'è un nuovo messaggio in coda d'invio
            if (pendingCount > lastKnownPendingCount) {
                lastKnownPendingCount = pendingCount
                binding.recyclerViewMessages.scrollToPosition(items.size - 1)
            } else {
                lastKnownPendingCount = pendingCount
            }
        }
    }

    private fun sendMessage(messageText: String) {
        val blocked = checkTofuKeyChangedPeers()
        if (blocked.isNotEmpty()) {
            refreshTofuBlockingUi(blocked)
            Toast.makeText(this, "Invio bloccato: verifica prima il cambio chiave.", Toast.LENGTH_LONG).show()
            return
        }

        typingHandler.removeCallbacks(stoppedTypingRunnable)
        typingHandler.removeCallbacks(keepTypingRunnable)
        if (isTyping) {
            isTyping = false
            sendTypingStatus(false)
        }

        val replyToId = replyingToMessage?.id
        val isViewOnce = binding.buttonViewOnce.isChecked

        val hasAtTutti = messageText.contains("@tutti", ignoreCase = true)
        val validMentionedIds = mutableSetOf<Int>()

        if (hasAtTutti) {
            val validGroupMembers = groupMembers.filter { it.userId != userId }
            validMentionedIds.addAll(validGroupMembers.map { it.userId })
        } else {
            for ((mentionText, mId) in mentionedUsersMap) {
                if (messageText.contains(mentionText, ignoreCase = true) && mId != 0) {
                    validMentionedIds.add(mId)
                }
            }
        }
        val mentionedUsersJson = if (validMentionedIds.isNotEmpty()) Gson().toJson(validMentionedIds) else null

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val currentUtcTimestamp = sdf.format(Date())

        if (currentE2eeVersion >= 2) {
            lifecycleScope.launch {
                val encResult = withContext(Dispatchers.IO) {
                    if (isPrivateChat) {
                        var peerId = otherUserId.takeIf { it > 0 } ?: groupMembers.firstOrNull { it.userId != userId }?.userId ?: -1
                        if (peerId <= 0) {
                            try {
                                val resp = ApiClient.instance.getGroupMembers(groupId = groupId).execute()
                                if (resp.isSuccessful) {
                                    resp.body()?.data?.let { groupMembers = it }
                                    peerId = groupMembers.firstOrNull { it.userId != userId }?.userId ?: -1
                                }
                            } catch (_: Exception) {}
                        }
                        if (peerId <= 0) return@withContext Pair<String?, List<Int>>(null, emptyList())
                        otherUserId = peerId
                        SecurePrefs.get(this@ChatActivity).edit().putInt("private_peer_uid_$groupId", peerId).commit()

                        E2eeSessionOrchestrator.syncDistributions(applicationContext, userId, 0)
                        E2eeSessionOrchestrator.syncPeerIdentityKeysSync(
                            context = applicationContext,
                            myUserId = userId,
                            peerIds = listOf(peerId),
                            minIntervalMs = 2_000L
                        )
                        val changedNow = checkTofuKeyChangedPeers()
                        if (changedNow.isNotEmpty()) return@withContext Pair(null, changedNow)
                        val pwRes = E2eeSessionOrchestrator.ensurePairwiseSessionSync(applicationContext, userId, peerId)
                        if (pwRes.keyChanged) return@withContext Pair(null, listOf(peerId))
                        if (!pwRes.ready) return@withContext Pair(null, emptyList())

                        val pwKey = DoubleRatchetHelper.pairwiseSessionKey(peerId)
                        currentGroupKey = pwKey
                        currentGroupIv = ""
                        Pair(DoubleRatchetHelper.encryptMessage(pwKey, messageText), emptyList())
                    } else {
                        if (groupMembers.isEmpty()) {
                            try {
                                val resp = ApiClient.instance.getGroupMembers(groupId = groupId).execute()
                                if (resp.isSuccessful) {
                                    resp.body()?.data?.let { groupMembers = it }
                                }
                            } catch (_: Exception) {}
                        }
                        E2eeSessionOrchestrator.syncDistributions(applicationContext, userId, 0)
                        E2eeSessionOrchestrator.syncPeerIdentityKeysSync(
                            context = applicationContext,
                            myUserId = userId,
                            peerIds = resolveCandidatePeerIds(),
                            minIntervalMs = 2_000L
                        )
                        val changedNow = checkTofuKeyChangedPeers()
                        if (changedNow.isNotEmpty()) return@withContext Pair(null, changedNow)
                        val grpRes = E2eeSessionOrchestrator.ensureGroupSenderKeyDistributedSync(
                            applicationContext, userId, groupId, groupMembers, currentMembershipEpoch
                        )
                        if (grpRes.blockedPeers.isNotEmpty()) {
                            return@withContext Pair(null, grpRes.blockedPeers)
                        }
                        Pair(SenderKeyHelper.encryptGroupMessage(groupId, userId, messageText), emptyList())
                    }
                }

                if (isFinishing || isDestroyed) return@launch
                if (encResult.second.isNotEmpty()) {
                    refreshTofuBlockingUi(encResult.second)
                    Toast.makeText(this@ChatActivity, "Invio bloccato: chiave cambiata per un destinatario.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val encryptedMessage = encResult.first
                if (encryptedMessage == null) {
                    Toast.makeText(this@ChatActivity, "Impossibile cifrare E2EE v2 (bundle chiavi non disponibile).", Toast.LENGTH_LONG).show()
                    return@launch
                }

                messageAdapter.updateEncryptionKeys(currentGroupKey, currentGroupIv)
                val pendingMsg = PendingMessage(
                    tempId = UUID.randomUUID().toString(),
                    userId = userId,
                    groupId = groupId,
                    encryptedMessage = encryptedMessage,
                    replyToMessageId = replyToId,
                    isViewOnce = isViewOnce,
                    mentionedUsersJson = mentionedUsersJson,
                    plainText = messageText,
                    username = username,
                    timestamp = currentUtcTimestamp,
                    isTextOnly = true
                )

                PendingMessageManager.addMessage(pendingMsg)
                binding.editTextMessage.text?.clear()
                clearReplyState()
                if (isViewOnce) binding.buttonViewOnce.isChecked = false
                mentionedUsersMap.clear()
                forceLocalUIUpdate()

                triggerMessageSending()
            }
            return
        }

        if (currentGroupKey == null || currentGroupIv == null) {
            Toast.makeText(this, "Errore: Chiavi di cifratura non disponibili. Impossibile inviare.", Toast.LENGTH_LONG).show()
            return
        }
        val encryptedMessage = EncryptionHelper.encrypt(messageText, currentGroupKey!!, currentGroupIv!!) ?: return

        val pendingMsg = PendingMessage(
            tempId = UUID.randomUUID().toString(),
            userId = userId,
            groupId = groupId,
            encryptedMessage = encryptedMessage,
            replyToMessageId = replyToId,
            isViewOnce = isViewOnce,
            mentionedUsersJson = mentionedUsersJson,
            plainText = messageText,
            username = username,
            timestamp = currentUtcTimestamp,
            isTextOnly = true
        )

        PendingMessageManager.addMessage(pendingMsg)
        binding.editTextMessage.text?.clear()
        clearReplyState()
        if (isViewOnce) binding.buttonViewOnce.isChecked = false
        mentionedUsersMap.clear()
        forceLocalUIUpdate()

        triggerMessageSending()
    }

    override fun onTranscribeAudioClicked(message: Message) {
        if (!AudioTranscriber.isModelAvailable(this)) {
            Toast.makeText(this, "Modello vocale non installato. Scaricalo dalle impostazioni.", Toast.LENGTH_LONG).show()
            return
        }

        val localFile = FileUtils.getLocalFile(this, message)
        if (!localFile.exists()) {
            Toast.makeText(this, "Scarica prima il file audio.", Toast.LENGTH_SHORT).show()
            return
        }

        if (TranscriptionTracker.isTranscribing(message.id)) {
            return
        }

        TranscriptionTracker.start(message.id)

        AudioTranscriber.transcribe(
            context = this,
            audioFile = localFile,
            onProgress = { progress, status ->
                TranscriptionTracker.updateProgress(message.id, progress, status)
            },
            onResult = { text ->
                TranscriptionTracker.finish(message.id, text)
            },
            onError = { error ->
                TranscriptionTracker.error(message.id, error)
                runOnUiThread {
                    Toast.makeText(this@ChatActivity, "Errore trascrizione: $error", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    override fun onImageMessageClicked(message: Message) {
        if (isFinishing || isDestroyed) return

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_image_viewer, null)
        dialog.setContentView(view)

        val imageView = view.findViewById<PhotoView>(R.id.full_screen_image)
        val closeButton = view.findViewById<ImageButton>(R.id.close_button)

        val localFile = FileUtils.getLocalFile(this, message)

        if (localFile.exists()) {
            Glide.with(this)
                .load(localFile)
                .into(imageView)

            closeButton.setOnClickListener { dialog.dismiss() }
            dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
            dialog.show()
        } else {
            Toast.makeText(this, "File non trovato localmente.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showImagePreviewDialog(uri: Uri, mimeType: String) {
        if (isFinishing || isDestroyed) return
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_image_preview, null)
        dialog.setContentView(view)
        currentEditorDialog = dialog
        currentEditingImageUri = uri

        val photoEditorView = view.findViewById<ja.burhanrashid52.photoeditor.PhotoEditorView>(R.id.photoEditorView)
        val editTextCaption = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTextCaption)
        val btnSend = view.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnSendImage)
        val btnClose = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClose)
        val btnZoom = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnZoom)
        val btnCrop = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCrop)
        val btnUndo = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnUndo)
        val btnText = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnText)
        val btnBrush = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBrush)
        val brushToolsLayout = view.findViewById<LinearLayout>(R.id.brushToolsLayout)
        val sliderBrushSize = view.findViewById<com.google.android.material.slider.Slider>(R.id.sliderBrushSize)
        val tvBrushSizeLabel = view.findViewById<TextView>(R.id.tvBrushSizeLabel)
        val colorsContainer = view.findViewById<LinearLayout>(R.id.colorsContainer)

        Glide.with(this)
            .asBitmap()
            .load(uri)
            .override(1920, 1080)
            .into(object : com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                override fun onResourceReady(
                    resource: Bitmap,
                    transition: com.bumptech.glide.request.transition.Transition<in Bitmap>?
                ) {
                    photoEditorView.source.setImageBitmap(resource)
                }

                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
            })

        val mPhotoEditor = PhotoEditor.Builder(this, photoEditorView).setPinchTextScalable(true).build()
        currentPhotoEditor = mPhotoEditor

        // LISTENER EDIT DEL TESTO GIA' PRESENTE SULLA FOTO
        mPhotoEditor.setOnPhotoEditorListener(object : ja.burhanrashid52.photoeditor.OnPhotoEditorListener {
            override fun onEditTextChangeListener(rootView: View, text: String, colorCode: Int) {
                showTextDialog(mPhotoEditor, colorCode, text, rootView)
            }
            override fun onAddViewListener(viewType: ja.burhanrashid52.photoeditor.ViewType, numberOfAddedViews: Int) {}
            override fun onRemoveViewListener(viewType: ja.burhanrashid52.photoeditor.ViewType, numberOfAddedViews: Int) {}
            override fun onStartViewChangeListener(viewType: ja.burhanrashid52.photoeditor.ViewType) {}
            override fun onStopViewChangeListener(viewType: ja.burhanrashid52.photoeditor.ViewType) {}
            override fun onTouchSourceImage(event: MotionEvent) {}
        })

        // GESTIONE ZOOM & PAN REALE DELL'IMMAGINE
        var scaleFactor = 1.0f
        var posX = 0f
        var posY = 0f
        var lastTouchX = 0f
        var lastTouchY = 0f
        var activePointerId = -1
        var isZoomActive = false

        val scaleDetector = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = Math.max(1.0f, Math.min(scaleFactor, 5.0f))
                photoEditorView.scaleX = scaleFactor
                photoEditorView.scaleY = scaleFactor
                return true
            }
        })

        photoEditorView.setOnTouchListener { _, event ->
            if (isZoomActive) {
                scaleDetector.onTouchEvent(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = event.x
                        lastTouchY = event.y
                        activePointerId = event.getPointerId(0)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        if (pointerIndex != -1 && event.pointerCount == 1 && scaleFactor > 1.0f) {
                            val x = event.getX(pointerIndex)
                            val y = event.getY(pointerIndex)
                            val dx = x - lastTouchX
                            val dy = y - lastTouchY
                            posX += dx
                            posY += dy
                            photoEditorView.translationX = posX
                            photoEditorView.translationY = posY
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        activePointerId = -1
                    }
                }
                true
            } else {
                false
            }
        }

        fun resetZoom() {
            scaleFactor = 1.0f
            posX = 0f
            posY = 0f
            photoEditorView.scaleX = 1.0f
            photoEditorView.scaleY = 1.0f
            photoEditorView.translationX = 0f
            photoEditorView.translationY = 0f
            isZoomActive = false
            btnZoom.setIconTintResource(android.R.color.white)
        }

        mPhotoEditor.brushSize = sliderBrushSize.value
        sliderBrushSize.addOnChangeListener { _, value, _ ->
            val size = value.toInt()
            tvBrushSizeLabel.text = "Spessore: ${size}px"
            mPhotoEditor.brushSize = value
        }

        val colors = listOf(
            Color.WHITE, Color.BLACK, Color.RED,
            Color.parseColor("#FF4081"), Color.parseColor("#9C27B0"),
            Color.BLUE, Color.CYAN, Color.GREEN,
            Color.YELLOW, Color.parseColor("#FF9800")
        )

        val btnSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 36f, resources.displayMetrics).toInt()
        val btnMarginPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt()

        // TRACCIAMENTO STATO SELEZIONE COLORE / GOMMA
        var isEraserActive = false
        var selectedColorInt = Color.WHITE

        fun isColorLight(color: Int): Boolean {
            val lightness = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
            return lightness > 0.6
        }

        fun setupColorButtons(isTextMode: Boolean) {
            colorsContainer.removeAllViews()

            fun updateSelectionUI() {
                for (i in 0 until colorsContainer.childCount) {
                    val frame = colorsContainer.getChildAt(i) as? FrameLayout ?: continue
                    val checkIcon = frame.findViewById<ImageView>(999) ?: continue
                    val tagColor = frame.tag as? Int

                    if (!isTextMode && tagColor == -999) {
                        checkIcon.visibility = if (isEraserActive) View.VISIBLE else View.GONE
                    } else if (tagColor != null && tagColor != -999) {
                        val isSelected = (!isEraserActive && tagColor == selectedColorInt)
                        checkIcon.visibility = if (isSelected) View.VISIBLE else View.GONE
                    }
                }
            }

            // 1. TASTO GOMMA CANCELLA (Usa ImageView per non bloccare il click)
            if (!isTextMode) {
                val eraserFrame = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(btnSizePx, btnSizePx).apply { setMargins(btnMarginPx, 0, btnMarginPx, 0) }
                    tag = -999
                    isClickable = true
                    isFocusable = true
                }

                val eraserImg = ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    setImageResource(R.drawable.ic_broom)
                    background = ContextCompat.getDrawable(this@ChatActivity, R.drawable.icon_background_circle)
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#505050"))
                    setColorFilter(Color.WHITE)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(16, 16, 16, 16)
                }

                val checkIcon = ImageView(this).apply {
                    id = 999
                    layoutParams = FrameLayout.LayoutParams(
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, resources.displayMetrics).toInt(),
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, resources.displayMetrics).toInt()
                    ).apply { gravity = android.view.Gravity.CENTER }
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(Color.WHITE)
                    visibility = if (isEraserActive) View.VISIBLE else View.GONE
                }

                eraserFrame.addView(eraserImg)
                eraserFrame.addView(checkIcon)

                eraserFrame.setOnClickListener {
                    isEraserActive = true
                    mPhotoEditor.setBrushDrawingMode(true)
                    mPhotoEditor.brushEraser()
                    updateSelectionUI()
                }

                colorsContainer.addView(eraserFrame)
            }

            // 2. CERCHI COLORI PREDEFINITI
            colors.forEach { color ->
                val colorFrame = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(btnSizePx, btnSizePx).apply { setMargins(btnMarginPx, 0, btnMarginPx, 0) }
                    tag = color
                    isClickable = true
                    isFocusable = true
                }

                val colorCircle = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    setBackgroundResource(R.drawable.icon_background_circle)
                    backgroundTintList = ColorStateList.valueOf(color)
                }

                val checkTint = if (isColorLight(color)) Color.BLACK else Color.WHITE

                val checkIcon = ImageView(this).apply {
                    id = 999
                    layoutParams = FrameLayout.LayoutParams(
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics).toInt(),
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics).toInt()
                    ).apply { gravity = android.view.Gravity.CENTER }
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(checkTint)
                    visibility = if (!isEraserActive && selectedColorInt == color) View.VISIBLE else View.GONE
                }

                colorFrame.addView(colorCircle)
                colorFrame.addView(checkIcon)

                colorFrame.setOnClickListener {
                    if (isTextMode) {
                        showTextDialog(mPhotoEditor, color)
                    } else {
                        isEraserActive = false
                        selectedColorInt = color
                        mPhotoEditor.setBrushDrawingMode(true)
                        mPhotoEditor.brushColor = color
                        updateSelectionUI()
                    }
                }

                colorsContainer.addView(colorFrame)
            }

            // 3. TASTO TAVOLOZZA PERSONALIZZATA (RGB)
            val customFrame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(btnSizePx, btnSizePx).apply { setMargins(btnMarginPx, 0, btnMarginPx, 0) }
                isClickable = true
                isFocusable = true
            }

            val customBtn = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                setImageResource(R.drawable.ic_wallpaper)
                background = ContextCompat.getDrawable(this@ChatActivity, R.drawable.icon_background_circle)
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@ChatActivity, R.color.primary_light))
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(16, 16, 16, 16)
            }

            customFrame.addView(customBtn)
            customFrame.setOnClickListener {
                showCustomColorPickerDialog { chosenColor ->
                    if (isTextMode) {
                        showTextDialog(mPhotoEditor, chosenColor)
                    } else {
                        isEraserActive = false
                        selectedColorInt = chosenColor
                        mPhotoEditor.setBrushDrawingMode(true)
                        mPhotoEditor.brushColor = chosenColor
                        updateSelectionUI()
                    }
                }
            }
            colorsContainer.addView(customFrame)
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        btnZoom.setOnClickListener {
            if (isZoomActive) {
                resetZoom()
                Toast.makeText(this, "Zoom disattivato", Toast.LENGTH_SHORT).show()
            } else {
                isZoomActive = true
                mPhotoEditor.setBrushDrawingMode(false)
                brushToolsLayout.visibility = View.GONE
                btnZoom.setIconTintResource(R.color.primary_light)
                Toast.makeText(this, "Zoom attivo: usa due dita per ingrandire o spostare l'immagine", Toast.LENGTH_SHORT).show()
            }
        }

        btnCrop.setOnClickListener { startCrop(currentEditingImageUri) }
        btnUndo.setOnClickListener { mPhotoEditor.undo() }

        btnBrush.setOnClickListener {
            if (isZoomActive) {
                isZoomActive = false
                btnZoom.setIconTintResource(android.R.color.white)
            }
            val isDrawing = !brushToolsLayout.isShown
            mPhotoEditor.setBrushDrawingMode(isDrawing)
            brushToolsLayout.visibility = if (isDrawing) View.VISIBLE else View.GONE
            if (isDrawing) setupColorButtons(false)
        }

        btnText.setOnClickListener {
            if (isZoomActive) {
                isZoomActive = false
                btnZoom.setIconTintResource(android.R.color.white)
            }
            mPhotoEditor.setBrushDrawingMode(false)
            brushToolsLayout.visibility = View.GONE
            showTextDialog(mPhotoEditor, Color.WHITE)
        }

        btnSend.setOnClickListener {
            resetZoom()
            val caption = editTextCaption.text.toString().trim()
            saveEditedImageAndSend(mPhotoEditor, mimeType, caption)
        }
        dialog.show()
    }

    private fun showTextDialog(
        photoEditor: PhotoEditor,
        initialColor: Int = Color.WHITE,
        existingText: String? = null,
        rootViewToUpdate: View? = null
    ) {
        var selectedColor = initialColor

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics).toInt()
            setPadding(p, p, p, p)
        }

        val textInputLayout = TextInputLayout(this).apply {
            hint = "Inserisci testo..."
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }

        val editText = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(existingText ?: "")
            setTextColor(selectedColor)
            textSize = 18f
            gravity = android.view.Gravity.CENTER
        }
        textInputLayout.addView(editText)
        dialogView.addView(textInputLayout)

        val colorScrollView = android.widget.HorizontalScrollView(this).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
            }
            layoutParams = lp
            isScrollbarFadingEnabled = false
        }

        val colorsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val colors = listOf(
            Color.WHITE, Color.BLACK, Color.RED,
            Color.parseColor("#FF4081"), Color.parseColor("#9C27B0"),
            Color.BLUE, Color.CYAN, Color.GREEN,
            Color.YELLOW, Color.parseColor("#FF9800")
        )

        val btnSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics).toInt()
        val btnMarginPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt()

        colors.forEach { color ->
            val colorBtn = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(btnSizePx, btnSizePx).apply { setMargins(btnMarginPx, 0, btnMarginPx, 0) }
                setBackgroundResource(R.drawable.icon_background_circle)
                backgroundTintList = ColorStateList.valueOf(color)
                setOnClickListener {
                    selectedColor = color
                    editText.setTextColor(selectedColor)
                }
            }
            colorsLayout.addView(colorBtn)
        }

        colorScrollView.addView(colorsLayout)
        dialogView.addView(colorScrollView)

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existingText == null) "Aggiungi Testo" else "Modifica Testo")
            .setView(dialogView)
            .setPositiveButton("OK") { _, _ ->
                val newText = editText.text?.toString()?.trim() ?: ""
                if (newText.isNotEmpty()) {
                    if (rootViewToUpdate != null) {
                        photoEditor.editText(rootViewToUpdate, newText, selectedColor)
                    } else {
                        photoEditor.addText(newText, selectedColor)
                        Toast.makeText(this, "Testo aggiunto! Puoi trascinarlo o ingrandirlo con le dita.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showCustomColorPickerDialog(onColorSelected: (Int) -> Unit) {
        var red = 255
        var green = 0
        var blue = 0

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics).toInt()
            setPadding(p, p, p, p)
        }

        val colorPreview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60f, resources.displayMetrics).toInt(),
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60f, resources.displayMetrics).toInt()
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
            }
            setBackgroundResource(R.drawable.icon_background_circle)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(red, green, blue))
        }
        dialogView.addView(colorPreview)

        fun updatePreview() {
            colorPreview.backgroundTintList = ColorStateList.valueOf(Color.rgb(red, green, blue))
        }

        fun createColorSlider(name: String, initialVal: Int, onValChanged: (Int) -> Unit) {
            val label = TextView(this).apply {
                text = "$name: $initialVal"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val slider = com.google.android.material.slider.Slider(this).apply {
                valueFrom = 0f
                valueTo = 255f
                value = initialVal.toFloat()
                stepSize = 1f
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
                }
                layoutParams = lp
                addOnChangeListener { _, value, _ ->
                    val v = value.toInt()
                    label.text = "$name: $v"
                    onValChanged(v)
                    updatePreview()
                }
            }
            dialogView.addView(label)
            dialogView.addView(slider)
        }

        createColorSlider("Rosso (R)", red) { red = it }
        createColorSlider("Verde (G)", green) { green = it }
        createColorSlider("Blu (B)", blue) { blue = it }

        MaterialAlertDialogBuilder(this)
            .setTitle("Selezione Colore Personalizzato")
            .setView(dialogView)
            .setPositiveButton("Seleziona") { _, _ ->
                onColorSelected(Color.rgb(red, green, blue))
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showTextDialog(photoEditor: PhotoEditor, color: Int) {
        val editText = EditText(this).apply { hint = "Scrivi testo..."; setTextColor(color) }
        AlertDialog.Builder(this).setView(editText).setPositiveButton("OK") { _, _ ->
            val text = editText.text.toString()
            if (text.isNotEmpty()) photoEditor.addText(text, color)
        }.setNegativeButton("Annulla", null).show()
    }

    private fun startCrop(uri: Uri?) {
        if (uri == null) return
        val destinationFileName = "cropped_image_${System.currentTimeMillis()}.jpg"
        val destinationUri = Uri.fromFile(File(cacheDir, destinationFileName))
        val uCropIntent = UCrop.of(uri, destinationUri).withOptions(UCrop.Options()).getIntent(this)
        cropResultLauncher.launch(uCropIntent)
    }


    @SuppressLint("MissingPermission")
    private fun saveEditedImageAndSend(photoEditor: PhotoEditor, mimeType: String, caption: String) {
        val file = File(cacheDir, "edited_image_${System.currentTimeMillis()}.jpg")
        val saveSettings = SaveSettings.Builder().setClearViewsEnabled(true).setTransparencyEnabled(true).build()
        Toast.makeText(this, "Elaborazione...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val result = photoEditor.saveAsFile(file.absolutePath, saveSettings)

                if (result is ja.burhanrashid52.photoeditor.SaveFileResult.Success) {
                    val uri = Uri.fromFile(file)
                    uploadFile(uri, mimeType, caption)
                    currentEditorDialog?.dismiss()
                } else {
                    Toast.makeText(this@ChatActivity, "Errore durante il salvataggio", Toast.LENGTH_SHORT).show()
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException durante salvataggio immagine editor", e)
                Toast.makeText(this@ChatActivity, "Errore permessi: ${e.message}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "Errore editor: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun uploadFile(uri: Uri, mimeType: String, caption: String? = null, skipCompression: Boolean = false) {
        val blocked = checkTofuKeyChangedPeers()
        if (blocked.isNotEmpty()) {
            refreshTofuBlockingUi(blocked)
            Toast.makeText(this, "Invio file bloccato: verifica prima il cambio chiave.", Toast.LENGTH_LONG).show()
            return
        }

        val replyToId = replyingToMessage?.id
        val isViewOnce = binding.buttonViewOnce.isChecked

        val safeCaption = caption ?: ""
        val hasAtTutti = safeCaption.contains("@tutti", ignoreCase = true)
        val validMentionedIds = mutableSetOf<Int>()

        if (hasAtTutti) {
            val validGroupMembers = groupMembers.filter { it.userId != userId }
            validMentionedIds.addAll(validGroupMembers.map { it.userId })
        } else {
            for ((mentionText, mId) in mentionedUsersMap) {
                if (safeCaption.contains(mentionText, ignoreCase = true) && mId != 0) {
                    validMentionedIds.add(mId)
                }
            }
        }
        val mentionedUsersJson = if (validMentionedIds.isNotEmpty()) Gson().toJson(validMentionedIds) else null

        if (currentE2eeVersion >= 2) {
            lifecycleScope.launch {
                val prep = withContext(Dispatchers.IO) {
                    if (isPrivateChat) {
                        var peerId = otherUserId.takeIf { it > 0 } ?: groupMembers.firstOrNull { it.userId != userId }?.userId ?: -1
                        if (peerId <= 0) {
                            try {
                                val resp = ApiClient.instance.getGroupMembers(groupId = groupId).execute()
                                if (resp.isSuccessful) {
                                    resp.body()?.data?.let { groupMembers = it }
                                    peerId = groupMembers.firstOrNull { it.userId != userId }?.userId ?: -1
                                }
                            } catch (_: Exception) {}
                        }
                        if (peerId <= 0) return@withContext Triple<String?, List<Int>, ((String) -> String?)?>(null, emptyList(), null)
                        otherUserId = peerId

                        E2eeSessionOrchestrator.syncDistributions(applicationContext, userId, 0)
                        E2eeSessionOrchestrator.syncPeerIdentityKeysSync(
                            context = applicationContext,
                            myUserId = userId,
                            peerIds = listOf(peerId),
                            minIntervalMs = 2_000L
                        )
                        val changedNow = checkTofuKeyChangedPeers()
                        if (changedNow.isNotEmpty()) return@withContext Triple(null, changedNow, null)
                        val pwRes = E2eeSessionOrchestrator.ensurePairwiseSessionSync(applicationContext, userId, peerId)
                        if (pwRes.keyChanged) return@withContext Triple(null, listOf(peerId), null)
                        if (!pwRes.ready) return@withContext Triple(null, emptyList(), null)

                        val pwKey = DoubleRatchetHelper.pairwiseSessionKey(peerId)
                        currentGroupKey = pwKey
                        currentGroupIv = ""
                        val encryptor: (String) -> String? = { metaJson ->
                            DoubleRatchetHelper.encryptMessage(pwKey, metaJson)
                        }
                        Triple(pwKey, emptyList(), encryptor)
                    } else {
                        if (groupMembers.isEmpty()) {
                            try {
                                val resp = ApiClient.instance.getGroupMembers(groupId = groupId).execute()
                                if (resp.isSuccessful) {
                                    resp.body()?.data?.let { groupMembers = it }
                                }
                            } catch (_: Exception) {}
                        }
                        E2eeSessionOrchestrator.syncDistributions(applicationContext, userId, 0)
                        E2eeSessionOrchestrator.syncPeerIdentityKeysSync(
                            context = applicationContext,
                            myUserId = userId,
                            peerIds = resolveCandidatePeerIds(),
                            minIntervalMs = 2_000L
                        )
                        val changedNow = checkTofuKeyChangedPeers()
                        if (changedNow.isNotEmpty()) return@withContext Triple(null, changedNow, null)
                        val grpRes = E2eeSessionOrchestrator.ensureGroupSenderKeyDistributedSync(
                            applicationContext, userId, groupId, groupMembers, currentMembershipEpoch
                        )
                        if (grpRes.blockedPeers.isNotEmpty()) {
                            return@withContext Triple(null, grpRes.blockedPeers, null)
                        }
                        val encryptor: (String) -> String? = { metaJson ->
                            SenderKeyHelper.encryptGroupMessage(groupId, userId, metaJson)
                        }
                        Triple("", emptyList(), encryptor)
                    }
                }

                if (isFinishing || isDestroyed) return@launch
                if (prep.second.isNotEmpty()) {
                    refreshTofuBlockingUi(prep.second)
                    Toast.makeText(this@ChatActivity, "Invio file bloccato: chiave cambiata.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val encKey = prep.first
                val encryptor = prep.third
                if (encKey == null || encryptor == null) {
                    Toast.makeText(this@ChatActivity, "Sessione E2EE v2 non pronta per l'invio file.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                BackgroundUploadManager.startUpload(
                    context = applicationContext,
                    uri = uri,
                    mimeType = mimeType,
                    caption = caption,
                    userId = userId,
                    groupId = groupId,
                    username = username,
                    groupName = groupName,
                    isPrivateChat = isPrivateChat,
                    replyToId = replyToId,
                    isViewOnce = isViewOnce,
                    mentionedUsersJson = mentionedUsersJson,
                    currentGroupKey = encKey,
                    currentGroupIv = "",
                    onLocalMessageCreated = {
                        clearReplyState()
                        if (isViewOnce) binding.buttonViewOnce.isChecked = false
                        mentionedUsersMap.clear()
                        forceLocalUIUpdate()
                    },
                    isE2eeV2 = true,
                    metadataEncryptor = encryptor
                )
            }
            return
        }

        if (currentGroupKey == null || currentGroupIv == null) {
            Toast.makeText(this, "Chiavi di cifratura mancanti.", Toast.LENGTH_SHORT).show()
            return
        }

        BackgroundUploadManager.startUpload(
            context = applicationContext,
            uri = uri,
            mimeType = mimeType,
            caption = caption,
            userId = userId,
            groupId = groupId,
            username = username,
            groupName = groupName,
            isPrivateChat = isPrivateChat,
            replyToId = replyToId,
            isViewOnce = isViewOnce,
            mentionedUsersJson = mentionedUsersJson,
            currentGroupKey = currentGroupKey!!,
            currentGroupIv = currentGroupIv!!,
            onLocalMessageCreated = {
                clearReplyState()
                if (isViewOnce) binding.buttonViewOnce.isChecked = false
                mentionedUsersMap.clear()
                forceLocalUIUpdate()
            }
        )
    }

    private fun triggerMessageSending() {
        if (getBaseNetworkStatus() != NetworkQualityMonitor.NetworkStatus.DISCONNECTED) {
            PendingMessageManager.sendAllPendingMessages {
                runOnUiThread { fetchMessages(isTriggeredByBroadcast = false) }
            }
        } else {
            Log.d(TAG, "Nessuna connessione. L'invio dei messaggi è in pausa temporanea.")
        }
    }

    private fun markAllMessagesAsRead() {
        if (userId == -1 || groupId == -1) return
        if (!SecurePrefs.get(this).getBoolean("send_read_receipts", true)) return

        ApiClient.instance.markMessagesAsRead(userId = userId, groupId = groupId).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {}
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                if (t is com.google.gson.JsonSyntaxException) {
                    Log.w(TAG, "Impossibile inviare conferme di lettura: traffico bloccato da WAF.")
                } else {
                    Log.e(TAG, "Errore di rete nell'invio conferme lettura: ${t.message}")
                }
            }
        })
    }

    private fun dismissNotification() {
        if (groupId != -1) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(groupId)
        }
    }

    private fun showEditMessageDialog(message: Message) {
        if (isFinishing || isDestroyed) return
        val decryptedMessage = decryptMessageText(message, currentGroupKey, currentGroupIv)
        if (decryptedMessage == null) { Toast.makeText(this, "Impossibile decifrare.", Toast.LENGTH_SHORT).show(); return }
        val editText = EditText(this).apply { setText(decryptedMessage); setSelection(decryptedMessage.length) }
        MaterialAlertDialogBuilder(this).setTitle("Modifica Messaggio").setView(editText)
            .setPositiveButton("Salva") { _, _ ->
                val newMessage = editText.text?.toString()?.trim() ?: ""
                if (newMessage.isNotEmpty() && newMessage != decryptedMessage) editMessage(message.id, newMessage)
            }
            .setNegativeButton("Annulla", null).show()
    }

    private fun editMessage(messageId: Int, newMessage: String) {
        if (checkTofuKeyChangedPeers().isNotEmpty()) {
            refreshTofuBlockingUi()
            Toast.makeText(this, "Modifica bloccata: verifica prima il cambio chiave.", Toast.LENGTH_SHORT).show()
            return
        }
        val encryptedNewMessage = if (currentE2eeVersion >= 2) {
            if (isPrivateChat) {
                if (otherUserId <= 0) return
                DoubleRatchetHelper.encryptMessage(DoubleRatchetHelper.pairwiseSessionKey(otherUserId), newMessage)
            } else {
                SenderKeyHelper.encryptGroupMessage(groupId, userId, newMessage)
            }
        } else {
            if (currentGroupKey == null || currentGroupIv == null) return
            EncryptionHelper.encrypt(newMessage, currentGroupKey!!, currentGroupIv!!)
        } ?: return

        ApiClient.instance.editMessage(userId = userId, messageId = messageId, encryptedNewMessage = encryptedNewMessage).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) { if (response.isSuccessful && response.body()?.status == "success") Toast.makeText(this@ChatActivity, "Modificato", Toast.LENGTH_SHORT).show() else Toast.makeText(this@ChatActivity, "Errore", Toast.LENGTH_SHORT).show() }
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) { Toast.makeText(this@ChatActivity, "Errore rete", Toast.LENGTH_SHORT).show() }
        })
    }

    private fun deleteMessage(message: Message) {
        if (message.fileId != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val localFile = FileUtils.getLocalFile(this@ChatActivity, message)
                if (localFile.exists()) PanicHelper.secureDelete(localFile)
            }
        }

        // Rimuove subito dal DB locale
        lifecycleScope.launch(Dispatchers.IO) {
            LocalMessageManager.deleteMessage(this@ChatActivity, message.id)
        }

        ApiClient.instance.deleteMessage(userId = userId, messageId = message.id).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                if (response.isSuccessful && response.body()?.status == "success") {
                    if (!message.isViewOnce) Toast.makeText(this@ChatActivity, "Eliminato", Toast.LENGTH_SHORT).show()
                    fetchMessages(isTriggeredByBroadcast = false)
                } else {
                    Toast.makeText(this@ChatActivity, "Errore: ${response.body()?.message}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                Toast.makeText(this@ChatActivity, "Errore rete", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun startPolling() {
        if (::pollingRunnable.isInitialized) {
            uiHandler.removeCallbacks(pollingRunnable)
        }
        pollingRunnable = Runnable {
            if (!isFinishing && !isDestroyed) {
                triggerMessageSending()
                fetchMessages(isTriggeredByBroadcast = false)
                uiHandler.postDelayed(pollingRunnable, pollingInterval)
            }
        }
        uiHandler.postDelayed(pollingRunnable, pollingInterval)
    }

    private fun stopPolling() {
        if (::pollingRunnable.isInitialized) {
            uiHandler.removeCallbacks(pollingRunnable)
        }
    }

    @SuppressLint("RestrictedApi")
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.chat_menu, menu)
        if (menu is androidx.appcompat.view.menu.MenuBuilder) {
            menu.setOptionalIconsVisible(true)
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val settingsItem = menu.findItem(R.id.action_group_settings)
        val canAccessSettings = isPrivateChat || isCurrentUserAdmin
        settingsItem?.isVisible = canAccessSettings
        settingsItem?.isEnabled = canAccessSettings

        val clearChatItem = menu.findItem(R.id.action_clear_chat)
        clearChatItem?.isVisible = canAccessSettings
        clearChatItem?.isEnabled = canAccessSettings

        val callItem = menu.findItem(R.id.action_call)
        callItem?.isVisible = isPrivateChat

        if (isPrivateChat) {
            val isOtherUserLoaded = groupMembers.any { it.userId != userId }
            callItem?.isEnabled = isOtherUserLoaded
            callItem?.icon?.alpha = if (isOtherUserLoaded) 255 else 130
        }

        val ultraSecureItem = menu.findItem(R.id.action_ultra_secure)
        ultraSecureItem?.isVisible = true

        val regenerateKeysItem = menu.findItem(R.id.action_regenerate_keys)
        regenerateKeysItem?.isVisible = true

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { handleBackNavigation(); true }
            R.id.action_call -> { startVoiceCall(); true }
            R.id.action_group_settings -> { showGroupSettingsDialog(); true }
            R.id.action_ultra_secure -> { showUltraSecureChatOptions(); true }
            R.id.action_clear_chat -> { showClearChatConfirmationDialog(); true }
            R.id.action_regenerate_keys -> { showRegenerateKeysConfirmationDialog(); true }
            R.id.action_chat_background -> { showChatBackgroundSettingsDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startVoiceCall() {
        val useProxy = SecurePrefs.get(this).getBoolean("use_proxy", false)
        if (useProxy) {
            MaterialAlertDialogBuilder(this).setTitle("Chiamate Disabilitate").setMessage(getString(R.string.call_error_tor_enabled)).setPositiveButton("OK", null).show()
            return
        }
        if (!isPrivateChat) {
            Toast.makeText(this, "Solo chat private.", Toast.LENGTH_SHORT).show()
            return
        }

        val otherMember = groupMembers.find { it.userId != userId }
        val targetId = otherMember?.userId ?: -1

        if (targetId == -1) {
            Toast.makeText(this, "Caricamento utente in corso... Riprova tra un istante.", Toast.LENGTH_SHORT).show()
            fetchGroupMembersForMentions()
            return
        }

        showCallTypeDialog(targetId, otherMember)
    }

    private fun showCallTypeDialog(targetId: Int, otherMember: GroupMember?) {
        if (isFinishing || isDestroyed) return

        // OTTIMIZZAZIONE: Avvia preventivamente la risoluzione dei server TURN
        // in background mentre l'utente visualizza o legge il dialogo di selezione.
        MeteredcaManager.preloadIceServers(applicationContext)

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_call_type, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val descView = dialogView.findViewById<TextView>(R.id.call_dialog_description)
        descView.text = Html.fromHtml(getString(R.string.call_dialog_desc), Html.FROM_HTML_MODE_COMPACT)

        val btnNormal = dialogView.findViewById<MaterialButton>(R.id.btn_call_normal)
        val btnProtected = dialogView.findViewById<MaterialButton>(R.id.btn_call_protected)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btn_call_cancel)

        btnNormal.setOnClickListener {
            dialog.dismiss()
            SecurePrefs.get(this).edit().putBoolean("temp_call_protect_ip", false).apply()
            proceedToCallActivity(targetId, otherMember)
        }

        btnProtected.setOnClickListener {
            dialog.dismiss()
            SecurePrefs.get(this).edit().putBoolean("temp_call_protect_ip", true).apply()
            Toast.makeText(this, "Protezione IP attivata per questa chiamata.", Toast.LENGTH_SHORT).show()
            proceedToCallActivity(targetId, otherMember)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
        dialog.show()
    }

    private fun proceedToCallActivity(targetId: Int, otherMember: GroupMember?) {
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra("GROUP_ID", groupId)
            putExtra("OTHER_USER_ID", targetId)
            putExtra("OTHER_USERNAME", otherMember?.username)
            putExtra("PROFILE_PIC", otherMember?.profilePictureUrl)
            putExtra("IS_INCOMING", false)
            putExtra("GROUP_KEY", if (currentE2eeVersion >= 2) null else currentGroupKey)
            putExtra("GROUP_IV", if (currentE2eeVersion >= 2) null else currentGroupIv)
        }
        startActivity(intent)
    }

    private fun showGroupSettingsDialog() {
        if (isFinishing || isDestroyed) return
        val dialogBinding = DialogGroupSettingsBinding.inflate(LayoutInflater.from(this))
        activeSettingsDialogBinding = dialogBinding

        if (isPrivateChat) {
            dialogBinding.groupImageTitle.visibility = View.GONE
            dialogBinding.groupImageSection.visibility = View.GONE
            dialogBinding.divider1.visibility = View.GONE
            dialogBinding.groupNameInputLayout.visibility = View.GONE
            dialogBinding.divider2.visibility = View.GONE
            dialogBinding.switchAdminsOnlyChat.visibility = View.GONE
            dialogBinding.divider3.visibility = View.GONE
        } else {
            dialogBinding.editTextGroupName.setText(groupName)
            dialogBinding.switchAdminsOnlyChat.isChecked = currentAdminsOnlyChat
            val placeholder = R.drawable.ic_group
            if (!currentGroupPictureUrl.isNullOrBlank()) {
                Glide.with(this).load(ApiClient.BASE_URL + currentGroupPictureUrl).placeholder(placeholder).error(placeholder).into(dialogBinding.groupPicturePreview)
            } else {
                dialogBinding.groupPicturePreview.setImageResource(placeholder)
            }
            dialogBinding.buttonChangeGroupPicture.setOnClickListener { pickGroupImageLauncher.launch("image/*") }

            // Generazione con IA per la foto del Gruppo
            dialogBinding.buttonAiGroupPicture.setOnClickListener {
                AIDialogHelper.showAIGeneratorDialog(
                    activity = this,
                    buttonText = "Imposta Foto Gruppo"
                ) { bitmap ->
                    val uri = FileUtils.saveBitmapToTempUri(this, bitmap)
                    if (uri != null) {
                        uploadGroupPicture(uri)
                    } else {
                        Toast.makeText(this, "Errore durante la preparazione dell'immagine.", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            dialogBinding.buttonRemoveGroupPicture.setOnClickListener { removeGroupPicture() }
        }

        fun updateLabel(value: Float) { dialogBinding.autoDeleteLabel.text = if (value == 0f) "Mai" else "Dopo ${value.toInt()} minuti" }
        dialogBinding.autoDeleteSlider.value = currentAutoDeleteMinutes.toFloat()
        updateLabel(dialogBinding.autoDeleteSlider.value)
        dialogBinding.autoDeleteSlider.addOnChangeListener { _, value, _ -> updateLabel(value) }

        groupSettingsDialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (isPrivateChat) "Impostazioni Chat" else "Impostazioni Gruppo")
            .setView(dialogBinding.root)
            .setPositiveButton("Salva") { _, _ ->
                val newMinutes = dialogBinding.autoDeleteSlider.value.toInt()
                if (newMinutes != currentAutoDeleteMinutes) setAutoDelete(newMinutes)
                if (!isPrivateChat) {
                    val newName = dialogBinding.editTextGroupName.text.toString().trim()
                    if (newName.isNotEmpty() && newName != groupName) changeGroupName(newName)
                    val newAdminsOnly = dialogBinding.switchAdminsOnlyChat.isChecked
                    if (newAdminsOnly != currentAdminsOnlyChat) setGroupChatMode(newAdminsOnly)
                }
            }
            .setNegativeButton("Annulla", null)
            .setOnDismissListener { activeSettingsDialogBinding = null; groupSettingsDialog = null }
            .show()
    }

    private fun changeGroupName(newName: String) {
        ApiClient.instance.changeGroupName(userId = userId, groupId = groupId, newName = newName).enqueue(object : Callback<ChangeGroupNameResponse> {
            override fun onResponse(call: Call<ChangeGroupNameResponse>, response: Response<ChangeGroupNameResponse>) { if (response.isSuccessful && response.body()?.status == "success") { groupName = response.body()?.data?.newName ?: newName; updateToolbarUI(); Toast.makeText(this@ChatActivity, "Nome aggiornato", Toast.LENGTH_SHORT).show() } }
            override fun onFailure(call: Call<ChangeGroupNameResponse>, t: Throwable) {}
        })
    }

    private fun setGroupChatMode(adminsOnly: Boolean) {
        ApiClient.instance.setGroupChatMode(userId = userId, groupId = groupId, adminsOnlyChat = adminsOnly).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) { if (response.isSuccessful && response.body()?.status == "success") { currentAdminsOnlyChat = adminsOnly; updateChatInputState() } }
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {}
        })
    }

    private fun uploadGroupPicture(uri: Uri) {
        val progressIndicator = activeSettingsDialogBinding?.groupPictureProgress
        progressIndicator?.progress = 0
        progressIndicator?.visibility = View.VISIBLE

        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { FileUtils.getFileFromUri(this@ChatActivity, uri) } ?: run {
                progressIndicator?.visibility = View.GONE
                Toast.makeText(this@ChatActivity, "Impossibile aprire l'immagine selezionata.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val mimeType = contentResolver.getType(uri) ?: "image/*"

            // ProgressRequestBody invia l'avanzamento reale in percentuale (0..100) all'indicatore circolare del gruppo
            val requestFile = ProgressRequestBody(file, mimeType) { progress ->
                runOnUiThread {
                    progressIndicator?.progress = progress
                }
            }

            val body = MultipartBody.Part.createFormData("group_picture", file.name, requestFile)
            val action = "upload_group_picture".toRequestBody("text/plain".toMediaTypeOrNull())
            val userIdBody = userId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val groupIdBody = groupId.toString().toRequestBody("text/plain".toMediaTypeOrNull())

            ApiClient.instance.uploadGroupPicture(action, userIdBody, groupIdBody, body).enqueue(object : Callback<GroupPictureResponse> {
                override fun onResponse(call: Call<GroupPictureResponse>, response: Response<GroupPictureResponse>) {
                    progressIndicator?.visibility = View.GONE
                    if (isFinishing || isDestroyed) return
                    if (response.isSuccessful && response.body()?.status == "success") {
                        val newUrl = response.body()?.data?.groupPictureUrl
                        currentGroupPictureUrl = newUrl
                        updateToolbarUI()
                        activeSettingsDialogBinding?.let {
                            Glide.with(this@ChatActivity).load(ApiClient.BASE_URL + newUrl).into(it.groupPicturePreview)
                        }
                    } else {
                        Toast.makeText(this@ChatActivity, "Errore caricamento immagine gruppo", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(call: Call<GroupPictureResponse>, t: Throwable) {
                    progressIndicator?.visibility = View.GONE
                }
            })
        }
    }

    private fun removeGroupPicture() {
        ApiClient.instance.removeGroupPicture(userId = userId, groupId = groupId).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                if (isFinishing || isDestroyed) return
                if (response.isSuccessful && response.body()?.status == "success") {
                    currentGroupPictureUrl = null; updateToolbarUI()
                    activeSettingsDialogBinding?.groupPicturePreview?.setImageResource(R.drawable.ic_group)
                }
            }
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {}
        })
    }

    private fun setAutoDelete(minutes: Int) {
        ApiClient.instance.setAutoDelete(groupId = groupId, minutes = minutes).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) { if (response.isSuccessful && response.body()?.status == "success") currentAutoDeleteMinutes = minutes }
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {}
        })
    }

    private fun showClearChatConfirmationDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Svuota Chat")
            .setMessage("Eliminare tutti i messaggi?")
            .setPositiveButton("Sì") { _, _ -> clearChat() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun deleteLocalMediaForChat(groupId: Int) {
        try {
            // 1. Cancella i file associati ai messaggi della chat
            lastServerMessages.forEach { msg ->
                if (msg.fileId != null) {
                    val localFile = FileUtils.getLocalFile(this, msg)
                    if (localFile.exists()) {
                        PanicHelper.secureDelete(localFile)
                    }
                }
            }

            // 2. Pulizia file temporanei e rimanenze in cache
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("temp_") || file.name.startsWith("edited_image_")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore pulizia file locali", e)
        }
    }


    private fun clearChat() {
        lifecycleScope.launch(Dispatchers.IO) {
            deleteLocalMediaForChat(groupId)
            LocalMessageManager.deleteAllMessagesForGroup(this@ChatActivity, groupId)
        }

        ApiClient.instance.deleteAllMessages(groupId = groupId).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                if (response.isSuccessful && response.body()?.status == "success") {
                    messageAdapter.updateItems(emptyList())
                    lastServerMessages = emptyList()
                    Toast.makeText(this@ChatActivity, "Chat svuotata", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@ChatActivity, "Errore: ${response.body()?.message}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                Toast.makeText(this@ChatActivity, "Errore di rete", Toast.LENGTH_SHORT).show()
            }
        })
    }


    private fun showRegenerateKeysConfirmationDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_chat_rekey, null)
        val badge = dialogView.findViewById<TextView>(R.id.tv_chat_rekey_badge)
        val detailsTitle = dialogView.findViewById<TextView>(R.id.tv_chat_rekey_title)
        val detailsBody = dialogView.findViewById<TextView>(R.id.tv_chat_rekey_details)

        when {
            isPrivateChat && currentE2eeVersion >= 2 -> {
                badge.text = "X3DH + Double Ratchet"
                detailsTitle.text = "Nuova sessione privata E2EE"
                detailsBody.text = "• Crea una nuova sessione crittografica con questo contatto\n• Ruota la tua Signed PreKey locale sul server\n• Mantiene il Safety Number identitario e i messaggi già decifrati"
            }
            currentE2eeVersion >= 2 -> {
                badge.text = "Sender Keys Gruppo v2"
                detailsTitle.text = "Rotazione Sender Key di gruppo"
                detailsBody.text = "• Genera una nuova Sender Key per i prossimi messaggi\n• Rinegozia i canali X3DH con i membri attuali del gruppo\n• I messaggi precedenti già decifrati restano leggibili"
            }
            else -> {
                badge.text = "Crittografia Legacy"
                detailsTitle.text = "Rigenerazione chiavi gruppo"
                detailsBody.text = "• Sostituisce le chiavi simmetriche correnti della chat\n• Azione irreversibile per i nuovi messaggi"
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Rigenera Chiavi Cifratura")
            .setView(dialogView)
            .setPositiveButton("Rigenera ora") { _, _ -> regenerateEncryptionKeys() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun regenerateEncryptionKeys() {
        if (isPrivateChat && currentE2eeVersion >= 2) {
            val resolvedPeerId = otherUserId.takeIf { it > 0 }
                ?: groupMembers.firstOrNull { it.userId != userId }?.userId
                ?: -1
            if (resolvedPeerId <= 0) {
                Toast.makeText(this, "Interlocutore non ancora caricato. Riprova tra un istante.", Toast.LENGTH_SHORT).show()
                fetchGroupMembersForMentions()
                return
            }

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val maxPeerMsgId = lastServerMessages.filter { it.userId == resolvedPeerId }.maxOfOrNull { it.id } ?: 0
                    E2eeSessionOrchestrator.markMessagesHealedUpTo(applicationContext, groupId, resolvedPeerId, maxPeerMsgId)
                    val res = E2eeSessionOrchestrator.renegotiatePairwiseSessionSync(
                        context = applicationContext,
                        myUserId = userId,
                        peerUserId = resolvedPeerId,
                        groupId = groupId
                    )
                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        when {
                            res.keyChanged -> {
                                refreshTofuBlockingUi(listOf(resolvedPeerId))
                                Toast.makeText(this@ChatActivity, "Chiave identitaria cambiata: verifica prima il Safety Number.", Toast.LENGTH_LONG).show()
                            }
                            res.ready -> {
                                fetchMessages(true)
                                Toast.makeText(this@ChatActivity, "Sessione E2EE (X3DH + Double Ratchet) rigenerata.", Toast.LENGTH_LONG).show()
                            }
                            else -> {
                                Toast.makeText(this@ChatActivity, "PreKey locali ruotate; nuova sessione pronta al prossimo messaggio.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(this@ChatActivity, "Errore rigenerazione chiavi: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            return
        }

        if (currentE2eeVersion >= 2) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    E2eeSessionOrchestrator.ensureLocalBundleUploadedSync(applicationContext, userId, forceRotate = false)
                    E2eeSessionOrchestrator.clearGroupDistributionCache(applicationContext, groupId)
                    SenderKeyHelper.rotateMySenderKey(groupId, userId)
                    val regenResp = ApiClient.instance.regenerateKeys(groupId = groupId, userId = userId).execute()
                    if (regenResp.isSuccessful) {
                        currentMembershipEpoch += 1
                        SecurePrefs.get(applicationContext).edit().putInt("membership_epoch_$groupId", currentMembershipEpoch).commit()
                    }
                    val membersResp = ApiClient.instance.getGroupMembers(groupId = groupId).execute()
                    if (membersResp.isSuccessful) {
                        membersResp.body()?.data?.let { groupMembers = it }
                    }
                    val res = E2eeSessionOrchestrator.ensureGroupSenderKeyDistributedSync(
                        context = applicationContext,
                        myUserId = userId,
                        groupId = groupId,
                        members = groupMembers,
                        membershipEpoch = currentMembershipEpoch,
                        forceResetPairwise = true
                    )
                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        refreshTofuBlockingUi(res.blockedPeers)
                        Toast.makeText(this@ChatActivity, "Sender Key ruotata e distribuita ai membri attuali.", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(this@ChatActivity, "Errore rotazione chiave: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            return
        }

        ApiClient.instance.regenerateKeys(groupId = groupId, userId = userId).enqueue(object: Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) { if (response.isSuccessful) Toast.makeText(this@ChatActivity, "Chiavi rigenerate.", Toast.LENGTH_LONG).show() }
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {}
        })
    }

    private fun fetchGroupMembersForMentions() {
        ApiClient.instance.getGroupMembers(groupId = groupId).enqueue(object : Callback<GetGroupMembersResponse> {
            override fun onResponse(call: Call<GetGroupMembersResponse>, response: Response<GetGroupMembersResponse>) {
                groupMembers = response.body()?.data ?: emptyList()
                if (isPrivateChat) {
                    val peer = groupMembers.firstOrNull { it.userId != userId }?.userId ?: -1
                    if (peer > 0) {
                        otherUserId = peer
                        SecurePrefs.get(this@ChatActivity).edit().putInt("private_peer_uid_$groupId", peer).apply()
                    }
                    invalidateOptionsMenu()
                }
                refreshTofuBlockingUi()
            }
            override fun onFailure(call: Call<GetGroupMembersResponse>, t: Throwable) {}
        })
    }

    private fun fetchAndShowGroupMembers() {
        if (isFinishing || isDestroyed) return
        val options = mutableListOf<String>()
        groupMembers.forEach { member ->
            options.add(member.username)
        }
        MaterialAlertDialogBuilder(this).setTitle("Membri del Gruppo").setItems(options.toTypedArray()) { _, which ->
            val member = groupMembers[which]
            if (member.userId != userId) {
                if (isCurrentUserAdmin) {
                    showAdminOptionsDialog(member)
                } else {
                    showSafetyEmojisDialog(targetPeerId = member.userId)
                }
            }
        }.show()
    }

    private fun displayMembersDialog(members: List<GroupMember>) {
        if (isFinishing || isDestroyed) return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_group_members, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.members_recycler_view)
        val adapter = GroupMemberAdapter(members) { member ->
            if (member.userId != userId) {
                if (isCurrentUserAdmin) {
                    showAdminOptionsDialog(member)
                } else {
                    membersDialog?.dismiss()
                    showSafetyEmojisDialog(targetPeerId = member.userId)
                }
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(this); recyclerView.adapter = adapter
        membersDialog = MaterialAlertDialogBuilder(this).setView(dialogView).setPositiveButton("Chiudi", null).show()
        membersDialog?.setOnDismissListener { membersDialog = null }
    }

    private fun showAdminOptionsDialog(member: GroupMember) {
        if (isFinishing || isDestroyed) return
        val options = mutableListOf("Verifica Identità / Safety Number", if (member.isAdmin) "Rimuovi Admin" else "Rendi Admin")
        if (!member.isAdmin) options.add("Espelli dal gruppo")
        MaterialAlertDialogBuilder(this).setTitle(member.username).setItems(options.toTypedArray()) { _, which ->
            when (options[which]) {
                "Verifica Identità / Safety Number" -> {
                    membersDialog?.dismiss()
                    showSafetyEmojisDialog(targetPeerId = member.userId)
                }
                "Rimuovi Admin", "Rendi Admin" -> setAdminStatus(member, !member.isAdmin)
                "Espelli dal gruppo" -> kickUserFromGroup(member)
            }
        }.show()
    }

    private fun setAdminStatus(member: GroupMember, makeAdmin: Boolean) {
        val isAdminInt = if (makeAdmin) 1 else 0
        ApiClient.instance.setAdminStatus(groupId = groupId, adminUserId = userId, targetUserId = member.userId, isAdmin = isAdminInt).enqueue(object: Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                if (isFinishing || isDestroyed) return
                if (response.isSuccessful && response.body()?.status == "success") {
                    membersDialog?.dismiss()
                    fetchAndShowCustomGroupMembersDialog()
                } else {
                    Toast.makeText(this@ChatActivity, "Errore: ${response.body()?.message ?: "Impossibile aggiornare lo stato di amministratore"}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                if (isFinishing || isDestroyed) return
                Toast.makeText(this@ChatActivity, "Errore di rete.", Toast.LENGTH_SHORT).show()
            }
        })
    }


    private fun kickUserFromGroup(member: GroupMember) {
        ApiClient.instance.kickUser(groupId = groupId, adminId = userId, userToKickId = member.userId).enqueue(object: Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                if (isFinishing || isDestroyed) return
                if (response.isSuccessful && response.body()?.status == "success") {
                    membersDialog?.dismiss()
                    fetchAndShowCustomGroupMembersDialog()
                } else {
                    Toast.makeText(this@ChatActivity, "Errore: ${response.body()?.message ?: "Impossibile espellere l'utente"}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                if (isFinishing || isDestroyed) return
                Toast.makeText(this@ChatActivity, "Errore di rete.", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun fetchAndShowCustomGroupMembersDialog() {
        if (isFinishing || isDestroyed) return
        ApiClient.instance.getGroupMembers(groupId = groupId).enqueue(object : Callback<GetGroupMembersResponse> {
            override fun onResponse(call: Call<GetGroupMembersResponse>, response: Response<GetGroupMembersResponse>) {
                if (isFinishing || isDestroyed) return
                if (response.isSuccessful) {
                    groupMembers = response.body()?.data ?: emptyList()
                    displayMembersDialog(groupMembers)
                } else {
                    Toast.makeText(this@ChatActivity, "Errore caricamento membri.", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(call: Call<GetGroupMembersResponse>, t: Throwable) {
                if (isFinishing || isDestroyed) return
                Toast.makeText(this@ChatActivity, "Errore di rete.", Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun onShowReactions(message: Message) {
        if (reactionDialog?.isShowing == true) return

        val dialogBinding = DialogReactionsBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this).setView(dialogBinding.root).create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation

        val quickReactionsContainerId = resources.getIdentifier("quick_reactions_container", "id", packageName)
        val quickReactionsContainer = if (quickReactionsContainerId != 0) dialogBinding.root.findViewById<LinearLayout>(quickReactionsContainerId) else null

        val allEmojisCardId = resources.getIdentifier("all_emojis_card", "id", packageName)
        val allEmojisCard = if (allEmojisCardId != 0) dialogBinding.root.findViewById<com.google.android.material.card.MaterialCardView>(allEmojisCardId) else null

        val btnToggleMoreEmojisId = resources.getIdentifier("btn_toggle_more_emojis", "id", packageName)
        val btnToggleMoreEmojis = if (btnToggleMoreEmojisId != 0) dialogBinding.root.findViewById<com.google.android.material.button.MaterialButton>(btnToggleMoreEmojisId) else null

        val categoryTabsContainerId = resources.getIdentifier("category_tabs_container", "id", packageName)
        val categoryTabsContainer = if (categoryTabsContainerId != 0) dialogBinding.root.findViewById<LinearLayout>(categoryTabsContainerId) else null

        val searchEmojiInputId = resources.getIdentifier("search_emoji_input", "id", packageName)
        val searchEmojiInput = if (searchEmojiInputId != 0) dialogBinding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(searchEmojiInputId) else null

        val progressBarEmojisId = resources.getIdentifier("progressBarEmojis", "id", packageName)
        val progressBarEmojis = if (progressBarEmojisId != 0) dialogBinding.root.findViewById<ProgressBar>(progressBarEmojisId) else null

        val allEmojisRecyclerViewId = resources.getIdentifier("all_emojis_recycler_view", "id", packageName)
        val allEmojisRecyclerView = if (allEmojisRecyclerViewId != 0) dialogBinding.root.findViewById<RecyclerView>(allEmojisRecyclerViewId) else null

        val tvEmptyEmojisId = resources.getIdentifier("tv_empty_emojis", "id", packageName)
        val tvEmptyEmojis = if (tvEmptyEmojisId != 0) dialogBinding.root.findViewById<TextView>(tvEmptyEmojisId) else null

        val btnActionFavStickerId = resources.getIdentifier("btn_action_favorite_sticker", "id", packageName)
        val btnActionFavSticker = if (btnActionFavStickerId != 0) dialogBinding.root.findViewById<MaterialButton>(btnActionFavStickerId) else null

        allEmojisRecyclerView?.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 7)

        val quickEmojis = listOf("👍", "❤️", "😂", "😲", "😢", "🙏")
        val currentUserReaction = message.reactions?.find { it.userId == userId && it.reactionEmoji != "📋" }?.reactionEmoji

        val px48 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics).toInt()
        val typedValue = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true) }
        val colorSubtitle = TypedValue().also { theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, it, true) }

        quickReactionsContainer?.removeAllViews()
        quickEmojis.forEach { emoji ->
            val textView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, px48, 1f)
                gravity = android.view.Gravity.CENTER
                textSize = 28f
                text = emoji
                isClickable = true
                isFocusable = true
                setBackgroundResource(typedValue.resourceId)
            }
            if (emoji == currentUserReaction) {
                textView.setBackgroundColor(ContextCompat.getColor(this@ChatActivity, R.color.message_highlight))
            }
            textView.setOnClickListener {
                val newReaction = if (emoji == currentUserReaction) null else emoji
                if (newReaction != null) saveRecentEmoji(newReaction)
                sendReaction(message, newReaction)
                dialog.dismiss()
            }
            quickReactionsContainer?.addView(textView)
        }

        val apiGroupMapping = linkedMapOf(
            "smileys-emotion" to "😀",
            "people-body" to "👋",
            "animals-nature" to "🐶",
            "food-drink" to "🍔",
            "travel-places" to "🚗",
            "activities" to "⚽",
            "objects" to "💡",
            "symbols" to "🔣",
            "flags" to "🏁"
        )

        var currentSelectedCategory = "smileys-emotion"
        val cachedApiGroups = mutableMapOf<String, List<String>>()
        var searchJob: Job? = null

        fun renderEmojiList(emojis: List<String>) {
            if (isFinishing || isDestroyed) return

            if (emojis.isEmpty()) {
                allEmojisRecyclerView?.visibility = View.GONE
                tvEmptyEmojis?.visibility = View.VISIBLE
            } else {
                allEmojisRecyclerView?.visibility = View.VISIBLE
                tvEmptyEmojis?.visibility = View.GONE

                allEmojisRecyclerView?.adapter = EmojiPickerAdapter(
                    list = emojis,
                    selected = currentUserReaction,
                    px48 = px48,
                    typedValueResId = typedValue.resourceId,
                    highlightColor = ContextCompat.getColor(this@ChatActivity, R.color.message_highlight)
                ) { emoji ->
                    if (emoji != currentUserReaction) saveRecentEmoji(emoji)
                    sendReaction(message, if (emoji == currentUserReaction) null else emoji)
                    dialog.dismiss()
                }
            }
        }

        fun fetchEmojisFromApi(groupName: String?, searchQuery: String?) {
            progressBarEmojis?.visibility = View.VISIBLE

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val client = ApiClient.okHttpClient
                    val urlBuilder = Uri.parse("https://www.emoji.family/api/emojis").buildUpon()

                    if (groupName == "smileys-emotion" || groupName == "people-body" || groupName == null) {
                        urlBuilder.appendQueryParameter("includeVariations", "true")
                    }

                    if (!groupName.isNullOrBlank()) {
                        urlBuilder.appendQueryParameter("group", groupName)
                    }
                    if (!searchQuery.isNullOrBlank()) {
                        urlBuilder.appendQueryParameter("search", searchQuery)
                    }

                    val request = okhttp3.Request.Builder()
                        .url(urlBuilder.build().toString())
                        .build()

                    val response = client.newCall(request).execute()
                    try {
                        var success = false
                        val emojiList = mutableListOf<String>()

                        if (response.isSuccessful) {
                            val bodyString = response.body?.string()
                            if (!bodyString.isNullOrBlank()) {
                                val jsonArray = org.json.JSONArray(bodyString)
                                for (i in 0 until jsonArray.length()) {
                                    val obj = jsonArray.getJSONObject(i)
                                    val emojiStr = obj.optString("emoji", "")
                                    if (emojiStr.isNotEmpty()) {
                                        emojiList.add(emojiStr)
                                    }
                                }
                                success = emojiList.isNotEmpty()
                            }
                        }

                        withContext(Dispatchers.Main) {
                            progressBarEmojis?.visibility = View.GONE

                            val finalEmojiList = if (groupName == "flags" && !success) {
                                listOf(
                                    "🇮🇹", "🇺🇸", "🇬🇧", "🇫🇷", "🇩🇪", "🇪🇸", "🇯🇵", "🇨🇳", "🇧🇷", "🇷🇺",
                                    "🇨🇦", "🇦🇺", "🇮🇳", "🇲🇽", "🇰🇷", "🇬🇷", "🇪🇺", "🇵🇹", "🇨🇭", "🇸🇪",
                                    "🇳🇱", "🇧🇪", "🇦🇹", "🇵🇱", "🇹🇷", "🇦🇪", "🇸🇦", "🇿🇦", "🇪🇬", "🇮🇱",
                                    "🇺🇦", "🇦🇷", "🇨🇱", "🇨🇴", "🇻🇪", "🇵🇪", "🇮🇪", "🇩🇰", "🇳🇴", "🇫🇮",
                                    "🏁", "🚩", "🎌", "🏴", "🏳", "🏳️‍🌈", "🏳️‍⚧️", "🏴‍☠️"
                                )
                            } else {
                                emojiList
                            }

                            if (!groupName.isNullOrBlank()) {
                                cachedApiGroups[groupName] = finalEmojiList
                            }
                            renderEmojiList(finalEmojiList)
                        }
                    } finally {
                        response.close()
                    }
                } catch (e: Exception) {
                    Log.e("ChatActivity", "Errore nel caricamento delle emoji dall'API.", e)
                    withContext(Dispatchers.Main) {
                        progressBarEmojis?.visibility = View.GONE

                        if (groupName == "flags") {
                            val fallbackFlags = listOf(
                                "🇮🇹", "🇺🇸", "🇬🇧", "🇫🇷", "🇩🇪", "🇪🇸", "🇯🇵", "🇨🇳", "🇧🇷", "🇷🇺",
                                "🇨🇦", "🇦🇺", "🇮🇳", "🇲🇽", "🇰🇷", "🇬🇷", "🇪🇺", "🇵🇹", "🇨🇭", "🇸🇪",
                                "🇳🇱", "🇧🇪", "🇦🇹", "🇵🇱", "🇹🇷", "🇦🇪", "🇸🇦", "🇿🇦", "🇪🇬", "🇮🇱",
                                "🇺🇦", "🇦🇷", "🇨🇱", "🇨🇴", "🇻🇪", "🇵🇪", "🇮🇪", "🇩🇰", "🇳🇴", "🇫🇮",
                                "🏁", "🚩", "🎌", "🏴", "🏳", "🏳️‍🌈", "🏳️‍⚧️", "🏴‍☠️"
                            )
                            cachedApiGroups[groupName] = fallbackFlags
                            renderEmojiList(fallbackFlags)
                        } else {
                            Toast.makeText(this@ChatActivity, "Connessione assente o errore API.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        fun onCategorySelected(categoryName: String) {
            currentSelectedCategory = categoryName
            searchEmojiInput?.text?.clear()

            if (cachedApiGroups.containsKey(categoryName)) {
                renderEmojiList(cachedApiGroups[categoryName]!!)
            } else {
                renderEmojiList(emptyList())
                fetchEmojisFromApi(categoryName, null)
            }
        }

        fun renderCategoryTabs() {
            categoryTabsContainer?.removeAllViews()
            apiGroupMapping.forEach { (apiGroupKey, iconEmoji) ->
                val tabView = TextView(this).apply {
                    val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics).toInt()
                    setPadding(pad, 0, pad, 0)
                    textSize = 20f
                    gravity = android.view.Gravity.CENTER
                    text = iconEmoji
                    isClickable = true
                    isFocusable = true
                    setBackgroundResource(typedValue.resourceId)
                }

                if (apiGroupKey == currentSelectedCategory) {
                    tabView.setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(colorSubtitle.data, 45))
                }

                tabView.setOnClickListener {
                    currentSelectedCategory = apiGroupKey
                    renderCategoryTabs()
                    onCategorySelected(apiGroupKey)
                }
                categoryTabsContainer?.addView(tabView)
            }
        }

        searchEmojiInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                val query = s?.toString()?.trim() ?: ""
                if (query.length >= 2) {
                    searchJob = lifecycleScope.launch {
                        delay(350)
                        fetchEmojisFromApi(null, query)
                    }
                } else if (query.isEmpty()) {
                    onCategorySelected(currentSelectedCategory)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnToggleMoreEmojis?.setOnClickListener {
            if (allEmojisCard?.visibility == View.VISIBLE) {
                allEmojisCard.visibility = View.GONE
                btnToggleMoreEmojis.text = "Mostra altre emoji"
                btnToggleMoreEmojis.setIconResource(R.drawable.ic_arrow_down)
            } else {
                allEmojisCard?.visibility = View.VISIBLE
                btnToggleMoreEmojis.text = "Nascondi altre emoji"
                btnToggleMoreEmojis.icon = null

                renderCategoryTabs()
                onCategorySelected(currentSelectedCategory)
            }
        }

        val reactionInput = dialogBinding.addReactionInput
        val sendCustomBtn = dialogBinding.btnSendCustomReaction
        reactionInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                sendCustomBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        sendCustomBtn.setOnClickListener {
            val emoji = reactionInput.text.toString().trim()
            if (isSingleEmoji(emoji)) {
                saveRecentEmoji(emoji)
                sendReaction(message, emoji)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Inserisci una singola emoji", Toast.LENGTH_SHORT).show()
            }
        }

        // --- GESTIONE AZIONE AGGIUNGI/RIMUOVI DAI PREFERITI PER GLI STICKER IN CHAT ---
        if (isStickerMessage(message)) {
            val cleanName = (message.fileName ?: "sticker_${message.id}.webp").replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val safeName = if (cleanName.startsWith("fav_") || cleanName.startsWith("wa_") || cleanName.startsWith("zip_")) cleanName else "fav_$cleanName"
            val isFav = isFavoriteSticker(safeName)

            btnActionFavSticker?.visibility = View.VISIBLE
            btnActionFavSticker?.text = if (isFav) "Rimuovi dai Preferiti ⭐" else "Aggiungi ai Preferiti ⭐"
            btnActionFavSticker?.setIconResource(if (isFav) R.drawable.ic_close else R.drawable.ic_check)

            btnActionFavSticker?.setOnClickListener {
                toggleFavoriteStickerFromChat(message)
                dialog.dismiss()
            }
        } else {
            btnActionFavSticker?.visibility = View.GONE
        }

        if (message.messageType == "text" && !message.isViewOnce) {
            dialogBinding.btnActionCopy.visibility = View.VISIBLE
            dialogBinding.btnActionCopy.setOnClickListener { copyMessageText(message); dialog.dismiss() }
        }
        if (message.userId == userId) {
            dialogBinding.btnActionDelete.visibility = View.VISIBLE
            dialogBinding.btnActionDelete.setOnClickListener {
                dialog.dismiss()
                MaterialAlertDialogBuilder(this@ChatActivity)
                    .setTitle("Elimina")
                    .setMessage("Eliminare per tutti?")
                    .setPositiveButton("Sì") { _, _ -> deleteMessage(message) }
                    .setNegativeButton("No", null)
                    .show()
            }
            if (message.messageType == "text" && !message.isViewOnce) {
                dialogBinding.btnActionEdit.visibility = View.VISIBLE
                dialogBinding.btnActionEdit.setOnClickListener { showEditMessageDialog(message); dialog.dismiss() }
            }
        }
        if (dialogBinding.btnActionCopy.visibility == View.GONE && dialogBinding.btnActionEdit.visibility == View.GONE && dialogBinding.btnActionDelete.visibility == View.GONE && btnActionFavSticker?.visibility != View.VISIBLE) {
            dialogBinding.actionsContainer.visibility = View.GONE
        }

        val copyReactions = message.reactions?.filter { it.reactionEmoji == "📋" }
        if (!copyReactions.isNullOrEmpty()) {
            dialogBinding.layoutCopiedBy.visibility = View.VISIBLE
            val names = copyReactions.joinToString(", ") { if (it.userId == userId) "Tu" else it.username }
            dialogBinding.tvCopiedByList.text = "Copiato da: $names"
        } else {
            dialogBinding.layoutCopiedBy.visibility = View.GONE
        }

        reactionDialog = dialog
        reactionDialog?.setOnDismissListener { reactionDialog = null }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        reactionDialog?.show()
    }

    override fun onShowOptions(message: Message) { reactionDialog?.dismiss(); showEditDeleteOptions(message) }

    override fun onStatusIconClicked(message: Message) {
        ApiClient.instance.getMessageReadBy(userId = userId, messageId = message.id).enqueue(object : Callback<GetReadReceiptsResponse> {
            override fun onResponse(call: Call<GetReadReceiptsResponse>, response: Response<GetReadReceiptsResponse>) {
                if (isFinishing || isDestroyed) return
                if (response.isSuccessful) {
                    val readers = response.body()?.data ?: emptyList()
                    if (isPrivateChat) {
                        if (readers.isNotEmpty()) Toast.makeText(this@ChatActivity, "Letto ${TimeUtils.formatReadTimestamp(readers.first().readAt).lowercase()}", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(this@ChatActivity, "Non ancora letto.", Toast.LENGTH_SHORT).show()
                    } else {
                        showReadReceiptsDialog(readers)
                    }
                }
            }
            override fun onFailure(call: Call<GetReadReceiptsResponse>, t: Throwable) {}
        })
    }

    override fun onReactionChipClicked(message: Message, reactionEmoji: String) {
        if (isFinishing || isDestroyed) return
        val reactions = message.reactions?.filter { it.reactionEmoji == reactionEmoji } ?: return
        if (reactions.isEmpty()) return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_reaction_details, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.reactions_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this); recyclerView.adapter = ReactionDetailAdapter(reactions)
        MaterialAlertDialogBuilder(this).setView(dialogView).setPositiveButton("Chiudi", null).show()
    }

    private fun showReadReceiptsDialog(readers: List<ReadReceiptInfo>) {
        if (isFinishing || isDestroyed) return
        val dialogBinding = DialogReadReceiptsBinding.inflate(layoutInflater)
        dialogBinding.readReceiptsRecyclerView.layoutManager = LinearLayoutManager(this); dialogBinding.readReceiptsRecyclerView.adapter = ReadReceiptsAdapter(readers)
        dialogBinding.emptyView.visibility = if (readers.isEmpty()) View.VISIBLE else View.GONE
        dialogBinding.readReceiptsRecyclerView.visibility = if (readers.isEmpty()) View.GONE else View.VISIBLE
        MaterialAlertDialogBuilder(this).setTitle("Letto da").setView(dialogBinding.root).setPositiveButton("Chiudi", null).show()
    }

    private fun sendReaction(message: Message, emoji: String?) {
        ApiClient.instance.reactToMessage(userId = userId, messageId = message.id, reactionEmoji = emoji).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {}
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {}
        })
    }

    private fun showEditDeleteOptions(message: Message) {
        if (isFinishing || isDestroyed) return
        val options = mutableListOf<String>()

        if (message.messageType == "text" && !message.isViewOnce) {
            options.add("Copia Testo")
        }

        if (message.userId == userId) {
            if (message.messageType == "text" && !message.isViewOnce) {
                options.add("Modifica Messaggio")
            }
            options.add("Elimina Messaggio")
        }

        MaterialAlertDialogBuilder(this).setItems(options.toTypedArray()) { _, which ->
            when (options[which]) {
                "Copia Testo" -> copyMessageText(message)
                "Modifica Messaggio" -> showEditMessageDialog(message)
                "Elimina Messaggio" -> showEditDeleteOptions(message)
            }
        }.show()
    }

    private fun copyMessageText(message: Message) {
        val decryptedMessage = decryptMessageText(message, currentGroupKey, currentGroupIv)

        if (decryptedMessage == null) {
            Toast.makeText(this, "Impossibile decifrare il messaggio.", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("CryptionChat Copia", decryptedMessage)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Testo copiato negli appunti", Toast.LENGTH_SHORT).show()

        if (message.userId != userId) {
            sendReaction(message, "📋")
        }
    }

    private fun showAttachmentOptions() {
        val options = if (isPrivateChat) {
            arrayOf("Fotocamera", "Galleria", "File", "La mia Posizione", "Richiedi Posizione")
        } else {
            arrayOf("Fotocamera", "Galleria", "File", "La mia Posizione")
        }

        MaterialAlertDialogBuilder(this).setTitle("Allega").setItems(options) { _, which ->
            when (which) {
                0 -> if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera() else requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                1 -> pickMediaLauncher.launch(arrayOf("image/*", "video/*"))
                2 -> pickAnyFileLauncher.launch(arrayOf("*/*"))
                3 -> checkLocationPermissionAndSend()
                4 -> sendMessage("[REQ_LOC]")
            }
        }.show()
    }

    private fun saveRecentSticker(fileName: String) {
        val prefs = SecurePrefs.get(this)
        val currentStr = prefs.getString("recent_stickers", "") ?: ""
        val list = currentStr.split(",").filter { it.isNotBlank() }.toMutableList()

        list.remove(fileName)
        list.add(0, fileName)

        if (list.size > 20) {
            list.removeAt(list.size - 1)
        }

        prefs.edit().putString("recent_stickers", list.joinToString(",")).apply()
    }

    private fun isFavoriteSticker(fileName: String): Boolean {
        val prefs = SecurePrefs.get(this)
        val favsStr = prefs.getString("favorite_stickers", "") ?: ""
        val list = favsStr.split(",").filter { it.isNotBlank() }
        return list.contains(fileName)
    }

    private fun toggleFavoriteSticker(fileName: String): Boolean {
        val prefs = SecurePrefs.get(this)
        val favsStr = prefs.getString("favorite_stickers", "") ?: ""
        val list = favsStr.split(",").filter { it.isNotBlank() }.toMutableList()

        val isFavNow = if (list.contains(fileName)) {
            list.remove(fileName)
            false
        } else {
            list.add(0, fileName)
            true
        }

        prefs.edit().putString("favorite_stickers", list.joinToString(",")).apply()
        return isFavNow
    }

    private fun showStickerPreviewDialog(
        file: File,
        bottomSheetDialog: com.google.android.material.bottomsheet.BottomSheetDialog,
        onReloadStickers: () -> Unit
    ) {
        if (isFinishing || isDestroyed) return

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_sticker_preview, null)

        val imgPreviewId = resources.getIdentifier("img_sticker_preview", "id", packageName)
        val btnSendId = resources.getIdentifier("btn_send_sticker", "id", packageName)
        val btnToggleFavId = resources.getIdentifier("btn_toggle_favorite_sticker", "id", packageName)
        val btnDeleteId = resources.getIdentifier("btn_delete_sticker", "id", packageName)

        val imgPreview = if (imgPreviewId != 0) dialogView.findViewById<ImageView>(imgPreviewId) else null
        val btnSend = if (btnSendId != 0) dialogView.findViewById<MaterialButton>(btnSendId) else null
        val btnToggleFav = if (btnToggleFavId != 0) dialogView.findViewById<MaterialButton>(btnToggleFavId) else null
        val btnDelete = if (btnDeleteId != 0) dialogView.findViewById<MaterialButton>(btnDeleteId) else null

        val isFav = isFavoriteSticker(file.name)
        btnToggleFav?.text = if (isFav) "Rimuovi dai Preferiti" else "Aggiungi ai Preferiti ⭐"
        btnToggleFav?.setIconResource(if (isFav) R.drawable.ic_close else R.drawable.ic_check)

        if (imgPreview != null) {
            Glide.with(this)
                .asDrawable()
                .load(file)
                .into(imgPreview)
        }

        val previewDialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        btnSend?.setOnClickListener {
            previewDialog.dismiss()
            bottomSheetDialog.dismiss()
            saveRecentSticker(file.name)

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this@ChatActivity,
                "$packageName.provider",
                file
            )
            uploadFile(uri, "image/webp", null, skipCompression = true)
        }

        btnToggleFav?.setOnClickListener {
            val nowFav = toggleFavoriteSticker(file.name)
            Toast.makeText(this, if (nowFav) "Aggiunto ai Preferiti ⭐" else "Rimosso dai Preferiti", Toast.LENGTH_SHORT).show()
            previewDialog.dismiss()
            onReloadStickers()
        }

        btnDelete?.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Elimina Sticker")
                .setMessage("Sei sicuro di voler eliminare definitivamente questo sticker?")
                .setPositiveButton("Elimina") { _, _ ->
                    if (file.exists()) {
                        file.delete()
                    }
                    removeRecentSticker(file.name)
                    if (isFavoriteSticker(file.name)) {
                        toggleFavoriteSticker(file.name)
                    }
                    Toast.makeText(this, "Sticker eliminato", Toast.LENGTH_SHORT).show()
                    previewDialog.dismiss()
                    onReloadStickers()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }

        previewDialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
        previewDialog.show()
    }



    private fun getFavoriteStickers(allStickers: List<File>): List<File> {
        val prefs = SecurePrefs.get(this)
        val favsStr = prefs.getString("favorite_stickers", "") ?: ""
        val favNames = favsStr.split(",").filter { it.isNotBlank() }

        val stickerMap = allStickers.associateBy { it.name }
        return favNames.mapNotNull { stickerMap[it] }
    }

    private fun removeRecentSticker(fileName: String) {
        val prefs = SecurePrefs.get(this)
        val currentStr = prefs.getString("recent_stickers", "") ?: ""
        val list = currentStr.split(",").filter { it.isNotBlank() }.toMutableList()

        list.remove(fileName)

        prefs.edit().putString("recent_stickers", list.joinToString(",")).apply()
    }

    private fun getRecentStickers(allStickers: List<File>): List<File> {
        val prefs = SecurePrefs.get(this)
        val currentStr = prefs.getString("recent_stickers", "") ?: ""
        val recentNames = currentStr.split(",").filter { it.isNotBlank() }

        val stickerMap = allStickers.associateBy { it.name }
        return recentNames.mapNotNull { stickerMap[it] }
    }

    private fun showStickerPickerDialog() {
        if (isFinishing || isDestroyed) return

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_sticker_picker, null)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.sticker_progress_bar)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tv_empty_stickers)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.rv_stickers)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_sticker_title)
        val tabLayout = dialogView.findViewById<com.google.android.material.tabs.TabLayout>(R.id.tab_layout_stickers)
        val btnScrollTop = dialogView.findViewById<MaterialButton>(R.id.btn_scroll_top_stickers)
        val btnScrollBottom = dialogView.findViewById<MaterialButton>(R.id.btn_scroll_bottom_stickers)
        val btnSort = dialogView.findViewById<MaterialButton>(R.id.btn_sort_stickers)
        val btnExport = dialogView.findViewById<MaterialButton>(R.id.btn_export_stickers)
        val btnImport = dialogView.findViewById<MaterialButton>(R.id.btn_import_stickers)
        val btnDeleteAll = dialogView.findViewById<MaterialButton>(R.id.btn_delete_all_stickers)

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        var currentSortMode = SecurePrefs.get(this).getInt("sticker_sort_mode", 0)

        val savedTab = SecurePrefs.get(this).getInt("last_selected_sticker_tab", 0)
        var currentSelectedTab = savedTab.coerceIn(0, 2)

        tabLayout.removeAllTabs()
        tabLayout.addTab(tabLayout.newTab().setText("Tutti"), currentSelectedTab == 0)
        tabLayout.addTab(tabLayout.newTab().setText("Preferiti ⭐"), currentSelectedTab == 1)
        tabLayout.addTab(tabLayout.newTab().setText("Recenti 🕒"), currentSelectedTab == 2)

        data class FileWithMeta(val file: File, val lastModified: Long, val nameLower: String)

        fun sortStickerListFast(list: List<File>, mode: Int): List<File> {
            val metaList = list.map { FileWithMeta(it, it.lastModified(), it.name.lowercase(Locale.ROOT)) }
            val sortedMeta = when (mode) {
                0 -> metaList.sortedByDescending { it.lastModified }
                1 -> metaList.sortedBy { it.lastModified }
                2 -> metaList.sortedBy { it.nameLower }
                3 -> metaList.sortedByDescending { it.nameLower }
                else -> metaList.sortedByDescending { it.lastModified }
            }
            return sortedMeta.map { it.file }
        }

        val gridLayoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 4)
        lateinit var adapter: StickerAdapter

        var cachedStickerFiles: List<File>? = null

        fun filterAndDisplay(files: List<File>) {
            val sortedStickers = sortStickerListFast(files, currentSortMode)
            val filteredList = when (currentSelectedTab) {
                1 -> getFavoriteStickers(sortedStickers)
                2 -> getRecentStickers(sortedStickers)
                else -> sortedStickers
            }

            val isRecentOrFav = (currentSelectedTab == 1 || currentSelectedTab == 2)
            val listItems = filteredList.map { StickerListItem.StickerItem(it, isRecent = isRecentOrFav) }

            if (listItems.isEmpty()) {
                tvEmpty.text = when (currentSelectedTab) {
                    1 -> "Nessun sticker nei Preferiti ⭐"
                    2 -> "Nessun sticker nei Recenti 🕒"
                    else -> "Nessuno sticker trovato."
                }
                tvEmpty.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
                tvTitle.text = "Sticker (0)"
            } else {
                tvEmpty.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                val categoryName = when (currentSelectedTab) {
                    1 -> "Preferiti ⭐"
                    2 -> "Recenti 🕒"
                    else -> "Tutti"
                }
                tvTitle.text = "$categoryName (${listItems.size})"
                adapter.updateItems(listItems)
            }
        }

        fun loadAndDisplayStickers(forceDiskScan: Boolean = true) {
            lifecycleScope.launch {
                if (cachedStickerFiles == null || forceDiskScan) {
                    progressBar.visibility = View.VISIBLE
                    val loadedAllStickers = withContext(Dispatchers.IO) {
                        WhatsAppStickerImporter.getOrImportStickers(this@ChatActivity)
                    }
                    cachedStickerFiles = loadedAllStickers
                    progressBar.visibility = View.GONE
                }

                filterAndDisplay(cachedStickerFiles ?: emptyList())
            }
        }

        tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                currentSelectedTab = tab.position
                SecurePrefs.get(this@ChatActivity).edit().putInt("last_selected_sticker_tab", currentSelectedTab).apply()

                if (cachedStickerFiles != null) {
                    filterAndDisplay(cachedStickerFiles!!)
                } else {
                    loadAndDisplayStickers(forceDiskScan = true)
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })

        btnScrollTop?.setOnClickListener {
            if (adapter.itemCount > 0) {
                recyclerView.scrollToPosition(0)
            }
        }

        btnScrollBottom?.setOnClickListener {
            if (adapter.itemCount > 0) {
                recyclerView.scrollToPosition(adapter.itemCount - 1)
            }
        }

        btnSort?.setOnClickListener {
            val sortOptions = arrayOf(
                "📅 Dal più recente (Default)",
                "⏳ Dal meno recente",
                "🔤 Nome (A - Z)",
                "🔤 Nome (Z - A)"
            )

            MaterialAlertDialogBuilder(this@ChatActivity)
                .setTitle("Ordina Sticker")
                .setSingleChoiceItems(sortOptions, currentSortMode) { dialogSort, which ->
                    currentSortMode = which
                    SecurePrefs.get(this@ChatActivity).edit().putInt("sticker_sort_mode", which).apply()
                    if (cachedStickerFiles != null) {
                        filterAndDisplay(cachedStickerFiles!!)
                    } else {
                        loadAndDisplayStickers(forceDiskScan = true)
                    }
                    dialogSort.dismiss()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }

        btnImport?.setOnClickListener {
            dialog.dismiss()
            pickStickerZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
        }

        btnExport?.setOnClickListener {
            dialog.dismiss()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestMediaPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                Toast.makeText(this, "Abilita il permesso notifiche per vedere la barra di avanzamento!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Esportazione avviata in background. Guarda la barra delle notifiche!", Toast.LENGTH_LONG).show()
            StickerExportService.startExport(this@ChatActivity)
        }

        btnDeleteAll?.setOnClickListener {
            MaterialAlertDialogBuilder(this@ChatActivity)
                .setTitle("Elimina Tutti gli Sticker")
                .setMessage("Sei sicuro di voler eliminare tutti gli sticker salvati e la cronologia? L'azione è irreversibile.")
                .setPositiveButton("Elimina") { _, _ ->
                    lifecycleScope.launch {
                        val success = StickerZipManager.deleteAllStickers(this@ChatActivity)
                        if (success) {
                            Toast.makeText(this@ChatActivity, "Tutti gli sticker sono stati eliminati.", Toast.LENGTH_SHORT).show()
                            cachedStickerFiles = emptyList()
                            loadAndDisplayStickers(forceDiskScan = true)
                        } else {
                            Toast.makeText(this@ChatActivity, "Errore durante l'eliminazione.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Annulla", null)
                .show()
        }

        adapter = StickerAdapter(
            items = emptyList(),
            onStickerClicked = { stickerFile ->
                saveRecentSticker(stickerFile.name)
                dialog.dismiss()

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@ChatActivity,
                    "$packageName.provider",
                    stickerFile
                )
                uploadFile(uri, "image/webp", null, skipCompression = true)
            },
            onStickerLongClicked = { stickerFile ->
                showStickerPreviewDialog(stickerFile, dialog) {
                    loadAndDisplayStickers(forceDiskScan = true)
                }
            }
        )

        gridLayoutManager.spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == StickerAdapter.TYPE_HEADER) 4 else 1
            }
        }

        recyclerView.layoutManager = gridLayoutManager
        recyclerView.adapter = adapter

        dialog.setContentView(dialogView)
        // Forza sempre la scansione del disco ad ogni apertura
        loadAndDisplayStickers(forceDiskScan = true)
        dialog.show()
    }

    private fun launchCamera() {
        lifecycleScope.launch { try { createImageFile()?.let { uri -> photoUri = uri; takePictureLauncher.launch(uri) } } catch (ex: IOException) { Toast.makeText(this@ChatActivity, "Errore file foto.", Toast.LENGTH_SHORT).show() } }
    }

    private fun isStickerMessage(message: Message): Boolean {
        val mime = message.fileMimeType?.lowercase(Locale.ROOT) ?: ""
        val name = message.fileName?.lowercase(Locale.ROOT) ?: ""
        return message.messageType == "image" && (mime == "image/webp" || name.endsWith(".webp"))
    }

    private fun toggleFavoriteStickerFromChat(message: Message) {
        val localFile = FileUtils.getLocalFile(this, message)
        if (!localFile.exists() || localFile.length() == 0L) {
            Toast.makeText(this, "Scarica prima lo sticker.", Toast.LENGTH_SHORT).show()
            return
        }

        val appStickerDir = File(filesDir, "cryption_stickers").apply { mkdirs() }
        val cleanName = (message.fileName ?: "sticker_${message.id}.webp").replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val safeName = if (cleanName.startsWith("fav_") || cleanName.startsWith("wa_") || cleanName.startsWith("zip_")) {
            cleanName
        } else {
            "fav_$cleanName"
        }

        val destFile = File(appStickerDir, safeName)
        if (!destFile.exists()) {
            try {
                localFile.copyTo(destFile, overwrite = true)
                destFile.setLastModified(System.currentTimeMillis())
            } catch (e: Exception) {
                Log.e(TAG, "Errore salvataggio sticker nei preferiti", e)
            }
        }

        val nowFav = toggleFavoriteSticker(destFile.name)
        Toast.makeText(this, if (nowFav) "Sticker aggiunto ai Preferiti ⭐" else "Sticker rimosso dai Preferiti", Toast.LENGTH_SHORT).show()
    }


    @Throws(IOException::class)
    private fun createImageFile(): Uri? {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val file = File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
        return FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", file)
    }

    private fun getFormattedDate(context: Context, date: Date): String {
        val cal = Calendar.getInstance(); val today = cal.time; cal.add(Calendar.DAY_OF_YEAR, -1); val yesterday = cal.time; val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return when (sdf.format(date)) { sdf.format(today) -> "Oggi"; sdf.format(yesterday) -> "Ieri"; else -> SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ITALIAN).format(date) }
    }

    private fun processMessagesWithDateHeaders(messages: List<Message>): List<ListItem> {
        val items = mutableListOf<ListItem>(); var lastDate: String? = null
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }
        for (msg in messages) {
            fmt.parse(msg.timestamp)?.let { date ->
                val fDate = getFormattedDate(this, date)
                if (fDate != lastDate) { items.add(ListItem.DateItem(fDate)); lastDate = fDate }
            }
            items.add(ListItem.MessageItem(msg))
        }
        return items
    }

    override fun onViewOnceMessageClicked(message: Message) {
        if (message.userId == userId) { Toast.makeText(this, "Non puoi aprirlo.", Toast.LENGTH_SHORT).show(); return }
        if (message.isViewedByMe) { Toast.makeText(this, "Già aperto.", Toast.LENGTH_SHORT).show(); return }
        when (message.messageType) {
            "text" -> showViewOnceTextDialog(message)
            "image" -> showViewOnceImageDialog(message)
            "file", "audio" -> showViewOnceFileDialog(message)
            else -> Toast.makeText(this, "Non supportato.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun markAsViewed(message: Message) {
        val pos = messageAdapter.findMessagePositionById(message.id)
        if (pos != -1) {
            val item = messageAdapter.getItemAt(pos) as? ListItem.MessageItem
            if (item != null) messageAdapter.updateItemAt(pos, ListItem.MessageItem(item.message.copy(isViewedByMe = true)))
        }
        ApiClient.instance.markViewOnceOpened(userId = userId, messageId = message.id).enqueue(object : Callback<BaseResponse> { override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {}; override fun onFailure(call: Call<BaseResponse>, t: Throwable) {} })
    }

    private fun showViewOnceTextDialog(message: Message) {
        val text = decryptMessageText(message, currentGroupKey, currentGroupIv)
        if (text == null) return
        markAsViewed(message)

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_view_once_text, null)
        viewOnceDialog = MaterialAlertDialogBuilder(this).setView(dialogView).setCancelable(false).create()

        val textContent = dialogView.findViewById<TextView>(R.id.view_once_text_content)
        val btnClose = dialogView.findViewById<MaterialButton>(R.id.close_button)

        textContent.text = text
        btnClose.setOnClickListener {
            viewOnceDialog?.dismiss()
        }

        viewOnceDialog?.setOnDismissListener { viewOnceDialog = null }
        viewOnceDialog?.show()
    }

    private fun showViewOnceImageDialog(message: Message) {
        if (isFinishing || isDestroyed) return

        viewOnceDialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_view_once_image, null)
        viewOnceDialog?.setContentView(view)

        val imageView = view.findViewById<PhotoView>(R.id.view_once_image)
        val btnClose = view.findViewById<MaterialButton>(R.id.close_button)

        val localFile = FileUtils.getLocalFile(this, message)

        fun onReady() {
            if(!isFinishing && !isDestroyed) {
                markAsViewed(message)
                viewOnceDialog?.show()
                viewOnceDialog?.setOnDismissListener {
                    if (localFile.exists()) PanicHelper.secureDelete(localFile)
                    viewOnceDialog = null
                }
            }
        }

        btnClose.setOnClickListener {
            viewOnceDialog?.dismiss()
        }

        if (localFile.exists()) {
            Glide.with(this).load(localFile).into(imageView)
            onReady()
        } else {
            lifecycleScope.launch {
                downloadAndDecryptFile(message, { f ->
                    if (!isFinishing && !isDestroyed) {
                        Glide.with(this@ChatActivity).load(f).into(imageView)
                        onReady()
                    }
                }, { e ->
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this@ChatActivity, "Errore: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }
    }

    private fun showViewOnceFileDialog(message: Message) {
        val localFile = FileUtils.getLocalFile(this, message)

        fun showDialog(file: File) {
            if (message.messageType == "audio") {
                showViewOnceAudioDialog(file, message)
                return
            }

            if (isFinishing || isDestroyed) return

            val dialogView = LayoutInflater.from(this@ChatActivity).inflate(R.layout.dialog_view_once_file, null)
            viewOnceDialog = MaterialAlertDialogBuilder(this@ChatActivity).setView(dialogView).setCancelable(false).create()

            val fileNameText = dialogView.findViewById<TextView>(R.id.view_once_file_name)
            val fileSizeText = dialogView.findViewById<TextView>(R.id.view_once_file_size)
            val btnOpenFile = dialogView.findViewById<MaterialButton>(R.id.btn_open_file)
            val btnClose = dialogView.findViewById<MaterialButton>(R.id.close_button)

            fileNameText.text = message.fileName ?: "Documento"
            fileSizeText.text = FileUtils.getFormattedFileSize(message.fileSize ?: file.length())

            markAsViewed(message)

            btnOpenFile.setOnClickListener {
                try {
                    val uri = FileProvider.getUriForFile(this@ChatActivity, "$packageName.provider", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, message.fileMimeType ?: "application/octet-stream")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@ChatActivity, "Nessuna app trovata per aprire questo formato.", Toast.LENGTH_LONG).show()
                }
            }

            btnClose.setOnClickListener {
                viewOnceDialog?.dismiss()
            }

            viewOnceDialog?.setOnDismissListener {
                if (file.exists()) {
                    PanicHelper.secureDelete(file)
                }
                viewOnceDialog = null
            }

            viewOnceDialog?.show()
        }

        if (localFile.exists() && localFile.length() > 0) {
            showDialog(localFile)
        } else {
            Toast.makeText(this, "Download file sicuro in corso...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                downloadAndDecryptFile(
                    message,
                    onFinish = { f -> showDialog(f) },
                    onError = { e -> Toast.makeText(this@ChatActivity, "Errore: ${e.message}", Toast.LENGTH_SHORT).show() }
                )
            }
        }
    }

    private fun showViewOnceAudioDialog(file: File, message: Message) {
        if (isFinishing || isDestroyed) return
        val dialogBinding = DialogViewOnceAudioBinding.inflate(layoutInflater)
        viewOnceDialog = MaterialAlertDialogBuilder(this).setView(dialogBinding.root).setCancelable(false).create()

        var localMediaPlayer: MediaPlayer? = MediaPlayer()
        val handler = Handler(Looper.getMainLooper())
        var updateProgressRunnable: Runnable? = null

        fun formatDuration(millis: Int): String {
            return String.format(
                Locale.getDefault(),
                "%02d:%02d",
                java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(millis.toLong()),
                java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(millis.toLong()) -
                        java.util.concurrent.TimeUnit.MINUTES.toSeconds(java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(millis.toLong()))
            )
        }

        try {
            localMediaPlayer?.setDataSource(file.absolutePath)
            localMediaPlayer?.prepare()
            val duration = localMediaPlayer?.duration ?: 0
            dialogBinding.seekBar.max = duration
            dialogBinding.durationText.text = "00:00 / ${formatDuration(duration)}"
        } catch (e: Exception) {
            Log.e(TAG, "Errore caricamento audio effimero", e)
            Toast.makeText(this, "Impossibile caricare l'audio.", Toast.LENGTH_SHORT).show()
        }

        updateProgressRunnable = object : Runnable {
            override fun run() {
                localMediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        dialogBinding.seekBar.progress = mp.currentPosition
                        dialogBinding.durationText.text = "${formatDuration(mp.currentPosition)} / ${formatDuration(mp.duration)}"
                        handler.postDelayed(this, 100)
                    }
                }
            }
        }

        dialogBinding.playPauseButton.setOnClickListener {
            localMediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.pause()
                    dialogBinding.playPauseButton.setIconResource(R.drawable.ic_play_arrow)
                    handler.removeCallbacks(updateProgressRunnable!!)
                } else {
                    mp.start()
                    dialogBinding.playPauseButton.setIconResource(R.drawable.ic_pause)
                    handler.post(updateProgressRunnable!!)
                }
            }
        }

        localMediaPlayer?.setOnCompletionListener { mp ->
            dialogBinding.playPauseButton.setIconResource(R.drawable.ic_play_arrow)
            dialogBinding.seekBar.progress = 0
            dialogBinding.durationText.text = "00:00 / ${formatDuration(mp.duration)}"
            handler.removeCallbacks(updateProgressRunnable!!)
        }

        dialogBinding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    localMediaPlayer?.seekTo(progress)
                    dialogBinding.durationText.text = "${formatDuration(progress)} / ${formatDuration(localMediaPlayer?.duration ?: 0)}"
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        dialogBinding.closeButton.setOnClickListener {
            viewOnceDialog?.dismiss()
        }

        viewOnceDialog?.setOnDismissListener {
            try {
                if (localMediaPlayer?.isPlaying == true) {
                    localMediaPlayer?.stop()
                }
                localMediaPlayer?.release()
                localMediaPlayer = null
                updateProgressRunnable?.let { handler.removeCallbacks(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Errore rilascio MediaPlayer locale", e)
            }

            if (file.exists()) {
                PanicHelper.secureDelete(file)
            }
            viewOnceDialog = null
        }

        markAsViewed(message)
        viewOnceDialog?.show()
    }

    private suspend fun downloadAndDecryptFile(message: Message, onFinish: (File) -> Unit, onError: (Exception) -> Unit) {
        val fileId = message.fileId
        val decryptedMeta = withContext(Dispatchers.IO) {
            decryptMessageText(message, currentGroupKey, currentGroupIv)
        }
        val meta = decryptedMeta?.let {
            try { Gson().fromJson(it, FileMetadata::class.java) } catch (_: Exception) { null }
        }
        val key = meta?.fileKeyHex?.takeIf { it.isNotBlank() } ?: currentGroupKey
        val iv = meta?.fileIvHex?.takeIf { it.isNotBlank() } ?: currentGroupIv
        if (key.isNullOrBlank() || iv.isNullOrBlank() || fileId == null) { onError(Exception("Dati mancanti")); return }

        val temp = File(cacheDir, "temp_vo_${fileId}_${System.currentTimeMillis()}.enc")
        val final = FileUtils.getLocalFile(this, message)

        var attempt = 0
        var success = false
        var lastException: Exception? = null

        while (attempt < 3 && !success) {
            try {
                attempt++
                withContext(Dispatchers.IO) {
                    val resp = ApiClient.instance.downloadFile("${ApiClient.BASE_URL}download.php?user_id=$userId&file_id=$fileId").execute()
                    if (!resp.isSuccessful) throw IOException("DL err: ${resp.code()}")

                    val body = resp.body() ?: throw IOException("Corpo vuoto")
                    val contentType = body.contentType()
                    if (contentType != null && (contentType.type == "text" || contentType.subtype.contains("json") || contentType.subtype.contains("html"))) {
                        throw IOException("Il file non esiste più sul server o è inaccessibile.")
                    }

                    val totalSize = body.contentLength()
                    val copiedSize = body.byteStream().use { i ->
                        FileOutputStream(temp).use { o ->
                            i.copyTo(o)
                        }
                    }

                    if (totalSize > 0 && copiedSize != totalSize) {
                        throw IOException("La connessione si è interrotta prima della fine del download.")
                    }

                    FileInputStream(temp).use { i ->
                        FileOutputStream(final).use { o ->
                            EncryptionHelper.decryptStream(i, o, key, iv)
                        }
                    }
                }
                success = true
            } catch (e: Exception) {
                lastException = e
                if (temp.exists()) temp.delete()
                if (final.exists()) final.delete()
                if (attempt < 3) {
                    delay(500)
                }
            }
        }

        if (temp.exists()) temp.delete()

        withContext(Dispatchers.Main) {
            if (success) {
                onFinish(final)
            } else {
                val errorMsg = lastException?.message ?: "Errore di rete persistente"
                onError(Exception(errorMsg, lastException))
            }
        }
    }

    private fun showMentionPopup() {
        // Evita di creare e sovrapporre istanze multiple dello stesso dialog se è già aperto
        if (mentionDialog?.isShowing == true) return
        if (groupMembers.isEmpty() || isFinishing || isDestroyed) return

        val names = mutableListOf("Tutti")
        names.addAll(groupMembers.filter { it.userId != userId }.map { it.username })

        mentionDialog = AlertDialog.Builder(this).setTitle("Menziona").setItems(names.toTypedArray()) { _, which ->
            val name = names[which]
            val text = binding.editTextMessage.text.toString()
            val newText = text.substring(0, text.lastIndexOf("@")) + "@$name "
            binding.editTextMessage.setText(newText)
            binding.editTextMessage.setSelection(newText.length)
            if (name == "Tutti") {
                mentionedUsersMap["@Tutti"] = 0
            } else {
                groupMembers.find { it.username == name }?.let {
                    mentionedUsersMap["@$name"] = it.userId
                }
            }
        }.create()

        // Aggiunge temporaneamente il flag FLAG_NOT_FOCUSABLE per impedire che il Dialog
        // sottragga il focus alla tastiera virtuale. Questo permette di intercettare il tocco
        // sugli elementi della lista immediatamente al primo clic.
        mentionDialog?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )

        mentionDialog?.setOnDismissListener { mentionDialog = null }
        mentionDialog?.show()

        // Rimuove il flag subito dopo l'apertura in modo che il Dialog possa continuare
        // a intercettare correttamente gli eventi del tasto Indietro (back) fisico del dispositivo.
        mentionDialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    private fun setupInputListeners() {

        binding.buttonSticker.setOnClickListener {
            showStickerPickerDialog()
        }

        binding.editTextMessage.imeOptions = binding.editTextMessage.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        binding.editTextMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s.toString()
                if (text.isNotEmpty()) {
                    binding.buttonSend.visibility = View.VISIBLE
                    binding.buttonRecordAudio.visibility = View.GONE
                    binding.buttonViewOnce.isEnabled = true
                    if (!isTyping) {
                        isTyping = true
                        sendTypingStatus(true)
                        typingHandler.removeCallbacks(keepTypingRunnable)
                        typingHandler.postDelayed(keepTypingRunnable, TYPING_KEEPALIVE_MS)
                    }
                    typingHandler.removeCallbacks(stoppedTypingRunnable)
                    typingHandler.postDelayed(stoppedTypingRunnable, TYPING_TIMEOUT)

                    if (text.endsWith("@")) showMentionPopup() else mentionDialog?.dismiss()

                    val lastWord = text.substringAfterLast(" ")
                    if (lastWord.startsWith("/")) {
                        showCommandPopup()
                    } else {
                        commandPopup?.dismiss()
                    }

                } else {
                    binding.buttonSend.visibility = View.GONE
                    binding.buttonRecordAudio.visibility = View.VISIBLE
                    binding.buttonViewOnce.isEnabled = true
                    typingHandler.removeCallbacks(stoppedTypingRunnable)
                    typingHandler.removeCallbacks(keepTypingRunnable)
                    if (isTyping) {
                        isTyping = false
                        sendTypingStatus(false)
                    }
                    mentionDialog?.dismiss()
                    commandPopup?.dismiss()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.buttonSend.setOnClickListener { val msg = binding.editTextMessage.text.toString().trim(); if (msg.isNotEmpty()) sendMessage(msg) }
        setupAudioRecording()
    }

    private fun showCommandPopup() {
        if (commandPopup == null) {
            commandPopup = androidx.appcompat.widget.ListPopupWindow(this)
            commandPopup?.anchorView = binding.textInputCard
            commandPopup?.setDropDownGravity(android.view.Gravity.TOP)

            commandPopup?.setOnItemClickListener { _, _, position, _ ->
                val selectedCmd = rawCommands[position]
                val currentText = binding.editTextMessage.text.toString()
                val newText = currentText.substringBeforeLast("/") + selectedCmd + " "
                binding.editTextMessage.setText(newText)
                binding.editTextMessage.setSelection(newText.length)
                commandPopup?.dismiss()
            }
        }
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, commandDescriptions)
        commandPopup?.setAdapter(adapter)
        if (commandPopup?.isShowing == false) commandPopup?.show()
    }

    private fun setupAudioRecording() {
        binding.buttonRecordAudio.setOnTouchListener { v, event ->
            if (currentAdminsOnlyChat && !isCurrentUserAdmin) return@setOnTouchListener true
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = event.rawX
                    initialY = event.rawY
                    isRecordingCancelled = false
                    isRecordingLocked = false
                    checkPermissionsAndStartRecording()
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isRecordingCancelled && !isRecordingLocked) {
                        val diffX = initialX - event.rawX
                        val diffY = initialY - event.rawY

                        if (diffX > 150f && diffX > diffY) {
                            isRecordingCancelled = true
                            audioRecorderHelper.cancelRecording()
                            resetRecordingUI()
                            Toast.makeText(this, "Annullato", Toast.LENGTH_SHORT).show()
                        }
                        else if (diffY > 150f && diffY > diffX) {
                            isRecordingLocked = true
                            lockRecordingUI()
                        }
                        else {
                            binding.recordingLayout.translationX = if (diffX > 0) -diffX / 2 else 0f
                            binding.buttonRecordAudio.translationY = if (diffY > 0) -diffY / 2 else 0f
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    binding.recordingLayout.translationX = 0f
                    binding.buttonRecordAudio.translationY = 0f
                    v.parent.requestDisallowInterceptTouchEvent(false)

                    if (!isRecordingCancelled && !isRecordingLocked) {
                        stopRecordingAndSend()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    binding.recordingLayout.translationX = 0f
                    binding.buttonRecordAudio.translationY = 0f
                    v.parent.requestDisallowInterceptTouchEvent(false)

                    if (!isRecordingLocked) {
                        audioRecorderHelper.cancelRecording()
                        stopRecordingTimer()
                        resetRecordingUI()
                        audioFilePath = null
                    }
                    true
                }
                else -> false
            }
        }

        binding.buttonDeleteRecording.setOnClickListener {
            audioRecorderHelper.cancelRecording()
            stopRecordingTimer()
            resetRecordingUI()
            audioFilePath = null
        }

        binding.buttonSendRecording.setOnClickListener {
            stopRecordingAndSend(force = true)
        }
    }

    private fun checkPermissionsAndStartRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        else startRecording()
    }

    private fun startRecording() {
        binding.textInputLayout.visibility = View.GONE
        binding.recordingLayout.visibility = View.VISIBLE
        binding.recordingTimeText.text = "00:00"

        binding.recordingMicIcon.visibility = View.VISIBLE
        binding.buttonDeleteRecording.visibility = View.GONE
        binding.swipeHintText.visibility = View.VISIBLE
        binding.swipeHintText.text = "< Annulla  |  Blocca ^"

        audioFilePath = audioRecorderHelper.startRecording()
        recordStartTime = System.currentTimeMillis()
        if (audioFilePath != null) {
            startRecordingTimer()
        } else {
            Toast.makeText(this, "Errore avvio", Toast.LENGTH_SHORT).show()
            resetRecordingUI()
        }
    }

    private fun stopRecordingAndSend(force: Boolean = false) {
        val duration = System.currentTimeMillis() - recordStartTime
        val success = audioRecorderHelper.stopRecording(); stopRecordingTimer()
        if (success && (duration >= MIN_RECORDING_DURATION_MS || force)) {
            audioFilePath?.let { path -> val file = File(path); if (file.exists()) uploadFile(FileProvider.getUriForFile(this, "$packageName.provider", file), "audio/m4a", null) }
        } else audioRecorderHelper.cancelRecording()
        resetRecordingUI()
    }

    private fun startRecordingTimer() {
        stopRecordingTimer(); recordingTime = 0
        recordingJob = lifecycleScope.launch {
            while (isActive) { delay(1000); recordingTime++; binding.recordingTimeText.text = String.format("%02d:%02d", recordingTime / 60, recordingTime % 60) }
        }
    }

    private fun stopRecordingTimer() { recordingJob?.cancel(); recordingJob = null }

    private fun resetRecordingUI() {
        binding.recordingLayout.visibility = View.GONE
        binding.textInputLayout.visibility = View.VISIBLE
        binding.textInputCard.visibility = View.VISIBLE

        binding.recordingLayout.translationX = 0f
        binding.buttonRecordAudio.translationY = 0f

        binding.buttonRecordAudio.visibility = View.VISIBLE
        binding.buttonSendRecording.visibility = View.GONE
        binding.buttonDeleteRecording.visibility = View.GONE

        val typedValue = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true)
        binding.swipeHintText.setTextColor(typedValue.data)
        binding.swipeHintText.visibility = View.VISIBLE

        isRecordingLocked = false
    }

    private fun lockRecordingUI() {
        binding.recordingLayout.translationX = 0f
        binding.buttonRecordAudio.translationY = 0f

        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (e: Exception) {}

        binding.swipeHintText.visibility = View.GONE
        binding.recordingMicIcon.visibility = View.VISIBLE

        binding.buttonRecordAudio.visibility = View.GONE
        binding.buttonSendRecording.visibility = View.VISIBLE
        binding.buttonDeleteRecording.visibility = View.VISIBLE
    }

    private fun prepareReply(message: Message) {
        replyingToMessage = message
        binding.replyPreviewLayout.visibility = View.VISIBLE
        binding.replyPreviewUsername.text = message.username

        val content = decryptMessageText(message, currentGroupKey, currentGroupIv)

        binding.replyPreviewMessage.text = when {
            message.isViewOnce -> "Messaggio effimero"
            message.messageType == "image" -> "📷 Foto"
            message.messageType == "audio" -> "🎤 Audio"
            message.messageType == "file" -> "📁 File"
            content?.contains("[REQ_LOC]") == true -> "📍 Richiesta posizione"
            content?.contains("https://maps.google.com/?q=") == true -> "📍 Posizione condivisa"
            else -> content ?: "Messaggio"
        }

        binding.replyPreviewLayout.setOnClickListener {
            val position = messageAdapter.findMessagePositionById(message.id)
            if (position != -1) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(binding.editTextMessage.windowToken, 0)
                val layoutManager = binding.recyclerViewMessages.layoutManager as LinearLayoutManager
                if (Math.abs(position - layoutManager.findFirstVisibleItemPosition()) > 15) {
                    binding.recyclerViewMessages.scrollToPosition(position)
                } else {
                    binding.recyclerViewMessages.smoothScrollToPosition(position)
                }
                uiHandler.postDelayed({ highlightMessageAt(position) }, 200)
            } else {
                Toast.makeText(this, "Messaggio non trovato nella cronologia recente.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearReplyState() {
        replyingToMessage = null
        binding.replyPreviewLayout.visibility = View.GONE
        binding.replyPreviewLayout.setOnClickListener(null)
    }

    override fun onReplyClicked(messageId: Int) {
        val position = messageAdapter.findMessagePositionById(messageId)
        if (position != -1) {
            val layoutManager = binding.recyclerViewMessages.layoutManager as LinearLayoutManager
            if (Math.abs(position - layoutManager.findFirstVisibleItemPosition()) > 15) {
                binding.recyclerViewMessages.scrollToPosition(position)
            } else {
                binding.recyclerViewMessages.smoothScrollToPosition(position)
            }
            uiHandler.postDelayed({ highlightMessageAt(position) }, 200)
        } else {
            Toast.makeText(this, "Messaggio non trovato.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateChatInputState() {
        if (currentAdminsOnlyChat && !isCurrentUserAdmin) { binding.layoutChatboxContainer.visibility = View.GONE; binding.adminsOnlyBanner.visibility = View.VISIBLE }
        else { binding.layoutChatboxContainer.visibility = View.VISIBLE; binding.adminsOnlyBanner.visibility = View.GONE }
    }

    private fun sendTypingStatus(isTyping: Boolean) {
        if (isTyping && SessionLifecycleHelper.isTypingIndicatorDisabled(SecurePrefs.get(this))) return
        ApiClient.instance.updateTypingStatus(userId = userId, groupId = groupId, isTyping = isTyping).enqueue(object : Callback<BaseResponse> { override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {}; override fun onFailure(call: Call<BaseResponse>, t: Throwable) {} })
    }

    private fun updateTypingUI(users: List<String>) {
        val others = users.filter { it != username }
        if (others.isNotEmpty()) {
            val typingText = if (others.size == 1) {
                "${others[0]} sta scrivendo..."
            } else {
                "Più persone stanno scrivendo..."
            }
            toolbarBinding.toolbarSubtitleStatus.text = typingText

            val typedValue = TypedValue()
            theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
            toolbarBinding.toolbarSubtitleStatus.setTextColor(typedValue.data)

            toolbarBinding.toolbarSubtitleStatus.visibility = View.VISIBLE
        } else {
            if (isPrivateChat) {
                updateToolbarStatus(lastOtherUserIsOnline, lastOtherUserLastSeen)
            } else {
                toolbarBinding.toolbarSubtitleStatus.visibility = View.GONE
            }
        }
    }

    private fun setChatInputEnabled(enabled: Boolean, hint: String? = null) {
        binding.editTextMessage.isEnabled = enabled
        binding.buttonAttach.isEnabled = enabled
        binding.buttonSticker.isEnabled = enabled // <-- AGGIUNGI QUESTA RIGA
        binding.buttonSend.isEnabled = enabled
        binding.buttonRecordAudio.isEnabled = enabled
        binding.editTextMessage.hint = if (!enabled && hint != null) hint else "Scrivi un messaggio..."
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbarBinding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        updateToolbarUI()
        toolbarBinding.toolbarClickableArea.setOnClickListener {
            if (isPrivateChat) {
                showSafetyEmojisDialog()
            } else {
                fetchAndShowCustomGroupMembersDialog()
            }
        }
        toolbarBinding.toolbarGroupImage.setOnClickListener { showImageDialog() }
    }

    private fun showImageDialog() {
        if (isFinishing || isDestroyed) return
        if (currentGroupPictureUrl.isNullOrBlank()) return
        val dialog = Dialog(this); currentEditorDialog = dialog
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_image_viewer, null); dialog.setContentView(view)
        val img = view.findViewById<PhotoView>(R.id.full_screen_image)
        val closeButton = view.findViewById<ImageButton>(R.id.close_button)
        Glide.with(this).load(ApiClient.BASE_URL + currentGroupPictureUrl).placeholder(if (isPrivateChat) R.drawable.ic_person else R.drawable.ic_group).into(img)
        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
        dialog.setOnDismissListener { currentEditorDialog = null }
        dialog.show()
    }

    private fun updateToolbarUI() {
        if (isFinishing || isDestroyed) return

        val isZkActive = currentE2eeVersion >= 2 || SecurePrefs.get(this).getBoolean("is_zk_$groupId", false)
        val lockIcon = if (isZkActive) " 🔒" else ""

        toolbarBinding.toolbarTitle.text = if (isPrivateChat) {
            extractPeerNameFromPrivateGroupName() + lockIcon
        } else {
            groupName + lockIcon
        }

        val ph = if (isPrivateChat) R.drawable.ic_person else R.drawable.ic_group
        if (!currentGroupPictureUrl.isNullOrBlank()) {
            Glide.with(this).load(ApiClient.BASE_URL + currentGroupPictureUrl).placeholder(ph).into(toolbarBinding.toolbarGroupImage)
        } else {
            toolbarBinding.toolbarGroupImage.setImageResource(ph)
        }
    }

    private fun showSafetyEmojisDialog(targetPeerId: Int? = null) {
        if (isFinishing || isDestroyed) return

        val resolvedPeerId = targetPeerId ?: if (isPrivateChat) {
            otherUserId.takeIf { it > 0 } ?: groupMembers.firstOrNull { it.userId != userId }?.userId ?: -1
        } else {
            -1
        }

        if (!isPrivateChat && resolvedPeerId <= 0) {
            fetchAndShowCustomGroupMembersDialog()
            return
        }

        if (resolvedPeerId <= 0) {
            Toast.makeText(this, "Caricamento identità di sicurezza in corso...", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val myIdentity = IdentityKeyManager.ensureLocalIdentity(applicationContext, userId)
                E2eeSessionOrchestrator.syncPeerIdentityKeysSync(
                    context = applicationContext,
                    myUserId = userId,
                    peerIds = listOf(resolvedPeerId),
                    minIntervalMs = 0L
                )
                val peerEntity = IdentityKeyManager.getTrustedIdentity(applicationContext, resolvedPeerId)
                    ?: E2eeSessionOrchestrator.fetchAndPinPeerIdentitySync(applicationContext, userId, resolvedPeerId)
                Pair(myIdentity, peerEntity)
            }

            if (isFinishing || isDestroyed) return@launch
            val myIdentity = loaded.first
            val peerEntity = loaded.second
            if (peerEntity == null) {
                Toast.makeText(this@ChatActivity, "Chiave identità del contatto non ancora disponibile.", Toast.LENGTH_LONG).show()
                return@launch
            }

            val peerName = groupMembers.find { it.userId == resolvedPeerId }?.username
                ?: if (isPrivateChat) extractPeerNameFromPrivateGroupName().ifBlank { "Utente #$resolvedPeerId" }
                else "Utente #$resolvedPeerId"

            val safetyNumber = EcdhHelper.getSafetyNumber60Digits(
                userId, myIdentity.ikSignPubB64, myIdentity.ikDhPubB64,
                resolvedPeerId, peerEntity.ikSignPubB64, peerEntity.ikDhPubB64
            )
            val safetyEmojis = EcdhHelper.getSafetyEmojis66Bit(
                userId, myIdentity.ikSignPubB64, myIdentity.ikDhPubB64,
                resolvedPeerId, peerEntity.ikSignPubB64, peerEntity.ikDhPubB64
            )
            val myFp = EcdhHelper.computeIdentityFingerprint(userId, myIdentity.ikSignPubB64, myIdentity.ikDhPubB64)
            val qrPayload = "cryptionchat://verify?v=2&uid=$userId&fp=$myFp"
            val qrBitmap = QrCodeHelper.generateQrBitmap(qrPayload, 420, 420)

            val sheetView = LayoutInflater.from(this@ChatActivity).inflate(R.layout.dialog_safety_number, null)
            val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this@ChatActivity)
            sheet.setContentView(sheetView)
            sheet.setOnShowListener {
                val maxHeight = (resources.displayMetrics.heightPixels * 0.90f).toInt()
                sheetView.layoutParams = sheetView.layoutParams?.apply {
                    height = maxHeight
                }
                sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { container ->
                    val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(container)
                    behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                    behavior.skipCollapsed = true
                    behavior.isDraggable = true
                }
            }

            val btnCloseSheet = sheetView.findViewById<ImageButton>(R.id.btn_close_safety_sheet)
            val cardStatus = sheetView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_safety_status)
            val tvPeerName = sheetView.findViewById<TextView>(R.id.tv_safety_peer_name)
            val cardStatusPill = sheetView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_safety_status_pill)
            val tvStatusBadge = sheetView.findViewById<TextView>(R.id.tv_safety_status_badge)
            val tvStatusDesc = sheetView.findViewById<TextView>(R.id.tv_safety_status_desc)
            val tvSafetyEmojis = sheetView.findViewById<TextView>(R.id.tv_safety_emojis)
            val btnCopyNumber = sheetView.findViewById<MaterialButton>(R.id.btn_copy_safety_number)
            val tvSafetyNumber = sheetView.findViewById<TextView>(R.id.tv_safety_number)
            val cardQrFrame = sheetView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_safety_qr_frame)
            val ivSafetyQr = sheetView.findViewById<ImageView>(R.id.iv_safety_qr)
            val btnScanQr = sheetView.findViewById<MaterialButton>(R.id.btn_scan_peer_qr)
            val btnConfirmVerify = sheetView.findViewById<MaterialButton>(R.id.btn_safety_confirm_verify)
            val btnAcceptChange = sheetView.findViewById<MaterialButton>(R.id.btn_safety_accept_change)
            val btnRekeyChat = sheetView.findViewById<MaterialButton>(R.id.btn_safety_rekey_chat)
            val btnDone = sheetView.findViewById<MaterialButton>(R.id.btn_safety_done)

            tvPeerName.text = peerName
            when (peerEntity.trustState) {
                TrustedIdentityEntity.STATE_VERIFIED -> {
                    tvStatusBadge.text = "✅ Identità Verificata"
                    tvStatusBadge.setTextColor(Color.parseColor("#66BB6A"))
                    cardStatusPill.setCardBackgroundColor(Color.parseColor("#1F2E7D32"))
                    tvStatusDesc.text = "Hai confermato personalmente l'identità crittografica di $peerName."
                }
                TrustedIdentityEntity.STATE_KEY_CHANGED -> {
                    tvStatusBadge.text = "🚨 Chiave Cambiata · Invio Bloccato"
                    tvStatusBadge.setTextColor(Color.parseColor("#EF5350"))
                    cardStatusPill.setCardBackgroundColor(Color.parseColor("#29C62828"))
                    tvStatusDesc.text = "La chiave identitaria di $peerName è cambiata. Verifica il codice qui sotto prima di continuare a scrivere."
                    cardStatus.strokeColor = Color.parseColor("#EF5350")
                }
                else -> {
                    tvStatusBadge.text = "⚠️ Non Verificato"
                    tvStatusBadge.setTextColor(Color.parseColor("#FFA726"))
                    cardStatusPill.setCardBackgroundColor(Color.parseColor("#24EF6C00"))
                    tvStatusDesc.text = "La chat è già cifrata E2EE, ma non hai ancora confrontato il Safety Number con $peerName."
                }
            }

            tvSafetyEmojis.text = safetyEmojis
            val formattedSafetyNumber = safetyNumber.split(" ").chunked(4).joinToString("\n") { it.joinToString("  ") }
            tvSafetyNumber.text = formattedSafetyNumber
            btnCopyNumber.setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Safety Number", safetyNumber))
                Toast.makeText(this@ChatActivity, "Safety Number copiato negli appunti.", Toast.LENGTH_SHORT).show()
            }

            if (qrBitmap != null) {
                cardQrFrame.visibility = View.VISIBLE
                ivSafetyQr.setImageBitmap(qrBitmap)
            } else {
                cardQrFrame.visibility = View.GONE
            }
            btnScanQr.text = "Scansiona QR di $peerName"

            when (peerEntity.trustState) {
                TrustedIdentityEntity.STATE_KEY_CHANGED -> {
                    btnConfirmVerify.visibility = View.VISIBLE
                    btnConfirmVerify.text = "Conferma e Verifica"
                    btnConfirmVerify.setOnClickListener {
                        sheet.dismiss()
                        lifecycleScope.launch(Dispatchers.IO) {
                            IdentityKeyManager.confirmVerifiedIdentity(applicationContext, resolvedPeerId)
                            E2eeSessionOrchestrator.onPeerIdentityChangeResolvedSync(applicationContext, userId, resolvedPeerId, groupId)
                            withContext(Dispatchers.Main) {
                                refreshTofuBlockingUi()
                                fetchMessages(false)
                                Toast.makeText(this@ChatActivity, "Identità verificata e confermata!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    btnAcceptChange.visibility = View.VISIBLE
                    btnAcceptChange.setOnClickListener {
                        sheet.dismiss()
                        lifecycleScope.launch(Dispatchers.IO) {
                            IdentityKeyManager.acceptKeyChange(applicationContext, resolvedPeerId)
                            E2eeSessionOrchestrator.onPeerIdentityChangeResolvedSync(applicationContext, userId, resolvedPeerId, groupId)
                            withContext(Dispatchers.Main) {
                                refreshTofuBlockingUi()
                                fetchMessages(false)
                                Toast.makeText(this@ChatActivity, "Nuova chiave accettata (stato Non Verificato).", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                TrustedIdentityEntity.STATE_VERIFIED -> {
                    btnConfirmVerify.visibility = View.GONE
                    btnAcceptChange.visibility = View.GONE
                }
                else -> {
                    btnConfirmVerify.visibility = View.VISIBLE
                    btnConfirmVerify.text = "Segna come Verificato"
                    btnConfirmVerify.setOnClickListener {
                        sheet.dismiss()
                        lifecycleScope.launch(Dispatchers.IO) {
                            IdentityKeyManager.confirmVerifiedIdentity(applicationContext, resolvedPeerId)
                            withContext(Dispatchers.Main) {
                                refreshTofuBlockingUi()
                                Toast.makeText(this@ChatActivity, "Identità di $peerName verificata!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    btnAcceptChange.visibility = View.GONE
                }
            }

            if (isPrivateChat) {
                btnRekeyChat.visibility = View.VISIBLE
                btnRekeyChat.setOnClickListener {
                    sheet.dismiss()
                    showRegenerateKeysConfirmationDialog()
                }
            } else {
                btnRekeyChat.visibility = View.GONE
            }

            btnScanQr.setOnClickListener {
                pendingVerifyPeerId = resolvedPeerId
                sheet.dismiss()
                val options = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Inquadra il QR di sicurezza di $peerName")
                    setBeepEnabled(false)
                    setOrientationLocked(true)
                }
                zkBarcodeLauncher.launch(options)
            }
            btnCloseSheet.setOnClickListener { sheet.dismiss() }
            btnDone.setOnClickListener { sheet.dismiss() }
            sheet.show()
        }
    }

    private fun updateToolbarStatus(online: Boolean?, seen: String?) {
        if (!isPrivateChat) {
            toolbarBinding.toolbarSubtitleStatus.visibility = View.GONE
            return
        }
        val status = TimeUtils.formatUserStatus(online ?: false, seen)
        if (status != null) {
            toolbarBinding.toolbarSubtitleStatus.text = status

            val typedValue = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true)
            toolbarBinding.toolbarSubtitleStatus.setTextColor(typedValue.data)

            toolbarBinding.toolbarSubtitleStatus.visibility = View.VISIBLE
        } else {
            toolbarBinding.toolbarSubtitleStatus.visibility = View.GONE
        }
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(mutableListOf(), username, userId, this, currentGroupKey, currentGroupIv, groupId)
        binding.recyclerViewMessages.apply {
            adapter = messageAdapter
            layoutManager = ReliableScrollOffsetLinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true
            }
            setHasFixedSize(true)
        }

        // Calibrazione della comparsa del tasto basata sulla distanza reale in pixel dal fondo
        binding.recyclerViewMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                val canScrollDown = recyclerView.canScrollVertically(1)
                if (!canScrollDown) {
                    binding.fabScrollDown.hide()
                } else {
                    // Otteniamo i dati reali in pixel delegati dal nostro LayoutManager personalizzato
                    val offset = binding.recyclerViewMessages.computeVerticalScrollOffset()
                    val range = binding.recyclerViewMessages.computeVerticalScrollRange()
                    val extent = binding.recyclerViewMessages.computeVerticalScrollExtent()

                    // Calcola lo spazio in pixel non ancora visibile sotto lo schermo
                    val distanceFromBottom = range - offset - extent

                    // Soglia di comparsa impostata a 300dp (circa mezza schermata di un dispositivo comune)
                    val thresholdPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 300f, resources.displayMetrics).toInt()

                    if (distanceFromBottom > thresholdPx) {
                        // Mostra il tasto solo se lo scorrimento è attivo o avviato dall'utente (manuale)
                        if (recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                            binding.fabScrollDown.show()
                        }
                    } else {
                        binding.fabScrollDown.hide()
                    }
                }
            }
        })

        // Azione di scorrimento ottimizzata in base alla distanza in pixel da percorrere
        binding.fabScrollDown.setOnClickListener {
            val totalItemCount = messageAdapter.itemCount
            if (totalItemCount > 0) {
                val offset = binding.recyclerViewMessages.computeVerticalScrollOffset()
                val range = binding.recyclerViewMessages.computeVerticalScrollRange()
                val extent = binding.recyclerViewMessages.computeVerticalScrollExtent()
                val distanceFromBottom = range - offset - extent

                // Se la distanza da percorrere è enorme (es: oltre 3000dp di messaggi nascosti),
                // effettuiamo un salto immediato senza caricare inutilmente lo scorrimento fluido (evita lag)
                val maxSmoothScrollDistancePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3000f, resources.displayMetrics).toInt()

                if (distanceFromBottom > maxSmoothScrollDistancePx) {
                    binding.recyclerViewMessages.scrollToPosition(totalItemCount - 1)
                } else {
                    binding.recyclerViewMessages.smoothScrollToPosition(totalItemCount - 1)
                }
            }
        }

        val callback = object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(r: RecyclerView, v: RecyclerView.ViewHolder): Int =
                if (v is MessageAdapter.DateHeaderViewHolder) 0
                else makeMovementFlags(0, ItemTouchHelper.RIGHT)

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(v: RecyclerView.ViewHolder, d: Int) {
                val pos = v.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    (messageAdapter.getItemAt(pos) as? ListItem.MessageItem)?.let { prepareReply(it.message) }
                    messageAdapter.notifyItemChanged(pos)
                }
            }

            override fun onChildDraw(c: Canvas, r: RecyclerView, v: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isActive: Boolean) {
                super.onChildDraw(c, r, v, kotlin.math.min(dX, 300f), dY, actionState, isActive)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerViewMessages)
    }

    private fun getCurrentHighestMessageId(): Int {
        val lastMsgItem = messageAdapter.getItems().lastOrNull { it is ListItem.MessageItem } as? ListItem.MessageItem
        return lastMsgItem?.message?.id ?: lastKnownMessageId
    }

    private fun showUltraSecureChatOptions() {
        if (isFinishing || isDestroyed) return
        if (isPrivateChat) {
            showSafetyEmojisDialog()
            return
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("Sicurezza E2EE Gruppo")
            .setMessage(
                if (currentE2eeVersion >= 2) {
                    "Questo gruppo utilizza crittografia Zero-Knowledge con Sender Keys (epoch $currentMembershipEpoch).\n\nPuoi verificare il Safety Number dei singoli membri o ruotare la tua Sender Key locale."
                } else {
                    "Questo gruppo utilizza la cifratura legacy v1. Puoi comunque verificare l'identità dei membri."
                }
            )
            .setPositiveButton("Verifica Membri") { _, _ ->
                fetchAndShowCustomGroupMembersDialog()
            }
        if (currentE2eeVersion >= 2) {
            builder.setNeutralButton("Ruota Sender Key") { _, _ ->
                showRegenerateKeysConfirmationDialog()
            }
        }
        builder.setNegativeButton("Chiudi", null).show()
    }

    private fun handleZkQrCode(content: String, isSilent: Boolean = false) {
        try {
            val uri = Uri.parse(content)
            if (uri.scheme == "cryptionchat" && uri.host == "verify") {
                val version = uri.getQueryParameter("v")?.toIntOrNull() ?: 0
                val scannedUid = uri.getQueryParameter("uid")?.toIntOrNull() ?: -1
                val scannedFp = uri.getQueryParameter("fp")?.trim()?.lowercase(Locale.US) ?: ""
                val expectedPeer = pendingVerifyPeerId
                pendingVerifyPeerId = -1

                if (version != 2 || scannedUid <= 0 || scannedFp.isBlank()) {
                    Toast.makeText(this, "QR di verifica non valido.", Toast.LENGTH_SHORT).show()
                    return
                }
                if (expectedPeer > 0 && scannedUid != expectedPeer) {
                    Toast.makeText(this, "Il QR appartiene a un altro utente (#$scannedUid).", Toast.LENGTH_LONG).show()
                    return
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    val peerEntity = IdentityKeyManager.getTrustedIdentity(applicationContext, scannedUid)
                        ?: E2eeSessionOrchestrator.fetchAndPinPeerIdentitySync(applicationContext, userId, scannedUid)

                    val matches = peerEntity != null && peerEntity.fingerprintHex.lowercase(Locale.US) == scannedFp
                    if (matches) {
                        IdentityKeyManager.confirmVerifiedIdentity(applicationContext, scannedUid)
                    }

                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        if (matches) {
                            refreshTofuBlockingUi()
                            if (!isSilent) {
                                Toast.makeText(this@ChatActivity, "✅ Safety Number verificato tramite QR!", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            MaterialAlertDialogBuilder(this@ChatActivity)
                                .setTitle("🚨 Verifica QR Fallita")
                                .setMessage("L'impronta crittografica del QR scansionato NON corrisponde alla chiave registrata per l'utente #$scannedUid.\n\nPossibile cambio dispositivo o attacco Man-in-the-Middle!")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            } else {
                Toast.makeText(this, "Formato QR di verifica non riconosciuto.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Errore lettura codice di sicurezza.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun highlightMessageAt(position: Int) {
        val viewHolder = binding.recyclerViewMessages.findViewHolderForAdapterPosition(position)
        viewHolder?.itemView?.let { view ->
            val colorAnimation = android.animation.ValueAnimator.ofInt(
                Color.TRANSPARENT,
                ContextCompat.getColor(this, R.color.message_highlight),
                Color.TRANSPARENT
            ).apply {
                setEvaluator(android.animation.ArgbEvaluator())
                duration = 1200
                addUpdateListener { animator ->
                    view.setBackgroundColor(animator.animatedValue as Int)
                }
            }
            colorAnimation.start()
        }
    }

    private fun showLocationConfirmationDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Invia Posizione")
            .setMessage("Verrà inviata la tua posizione in questo esatto momento come punto sulla mappa.\n\nLa posizione è STATICA: l'app NON ti traccerà mentre ti sposti.")
            .setPositiveButton("Invia") { _, _ ->
                fetchAndSendLocation()
            }
            .setNegativeButton("Annulla") { dialog, _ ->
                dialog.dismiss()
                clearReplyState()
            }
            .show()
    }

    private fun checkLocationPermissionAndSend() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            showLocationConfirmationDialog()
        } else {
            requestLocationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }


    private var activeLocationCallback: com.google.android.gms.location.LocationCallback? = null
    private var locationTimeoutHandler: Handler? = null
    private var locationTimeoutRunnable: Runnable? = null

    @SuppressLint("MissingPermission")
    private fun fetchAndSendLocation() {
        val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation) {
            Toast.makeText(this, "Per un'elevata precisione è necessario il permesso di Posizione Precisa.", Toast.LENGTH_SHORT).show()
            clearReplyState()
            return
        }

        Toast.makeText(this, "🎯 Aggancio satelliti GPS per alta precisione...", Toast.LENGTH_SHORT).show()
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val targetAccuracyMeters = 15.0f // Target: margine di errore <= 15 metri
        val maxWaitTimeMs = 12000L      // Tempo massimo di attesa GPS: 12 secondi
        var bestLocation: android.location.Location? = null
        var isLocationSent = false

        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L // Chiedi un fix al GPS ogni secondo
        ).apply {
            setMinUpdateIntervalMillis(500L)
        }.build()

        fun stopUpdatesAndSend(locationToSend: android.location.Location?) {
            if (isLocationSent) return
            isLocationSent = true

            // Ferma gli aggiornamenti GPS e cancella il timer
            activeLocationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
            activeLocationCallback = null
            locationTimeoutRunnable?.let { locationTimeoutHandler?.removeCallbacks(it) }

            if (locationToSend != null) {
                val acc = locationToSend.accuracy.toInt()
                val mapsLink = "📍 Posizione condivisa (Precisione: ~${acc}m):\nhttps://maps.google.com/?q=${locationToSend.latitude},${locationToSend.longitude}"
                sendMessage(mapsLink)
                Toast.makeText(this, "📍 Posizione inviata con precisione di ${acc} metri!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Impossibile ottenere una posizione precisa. Controlla che il GPS sia attivo.", Toast.LENGTH_LONG).show()
                clearReplyState()
            }
        }

        activeLocationCallback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(locationResult: com.google.android.gms.location.LocationResult) {
                for (location in locationResult.locations) {
                    // Mantieni traccia della migliore posizione trovata fino ad ora
                    if (bestLocation == null || location.accuracy < bestLocation!!.accuracy) {
                        bestLocation = location
                    }

                    // Se il GPS ha agganciato la posizione con scarto <= 15m, invia SUBITO!
                    if (location.hasAccuracy() && location.accuracy <= targetAccuracyMeters) {
                        stopUpdatesAndSend(location)
                        break
                    }
                }
            }
        }

        locationTimeoutHandler = Handler(Looper.getMainLooper())
        locationTimeoutRunnable = Runnable {
            if (!isLocationSent) {
                Log.w(TAG, "Timeout precisione GPS raggiunto. Invio della migliore posizione disponibile.")
                stopUpdatesAndSend(bestLocation)
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                activeLocationCallback!!,
                Looper.getMainLooper()
            )

            // Avvia il timer di fallback
            locationTimeoutHandler?.postDelayed(locationTimeoutRunnable!!, maxWaitTimeMs)

        } catch (e: SecurityException) {
            Toast.makeText(this, "Errore di sicurezza GPS: ${e.message}", Toast.LENGTH_SHORT).show()
            clearReplyState()
        }
    }

    override fun onAcceptLocationRequest(message: Message) {
        replyingToMessage = message
        checkLocationPermissionAndSend()
    }

    override fun onDeclineLocationRequest(message: Message) {
        replyingToMessage = message
        sendMessage("❌ Ho rifiutato la tua richiesta di posizione.")
    }

    private fun applyCurrentChatBackground() {
        val bgImage = findViewById<ImageView>(R.id.chat_background_image)
        val bgVideo = findViewById<VideoView>(R.id.chat_background_video)
        val bgOverlay = findViewById<View>(R.id.chat_background_overlay)

        ChatBackgroundManager.applyBackground(
            this, bgImage, bgVideo, bgOverlay,
            ChatBackgroundManager.TARGET_CHAT, groupId
        )
    }

    private fun showChatBackgroundSettingsDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_background_settings, null)

        val sliderOpacity = dialogView.findViewById<Slider>(R.id.slider_opacity)
        val labelOpacity = dialogView.findViewById<TextView>(R.id.opacity_label)

        // Recupera l'opacità specifica per questa chat/gruppo
        val currentOpacity = ChatBackgroundManager.getOpacity(this, ChatBackgroundManager.TARGET_CHAT, groupId)
        val currentPercentage = (currentOpacity * 100).toInt()
        sliderOpacity.value = currentPercentage.toFloat()
        labelOpacity.text = "Opacità Sfondo: $currentPercentage%"

        sliderOpacity.addOnChangeListener { _, value, _ ->
            val intValue = value.toInt()
            labelOpacity.text = "Opacità Sfondo: $intValue%"
            // Salva l'opacità specificando la destinazione TARGET_CHAT ed il groupId
            ChatBackgroundManager.setOpacity(this, value / 100f, ChatBackgroundManager.TARGET_CHAT, groupId)
            applyCurrentChatBackground()
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Sfondo di questa Chat")
            .setView(dialogView)
            .show()

        dialogView.findViewById<View>(R.id.option_gallery).setOnClickListener {
            dialog.dismiss()
            pickChatBackgroundLauncher.launch(arrayOf("image/*", "video/*"))
        }

        dialogView.findViewById<View>(R.id.option_library).setOnClickListener {
            dialog.dismiss()
            showPredefinedBackgroundsDialog()
        }

        dialogView.findViewById<View>(R.id.option_ai_generator).setOnClickListener {
            dialog.dismiss()
            AIDialogHelper.showAIGeneratorDialog(
                activity = this,
                target = ChatBackgroundManager.TARGET_CHAT,
                groupId = groupId,
                onBackgroundApplied = { applyCurrentChatBackground() }
            )
        }

        dialogView.findViewById<View>(R.id.option_remove).setOnClickListener {
            dialog.dismiss()
            ChatBackgroundManager.removeBackground(this, ChatBackgroundManager.TARGET_CHAT, groupId)
            applyCurrentChatBackground()
            Toast.makeText(this, "Sfondo ripristinato", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPredefinedBackgroundsDialog() {
        if (isFinishing || isDestroyed) return

        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Caricamento sfondi...")
            .setView(ProgressBar(this).apply { setPadding(50, 50, 50, 50) })
            .setCancelable(false)
            .show()

        ApiClient.instance.getPredefinedBackgrounds().enqueue(object : Callback<PredefinedImagesResponse> {
            override fun onResponse(call: Call<PredefinedImagesResponse>, response: Response<PredefinedImagesResponse>) {
                if (isFinishing || isDestroyed) {
                    try { progressDialog.dismiss() } catch (e: Exception) {}
                    return
                }
                try { progressDialog.dismiss() } catch (e: Exception) {}

                val urls = if (response.isSuccessful && response.body() != null) response.body()!!.images else emptyList()

                if (urls.isEmpty()) {
                    Toast.makeText(this@ChatActivity, "Nessuno sfondo disponibile.", Toast.LENGTH_SHORT).show()
                    return
                }

                val dialogView = LayoutInflater.from(this@ChatActivity).inflate(R.layout.dialog_predefined_backgrounds, null)
                val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerViewBackgrounds)
                val btnClose = dialogView.findViewById<ImageButton>(R.id.btn_close_dialog)

                val dialog = MaterialAlertDialogBuilder(this@ChatActivity).setView(dialogView).show()
                btnClose.setOnClickListener { dialog.dismiss() }

                recyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@ChatActivity, 2)
                recyclerView.adapter = PredefinedBackgroundAdapter(urls) { selectedUrl ->
                    dialog.dismiss()
                    ChatBackgroundManager.setPredefinedBackground(this@ChatActivity, selectedUrl, ChatBackgroundManager.TARGET_CHAT, groupId)
                    applyCurrentChatBackground()
                    Toast.makeText(this@ChatActivity, "Sfondo applicato a questa chat!", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<PredefinedImagesResponse>, t: Throwable) {
                if (isFinishing || isDestroyed) {
                    try { progressDialog.dismiss() } catch (e: Exception) {}
                    return
                }
                try { progressDialog.dismiss() } catch (e: Exception) {}
                Toast.makeText(this@ChatActivity, "Errore di connessione.", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun isSingleEmoji(str: String): Boolean {
        val cleanStr = str.trim()
        if (cleanStr.isBlank()) return false
        if (cleanStr.any { it.isLetter() }) return false
        if (cleanStr.all { it.isDigit() }) return false
        return true
    }

    private fun saveRecentEmoji(emoji: String) {
        val prefs = SecurePrefs.get(this)
        val recentsStr = prefs.getString("recent_reaction_emojis", "") ?: ""
        val recentsList = recentsStr.split(",").filter { it.isNotBlank() }.toMutableList()

        recentsList.remove(emoji)
        recentsList.add(0, emoji)

        if (recentsList.size > 12) {
            recentsList.removeAt(recentsList.size - 1)
        }

        prefs.edit().putString("recent_reaction_emojis", recentsList.joinToString(",")).apply()
    }

    private fun getRecentEmojis(): List<String> {
        val prefs = SecurePrefs.get(this)
        val recentsStr = prefs.getString("recent_reaction_emojis", "") ?: ""
        return recentsStr.split(",").filter { it.isNotBlank() }
    }

    private class EmojiPickerAdapter(
        private val list: List<String>,
        private val selected: String?,
        private val px48: Int,
        private val typedValueResId: Int,
        private val highlightColor: Int,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<EmojiPickerAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(px48, px48)
                gravity = android.view.Gravity.CENTER
                textSize = 28f
                isClickable = true
                isFocusable = true
            }
            return ViewHolder(tv)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val emoji = list[position]
            val tv = holder.itemView as TextView
            tv.text = emoji
            if (emoji == selected) {
                tv.setBackgroundColor(highlightColor)
            } else {
                tv.background = null
                tv.setBackgroundResource(typedValueResId)
            }
            tv.setOnClickListener { onClick(emoji) }
        }

        override fun getItemCount(): Int = list.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
    }

}

class ReliableScrollOffsetLinearLayoutManager(context: Context) : LinearLayoutManager(context) {

    // Mappa per memorizzare l'altezza esatta in pixel di ogni messaggio renderizzato
    private val childSizes = mutableMapOf<Int, Int>()

    private fun recalculateChildHeights() {
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            val position = getPosition(child)
            if (position != RecyclerView.NO_POSITION) {
                childSizes[position] = child.height
            }
        }
    }

    override fun onLayoutCompleted(state: RecyclerView.State?) {
        super.onLayoutCompleted(state)
        recalculateChildHeights()
    }

    override fun onScrollStateChanged(state: Int) {
        super.onScrollStateChanged(state)
        recalculateChildHeights()
    }

    // Calcola l'offset di scorrimento verticale esatto in pixel
    override fun computeVerticalScrollOffset(state: RecyclerView.State): Int {
        if (childCount == 0) return 0
        val firstChildPosition = findFirstVisibleItemPosition()
        val firstChild = findViewByPosition(firstChildPosition) ?: return 0

        var scrolledY = -firstChild.top
        val defaultHeight = firstChild.height

        for (i in 0 until firstChildPosition) {
            scrolledY += childSizes[i] ?: defaultHeight
        }
        return scrolledY
    }

    // Calcola la lunghezza totale esatta in pixel dell'intera lista della chat
    override fun computeVerticalScrollRange(state: RecyclerView.State): Int {
        if (itemCount == 0) return 0
        var totalHeight = 0
        val defaultHeight = if (childCount > 0) getChildAt(0)?.height ?: 100 else 100

        for (i in 0 until itemCount) {
            totalHeight += childSizes[i] ?: defaultHeight
        }
        return totalHeight
    }

    override fun computeVerticalScrollExtent(state: RecyclerView.State): Int {
        if (childCount == 0) return 0
        return super.computeVerticalScrollExtent(state)
    }
}