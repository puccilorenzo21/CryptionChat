package com.lollo1344.cryptionchat

import android.Manifest
import android.app.AlarmManager
import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.Html
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.text.util.Linkify
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.lollo1344.cryptionchat.databinding.ActivityMainBinding
import com.lollo1344.cryptionchat.databinding.DialogNotificationSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var groupAdapter: GroupAdapter
    private var userId: Int = -1
    private var username: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var groupPollingRunnable: Runnable
    private val pollingInterval = 3500L
    private val homeNewMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isFinishing && !isDestroyed && isNetworkAvailable && userId != -1) {
                fetchUserGroups(isInitialLoad = false)
            }
        }
    }
    private var betaTesterDialog: AlertDialog? = null
    private var betaProgressDialog: AlertDialog? = null

    // Callback di sistema per la gestione dinamica del tasto indietro predittivo
    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            binding.bottomNavigation.selectedItemId = R.id.nav_chats
        }
    }

    private var isNetworkAvailable = true
    private var permissionDialog: AlertDialog? = null
    private var waitingForOrbot = false
    private var securityDialog: Dialog? = null
    private var onSaveSecuritySettingsAction: (() -> Unit)? = null

    private var appTitleSpannable: SpannableString? = null
    private var glassmorphismDialog: androidx.appcompat.app.AlertDialog? = null

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Al rientro, ricontrolla la sequenza dei permessi
        checkAllMandatoryPermissions()
    }

    private val pickBackgroundLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            promptBackgroundTarget(getString(R.string.main_bg_target_apply_prompt)) { target ->
                if (ChatBackgroundManager.setCustomBackground(this, it, target)) {
                    Toast.makeText(this, getString(R.string.ai_bg_applied), Toast.LENGTH_SHORT).show()
                    applyCurrentHomeBackground()
                } else {
                    Toast.makeText(this, getString(R.string.main_bg_save_error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val installPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, getString(R.string.main_perm_granted_update), Toast.LENGTH_SHORT).show()
                checkForUpdates()
            } else {
                Toast.makeText(this, getString(R.string.main_perm_denied_update), Toast.LENGTH_LONG).show()
            }
        }
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (isFinishing || isDestroyed) return@registerForActivityResult
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, getString(R.string.main_base_perms_granted), Toast.LENGTH_SHORT).show()
            checkAndRestoreService()
            ServiceScheduler.scheduleWatchdog(this)
        } else {
            Toast.makeText(this, getString(R.string.main_base_perms_required), Toast.LENGTH_LONG).show()
        }
        // Riesegue il controllo per passare alla fase successiva (Fase 2) o bloccare nuovamente
        checkAllMandatoryPermissions()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val prefs = SecurePrefs.get(this)
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.main_overlay_perm_granted), Toast.LENGTH_SHORT).show()
            prefs.edit().remove("skip_overlay_permission").remove("overlay_settings_visited").apply()
        } else {
            // Registra che l'utente è stato nelle impostazioni ed è tornato senza concedere il permesso
            prefs.edit().putBoolean("overlay_settings_visited", true).apply()
            Toast.makeText(this, getString(R.string.main_overlay_perm_denied_skip), Toast.LENGTH_LONG).show()
        }
        // Al rientro, ricontrolla i permessi: mostrerà il dialogo aggiornato col tasto "Salta"
        checkAllMandatoryPermissions()
    }


    private val manageAllFilesPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Al rientro, verifica e passa alla fase successiva (Fase 3)
        checkAllMandatoryPermissions()
    }



    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> uri?.let { uploadProfilePicture(it) } }
    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult -> if (result.contents != null) handleScannedQrCode(result.contents) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isBlocked || isFinishing) return
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val prefs = SecurePrefs.get(this)
        userId = prefs.getInt("user_id", -1)
        username = prefs.getString("username", "") ?: ""

        if (userId == -1) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        applyCurrentToolbarAndNavBarAlpha()

        setupActionBarTitle()
        setupRecyclerView()
        setupBottomNavigation()

        updateTabVisibility(binding.bottomNavigation.selectedItemId)

        setupGroupPolling()
        // La chiamata locale a setupNetworkMonitor() è stata eliminata.

        setupMeTabActions()
        setupSettingsTabActions()

        syncBetaStatusWithServer()
        updateUserAppVersionOnServer()

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        // --- COLLEGAMENTO DEL PULSANTE + (FAB) ---
        binding.fabAddGroup.setOnClickListener {
            showAddGroupOptions()
        }

        handleDeepLink(intent)
        E2eeSessionOrchestrator.syncPreKeyBundleAsync(this, userId)
        E2eeSessionOrchestrator.syncDistributionsAsync(this, userId) { updated ->
            if (updated && !isFinishing && !isDestroyed) {
                runOnUiThread { groupAdapter.notifyDataSetChanged() }
            }
        }
        checkForBroadcastMessage()
    }

    private fun syncBetaStatusWithServer() {
        val prefs = SecurePrefs.get(this)
        val isBetaSynced = prefs.getBoolean("is_beta_synced_to_server_v1", false)

        if (!isBetaSynced && userId != -1) {
            val isBeta = prefs.getBoolean("is_beta_tester", false)
            if (isBeta) {
                // Se l'utente ha già la Beta attiva in locale, sincronizza in background sul server
                ApiClient.instance.updateBetaStatus(userId = userId, isBetaTester = true).enqueue(object : Callback<BaseResponse> {
                    override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                        if (response.isSuccessful && response.body()?.status == "success") {
                            Log.d("MainActivity", "Sincronizzazione automatica iniziale dello stato Beta completata con successo.")
                            prefs.edit().putBoolean("is_beta_synced_to_server_v1", true).apply()
                        }
                    }
                    override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                        Log.e("MainActivity", "Fallimento sincronizzazione automatica iniziale dello stato Beta.", t)
                    }
                })
            } else {
                // Se l'utente è sulla versione stabile, non serve caricare il server (il DB di default ha già 0)
                prefs.edit().putBoolean("is_beta_synced_to_server_v1", true).apply()
            }
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            updateTabVisibility(item.itemId)
            true
        }
    }

    private fun updateTabVisibility(itemId: Int) {
        backPressedCallback.isEnabled = (itemId != R.id.nav_chats)
        when (itemId) {
            R.id.nav_chats -> {
                binding.layoutMe.visibility = View.GONE
                binding.layoutSettings.visibility = View.GONE
                binding.layoutChats.visibility = View.VISIBLE
                supportActionBar?.title = getString(R.string.app_name)
            }
            R.id.nav_me -> {
                binding.layoutChats.visibility = View.GONE
                binding.layoutSettings.visibility = View.GONE
                binding.layoutMe.visibility = View.VISIBLE
                supportActionBar?.title = getString(R.string.title_my_profile)
                updateMeTabUI()
            }
            R.id.nav_settings -> {
                binding.layoutChats.visibility = View.GONE
                binding.layoutMe.visibility = View.GONE
                binding.layoutSettings.visibility = View.VISIBLE
                supportActionBar?.title = getString(R.string.nav_settings)
                updateSettingsTabUI()
            }
        }
    }

    private fun updateMeTabUI() {
        val prefs = SecurePrefs.get(this)
        val profilePicUrl = prefs.getString("profile_picture_url", null)
        val currentUsername = prefs.getString("username", getString(R.string.default_user_label))

        val meProfilePic = findViewById<ShapeableImageView>(R.id.me_profile_picture)
        val meUsername = findViewById<TextView>(R.id.me_username)

        meUsername.text = currentUsername
        if (!profilePicUrl.isNullOrBlank()) {
            Glide.with(this).load(ApiClient.BASE_URL + profilePicUrl).placeholder(R.drawable.ic_person).into(meProfilePic)
        } else {
            meProfilePic.setImageResource(R.drawable.ic_person)
        }
    }

    private fun setupMeTabActions() {
        // Cliccando sull'immagine di profilo al centro in alto si apre il visualizzatore ingrandito
        val meProfilePic = findViewById<ShapeableImageView>(R.id.me_profile_picture)
        meProfilePic?.setOnClickListener {
            val profilePicUrl = SecurePrefs.get(this).getString("profile_picture_url", null)
            showUserProfileImageDialog(profilePicUrl)
        }

        findViewById<View>(R.id.btn_me_change_photo).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        findViewById<View>(R.id.btn_me_ai_photo)?.setOnClickListener {
            AIDialogHelper.showAIGeneratorDialog(
                activity = this,
                buttonText = getString(R.string.action_set_profile_photo)
            ) { bitmap ->
                val uri = FileUtils.saveBitmapToTempUri(this, bitmap)
                if (uri != null) {
                    uploadProfilePicture(uri)
                } else {
                    Toast.makeText(this, getString(R.string.error_preparing_image), Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<View>(R.id.btn_me_remove_photo).setOnClickListener {
            removeProfilePicture()
        }
        findViewById<View>(R.id.btn_me_change_username).setOnClickListener {
            showChangeUsernameDialog()
        }
        findViewById<View>(R.id.btn_me_logout).setOnClickListener {
            logoutUser()
        }
    }

    private fun updateSettingsTabUI() {
        val isRooted = RootDetectionHelper.isDeviceRooted(this)
        val isWhitelisted = false

        val divRoot = findViewById<View>(R.id.div_root_tools)
        val btnRoot = findViewById<View>(R.id.btn_set_root)

        if (isRooted && isWhitelisted) {
            divRoot.visibility = View.VISIBLE
            btnRoot.visibility = View.VISIBLE
        } else {
            divRoot.visibility = View.GONE
            btnRoot.visibility = View.GONE
        }
    }

    private fun setupAppVersionFooter() {
        val tvVersion = findViewById<TextView>(R.id.tv_settings_app_version) ?: return
        val tvBuildType = findViewById<TextView>(R.id.tv_settings_build_type) ?: return
        val btnChangelog = findViewById<MaterialButton>(R.id.btn_view_changelog)
        val cardInfo = findViewById<View>(R.id.card_app_info_footer) ?: return

        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE
        val prefs = SecurePrefs.get(this)
        val isBeta = prefs.getBoolean("is_beta_tester", false)

        val channelText = if (isBeta) getString(R.string.channel_beta_label) else getString(R.string.channel_stable_label)

        tvVersion.text = getString(R.string.settings_version_fmt, versionName, versionCode)
        tvBuildType.text = getString(R.string.settings_build_type_fmt, channelText)

        // Apre il changelog dinamico in base al canale dell'utente
        btnChangelog?.setOnClickListener {
            showChangelogDialog()
        }

        cardInfo.setOnClickListener {
            showChangelogDialog()
        }
    }

    private fun setupSettingsTabActions() {
        findViewById<View>(R.id.btn_set_security).setOnClickListener {
            showSecuritySettingsDialog()
        }
        findViewById<View>(R.id.btn_set_biometric).setOnClickListener {
            showBiometricAuthDialog()
        }
        findViewById<View>(R.id.btn_set_notifications).setOnClickListener {
            showNotificationSettingsDialog()
        }
        findViewById<View>(R.id.btn_set_background).setOnClickListener {
            showChatBackgroundSettingsDialog()
        }
        findViewById<View>(R.id.btn_set_oled).setOnClickListener {
            showOledThemeDialog()
        }
        findViewById<View>(R.id.btn_set_transcription).setOnClickListener {
            showTranscriptionSettingsDialog()
        }
        findViewById<View>(R.id.btn_set_cache).setOnClickListener {
            showClearCacheConfirmationDialog()
        }
        findViewById<View>(R.id.btn_set_root).setOnClickListener {
            showRootToolsDialog()
        }
        findViewById<View>(R.id.btn_set_update).setOnClickListener {
            Toast.makeText(this, getString(R.string.checking_for_updates), Toast.LENGTH_SHORT).show()
            UpdateHandler(this, installPermissionLauncher).forceManualUpdate()
        }
        findViewById<View>(R.id.btn_set_beta).setOnClickListener {
            showBetaTesterDialog()
        }
        findViewById<View>(R.id.btn_set_bug).setOnClickListener {
            showFeedbackDialog()
        }
        findViewById<View>(R.id.btn_set_delete).setOnClickListener {
            showDeleteConfirmationDialog()
        }

        val settingsCardLayout = findViewById<View>(R.id.btn_set_background).parent as? LinearLayout
        val btnGlassmorphism = TextView(this).apply {
            id = View.generateViewId()
            text = getString(R.string.settings_transparency_customization)
            compoundDrawablePadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
            setCompoundDrawablesWithIntrinsicBounds(
                ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_wallpaper),
                null, null, null
            )
            val typedValue = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedValue, true)
            TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(typedValue.data))

            val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
            setPadding(padding, padding, padding, padding)

            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)

            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(typedValue.data)
        }

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics).toInt()
            )
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.outline_variant_light))
            alpha = 0.3f
        }

        settingsCardLayout?.addView(divider)
        settingsCardLayout?.addView(btnGlassmorphism)

        btnGlassmorphism.setOnClickListener {
            showGlassmorphismSettingsDialog()
        }

        // Inizializza il footer dinamico della versione in fondo al menu
        setupAppVersionFooter()
    }

    /**
     * Dialogo Material 3 per la regolazione di sfocatura, opacità, riflesso e spessore.
     */
    private fun showGlassmorphismSettingsDialog() {
        if (isFinishing || isDestroyed) return
        val prefs = SecurePrefs.get(this)

        // Allineamento ai nuovi valori predefiniti
        val navBarTransparency = prefs.getInt("nav_bar_transparency", 80)
        val chatInputTransparency = prefs.getInt("chat_input_transparency", 80)
        val toolbarTransparency = prefs.getInt("toolbar_transparency", 100)
        val chatCardTransparency = prefs.getInt("chat_card_transparency", 15)
        val homeCardTransparency = prefs.getInt("home_card_transparency", 70)
        val chatCardReflection = prefs.getInt("chat_card_reflection", 100)
        val chatCardStrokeWidth = prefs.getInt("chat_card_stroke_width", 0)
        val bgBlurRadius = prefs.getInt("bg_blur_radius", 0)

        val context = this
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()
            setPadding(padding, padding, padding, padding)
        }

        fun addSectionHeader(title: String, iconRes: Int) {
            val headerContainer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
                    bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
                }
                layoutParams = params
            }

            val icon = ImageView(context).apply {
                setImageResource(iconRes)
                val typedValue = TypedValue()
                theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
                imageTintList = ColorStateList.valueOf(typedValue.data)
                val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
                }
            }

            val headerText = TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                val typedValue = TypedValue()
                theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
                setTextColor(typedValue.data)
            }

            headerContainer.addView(icon)
            headerContainer.addView(headerText)
            rootLayout.addView(headerContainer)

            val divider = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                    bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics).toInt()
                }
                val typedValue = TypedValue()
                theme.resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, typedValue, true)
                setBackgroundColor(typedValue.data)
                alpha = 0.5f
            }
            rootLayout.addView(divider)
        }

        fun createSlider(labelPrefix: String, suffix: String, initialValue: Float, min: Float, max: Float, step: Float): Pair<Slider, TextView> {
            val label = TextView(context).apply {
                text = "$labelPrefix: ${initialValue.toInt()}$suffix"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            rootLayout.addView(label)

            val slider = Slider(context).apply {
                valueFrom = min
                valueTo = max
                value = initialValue
                stepSize = step
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
                }
                layoutParams = params
            }
            rootLayout.addView(slider)

            slider.addOnChangeListener { _, value, _ ->
                label.text = "$labelPrefix: ${value.toInt()}$suffix"
            }

            return Pair(slider, label)
        }

        addSectionHeader(getString(R.string.glass_section_bg), R.drawable.ic_wallpaper)
        val (bgBlurSlider, _) = createSlider(getString(R.string.glass_blur_label), "px", bgBlurRadius.toFloat(), 0f, 50f, 1f)

        bgBlurSlider.addOnChangeListener { _, value, _ ->
            val valInt = value.toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (valInt > 0) {
                    val blurEffect = android.graphics.RenderEffect.createBlurEffect(
                        valInt.toFloat(), valInt.toFloat(), android.graphics.Shader.TileMode.CLAMP
                    )
                    binding.homeBackgroundImage.setRenderEffect(blurEffect)
                    binding.homeBackgroundVideo.setRenderEffect(blurEffect)
                } else {
                    binding.homeBackgroundImage.setRenderEffect(null)
                    binding.homeBackgroundVideo.setRenderEffect(null)
                }
            }
        }

        addSectionHeader(getString(R.string.glass_section_bars), R.drawable.ic_settings)
        val (toolbarSlider, _) = createSlider(getString(R.string.glass_toolbar_alpha), "%", toolbarTransparency.toFloat(), 0f, 100f, 5f)
        val (navBarSlider, _) = createSlider(getString(R.string.glass_navbar_alpha), "%", navBarTransparency.toFloat(), 0f, 100f, 5f)
        val (chatInputSlider, _) = createSlider(getString(R.string.glass_chat_input_alpha), "%", chatInputTransparency.toFloat(), 0f, 100f, 5f)

        toolbarSlider.addOnChangeListener { _, value, _ ->
            val percent = value.toInt()
            val alpha = ((100 - percent) * 255 / 100).coerceIn(0, 255)
            val typedValue = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)
            val color = ColorUtils.setAlphaComponent(typedValue.data, alpha)
            binding.appBarLayout.setBackgroundColor(color)
            findViewById<AppBarLayout>(R.id.preview_appbar)?.setBackgroundColor(color)
        }

        navBarSlider.addOnChangeListener { _, value, _ ->
            val percent = value.toInt()
            val alpha = ((100 - percent) * 255 / 100).coerceIn(0, 255)
            val typedValue = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainer, typedValue, true)
            val color = ColorUtils.setAlphaComponent(typedValue.data, alpha)

            binding.bottomNavigation.setBackgroundColor(color)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.navigationBarColor = color
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightNavigationBars = !isDarkTheme && percent < 70
                }
            }
        }

        chatInputSlider.addOnChangeListener { _, value, _ ->
            val percent = value.toInt()
            val alpha = ((100 - percent) * 255 / 100).coerceIn(0, 255)
            val typedValue = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainer, typedValue, true)
            val color = ColorUtils.setAlphaComponent(typedValue.data, alpha)
            findViewById<View>(R.id.preview_bottom_bar)?.setBackgroundColor(color)
        }

        addSectionHeader(getString(R.string.glass_section_bubbles), R.drawable.ic_chat_bubble)
        val (chatCardSlider, _) = createSlider(getString(R.string.glass_msg_alpha), "%", chatCardTransparency.toFloat(), 0f, 100f, 5f)
        val (strokeWidthSlider, _) = createSlider(getString(R.string.glass_stroke_width), "dp", chatCardStrokeWidth.toFloat(), 0f, 10f, 1f)
        val (reflectionSlider, _) = createSlider(getString(R.string.glass_reflection), "%", chatCardReflection.toFloat(), 0f, 100f, 5f)

        chatCardSlider.addOnChangeListener { _, value, _ -> updateFakeChatCardsAlpha(value.toInt()) }
        strokeWidthSlider.addOnChangeListener { _, value, _ -> updateFakeChatCardsStroke(value.toInt()) }
        reflectionSlider.addOnChangeListener { _, value, _ -> updateFakeChatCardsReflection(value.toInt()) }

        addSectionHeader(getString(R.string.glass_section_home), R.drawable.ic_person)
        val (homeCardSlider, _) = createSlider(getString(R.string.glass_chat_list_alpha), "%", homeCardTransparency.toFloat(), 0f, 100f, 5f)

        val chatTouchListener = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                glassmorphismDialog?.window?.let { window ->
                    window.setDimAmount(0.0f)
                    val lp = window.attributes
                    lp.alpha = 0.2f
                    window.attributes = lp
                }
                binding.layoutSettings.visibility = View.INVISIBLE
                binding.appBarLayout.visibility = View.INVISIBLE
                binding.bottomNavigation.visibility = View.INVISIBLE
                findViewById<View>(R.id.fake_chat_preview)?.visibility = View.VISIBLE
            }

            override fun onStopTrackingTouch(slider: Slider) {
                glassmorphismDialog?.window?.let { window ->
                    window.setDimAmount(0.5f)
                    val lp = window.attributes
                    lp.alpha = 1.0f
                    window.attributes = lp
                }
                findViewById<View>(R.id.fake_chat_preview)?.visibility = View.GONE
                binding.layoutSettings.visibility = View.VISIBLE
                binding.appBarLayout.visibility = View.VISIBLE
                binding.bottomNavigation.visibility = View.VISIBLE
            }
        }

        val homeTouchListener = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                glassmorphismDialog?.window?.let { window ->
                    window.setDimAmount(0.0f)
                    val lp = window.attributes
                    lp.alpha = 0.2f
                    window.attributes = lp
                }
                findViewById<View>(R.id.fake_chat_preview)?.visibility = View.GONE
            }

            override fun onStopTrackingTouch(slider: Slider) {
                glassmorphismDialog?.window?.let { window ->
                    window.setDimAmount(0.5f)
                    val lp = window.attributes
                    lp.alpha = 1.0f
                    window.attributes = lp
                }
            }
        }

        chatCardSlider.addOnSliderTouchListener(chatTouchListener)
        reflectionSlider.addOnSliderTouchListener(chatTouchListener)
        strokeWidthSlider.addOnSliderTouchListener(chatTouchListener)
        chatInputSlider.addOnSliderTouchListener(chatTouchListener)

        navBarSlider.addOnSliderTouchListener(homeTouchListener)
        homeCardSlider.addOnSliderTouchListener(homeTouchListener)
        toolbarSlider.addOnSliderTouchListener(homeTouchListener)
        bgBlurSlider.addOnSliderTouchListener(homeTouchListener)

        // PULSANTE RESET (Allineato ai nuovi predefiniti dello screenshot)
        val resetButton = com.google.android.material.button.MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.glass_reset_defaults)
            setTextColor(context.getColor(android.R.color.holo_red_light))
            strokeColor = android.content.res.ColorStateList.valueOf(context.getColor(android.R.color.holo_red_light))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()
                bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt()
            }
            layoutParams = lp
            setOnClickListener {
                bgBlurSlider.value = 0f
                homeCardSlider.value = 70f
                navBarSlider.value = 80f
                chatInputSlider.value = 80f
                toolbarSlider.value = 100f
                chatCardSlider.value = 15f
                reflectionSlider.value = 100f
                strokeWidthSlider.value = 0f
                updateFakeChatCardsAlpha(15)
                updateFakeChatCardsReflection(100)
                updateFakeChatCardsStroke(0)
            }
        }
        rootLayout.addView(resetButton)

        val scrollView = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(rootLayout)
        }

        updateFakeChatCardsAlpha(chatCardTransparency)
        updateFakeChatCardsReflection(chatCardReflection)
        updateFakeChatCardsStroke(chatCardStrokeWidth)

        val chatInputAlphaInit = ((100 - chatInputTransparency) * 255 / 100).coerceIn(0, 255)
        val toolAlphaInit = ((100 - toolbarTransparency) * 255 / 100).coerceIn(0, 255)

        val tvSurfaceInit = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, tvSurfaceInit, true)
        findViewById<AppBarLayout>(R.id.preview_appbar)?.setBackgroundColor(ColorUtils.setAlphaComponent(tvSurfaceInit.data, toolAlphaInit))

        val tvSurfaceContInit = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainer, tvSurfaceContInit, true)
        findViewById<View>(R.id.preview_bottom_bar)?.setBackgroundColor(ColorUtils.setAlphaComponent(tvSurfaceContInit.data, chatInputAlphaInit))

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.glass_dialog_title))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.action_save)) { _, _ ->
                prefs.edit {
                    putInt("nav_bar_transparency", navBarSlider.value.toInt())
                    putInt("chat_input_transparency", chatInputSlider.value.toInt())
                    putInt("toolbar_transparency", toolbarSlider.value.toInt())
                    putInt("chat_card_transparency", chatCardSlider.value.toInt())
                    putInt("home_card_transparency", homeCardSlider.value.toInt())
                    putInt("chat_card_reflection", reflectionSlider.value.toInt())
                    putInt("chat_card_stroke_width", strokeWidthSlider.value.toInt())
                    putInt("bg_blur_radius", bgBlurSlider.value.toInt())
                }
                Toast.makeText(context, getString(R.string.glass_applied_toast), Toast.LENGTH_SHORT).show()
                fetchUserGroups(isInitialLoad = false)
            }
            .setNegativeButton(getString(R.string.call_dialog_btn_cancel)) { _, _ ->
                applyCurrentToolbarAndNavBarAlpha()
                applyCurrentBackgroundBlur()
            }
            .setOnDismissListener {
                glassmorphismDialog = null
                findViewById<View>(R.id.fake_chat_preview)?.visibility = View.GONE
                binding.layoutSettings.visibility = View.VISIBLE
                binding.appBarLayout.visibility = View.VISIBLE
                binding.bottomNavigation.visibility = View.VISIBLE
            }
            .create()

        dialog.show()
        glassmorphismDialog = dialog
    }

    private fun updateFakeChatCardsAlpha(transparencyPercent: Int) {
        val cardReceived = findViewById<MaterialCardView>(R.id.preview_card_received) ?: return
        val cardSent = findViewById<MaterialCardView>(R.id.preview_card_sent) ?: return
        val inputCard = findViewById<MaterialCardView>(R.id.preview_input_card) ?: return

        val alpha = ((100 - transparencyPercent) * 255 / 100).coerceIn(0, 255)
        val typedValue = TypedValue()

        theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainer, typedValue, true)
        cardReceived.setCardBackgroundColor(ColorUtils.setAlphaComponent(typedValue.data, alpha))

        theme.resolveAttribute(com.google.android.material.R.attr.colorPrimaryContainer, typedValue, true)
        cardSent.setCardBackgroundColor(ColorUtils.setAlphaComponent(typedValue.data, alpha))

        theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerHigh, typedValue, true)
        inputCard.setCardBackgroundColor(ColorUtils.setAlphaComponent(typedValue.data, alpha))

        if (transparencyPercent > 0) {
            cardReceived.cardElevation = 0f
            cardSent.cardElevation = 0f
        } else {
            val elev = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics)
            cardReceived.cardElevation = elev
            cardSent.cardElevation = elev
        }
    }

    private fun updateFakeChatCardsReflection(reflectionPercent: Int) {
        val cardReceived = findViewById<MaterialCardView>(R.id.preview_card_received) ?: return
        val cardSent = findViewById<MaterialCardView>(R.id.preview_card_sent) ?: return

        val alpha = (reflectionPercent * 255 / 100).coerceIn(0, 255)
        val typedValue = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, typedValue, true)
        val strokeColor = ColorUtils.setAlphaComponent(typedValue.data, alpha)

        cardReceived.strokeColor = strokeColor
        cardSent.strokeColor = strokeColor
    }

    private fun updateFakeChatCardsStroke(strokeWidthDp: Int) {
        val cardReceived = findViewById<MaterialCardView>(R.id.preview_card_received) ?: return
        val cardSent = findViewById<MaterialCardView>(R.id.preview_card_sent) ?: return

        val strokeWidthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            strokeWidthDp.toFloat(),
            resources.displayMetrics
        ).toInt()

        cardReceived.strokeWidth = strokeWidthPx
        cardSent.strokeWidth = strokeWidthPx
    }

    private fun applyCurrentToolbarAndNavBarAlpha() {
        val prefs = SecurePrefs.get(this)
        val navBarPercent = prefs.getInt("nav_bar_transparency", 80)
        val toolbarPercent = prefs.getInt("toolbar_transparency", 80)

        val navAlpha = ((100 - navBarPercent) * 255 / 100).coerceIn(0, 255)
        val toolAlpha = ((100 - toolbarPercent) * 255 / 100).coerceIn(0, 255)

        val typedValue = android.util.TypedValue()

        // Applica Trasparenza Navigazione Inferiore
        theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainer, typedValue, true)
        val navGlassColor = ColorUtils.setAlphaComponent(typedValue.data, navAlpha)
        binding.bottomNavigation.setBackgroundColor(navGlassColor)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.navigationBarColor = navGlassColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightNavigationBars = !isDarkTheme && navBarPercent < 70
            }
        }

        // Applica Trasparenza Toolbar Superiore
        theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)
        val toolGlassColor = ColorUtils.setAlphaComponent(typedValue.data, toolAlpha)
        binding.appBarLayout.setBackgroundColor(toolGlassColor)
    }

    private fun applyCurrentBackgroundBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val prefs = SecurePrefs.get(this)
            val blurRadius = prefs.getInt("bg_blur_radius", 0)
            if (blurRadius > 0) {
                val blurEffect = android.graphics.RenderEffect.createBlurEffect(
                    blurRadius.toFloat(),
                    blurRadius.toFloat(),
                    android.graphics.Shader.TileMode.CLAMP
                )
                binding.homeBackgroundImage.setRenderEffect(blurEffect)
                binding.homeBackgroundVideo.setRenderEffect(blurEffect)
            } else {
                binding.homeBackgroundImage.setRenderEffect(null)
                binding.homeBackgroundVideo.setRenderEffect(null)
            }
        }
    }

    private fun setupActionBarTitle() {
        appTitleSpannable = SpannableString(getString(R.string.app_name))
        supportActionBar?.title = appTitleSpannable
    }

    override fun onResume() {
        super.onResume()
        if (isBlocked || isFinishing) return

        // REINDIRIZZAMENTO DI SICUREZZA SE LA CHIAMATA È ATTIVA IN BACKGROUND
        if (CallManager.isActive) {
            val intent = Intent(this, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            return
        }

        if (userId != -1) {
            AppLifecycleManager.syncOnlineStatusNow(applicationContext)
            E2eeSessionOrchestrator.syncPreKeyBundleAsync(this, userId)
            E2eeSessionOrchestrator.syncDistributionsAsync(this, userId) { updated ->
                if (updated && !isFinishing && !isDestroyed) {
                    runOnUiThread { groupAdapter.notifyDataSetChanged() }
                }
            }
            checkForUpdates()
            fetchUserGroups(isInitialLoad = false)
            startGroupPolling()
            LocalBroadcastManager.getInstance(this).registerReceiver(
                homeNewMessageReceiver,
                IntentFilter(NotificationService.ACTION_NEW_MESSAGE_RECEIVED)
            )
            checkAllMandatoryPermissions()
            checkAndRestoreService()
            applyCurrentHomeBackground()
            applyCurrentToolbarAndNavBarAlpha()
            applyCurrentBackgroundBlur()

            if (waitingForOrbot && securityDialog?.isShowing == true) {
                waitingForOrbot = false
                onSaveSecuritySettingsAction?.invoke()
            }
        }
    }

    private fun applyCurrentHomeBackground() {
        val bgImage = findViewById<ImageView>(R.id.home_background_image)
        val bgVideo = findViewById<VideoView>(R.id.home_background_video)
        val bgOverlay = findViewById<View>(R.id.home_background_overlay)
        ChatBackgroundManager.applyBackground(this, bgImage, bgVideo, bgOverlay, ChatBackgroundManager.TARGET_HOME)
    }

    private fun checkAndRestoreService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            var isServiceNotificationVisible = false
            try {
                val activeNotifications = notificationManager.activeNotifications
                for (notification in activeNotifications) {
                    if (notification.id == NotificationService.FOREGROUND_NOTIFICATION_ID) {
                        isServiceNotificationVisible = true
                        break
                    }
                }
            } catch (e: Exception) {}
            if (!isServiceNotificationVisible) ServiceScheduler.startService(this)
        } else {
            ServiceScheduler.startService(this)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(homeNewMessageReceiver)
        } catch (_: Exception) {}
        try {
            val bgVideo = findViewById<VideoView>(R.id.home_background_video)
            if (bgVideo.isPlaying) bgVideo.pause()
            bgVideo.suspend()
        } catch (e: Exception) {}

        stopGroupPolling()
    }

    override fun onDestroy() {
        try {
            securityDialog?.dismiss()
            permissionDialog?.dismiss()
            glassmorphismDialog?.dismiss()
            betaTesterDialog?.dismiss()    // <-- AGGIUNTO
            betaProgressDialog?.dismiss()  // <-- AGGIUNTO
        } catch (e: Exception) {
            Log.e("MainActivity", "Errore chiusura dialoghi", e)
        }

        try {
            val bgVideo = findViewById<VideoView>(R.id.home_background_video)
            bgVideo.stopPlayback()
            bgVideo.suspend()
        } catch (e: Exception) {
            Log.e("MainActivity", "Errore rilascio bgVideo", e)
        }

        securityDialog = null
        onSaveSecuritySettingsAction = null
        permissionDialog = null
        glassmorphismDialog = null
        betaTesterDialog = null
        betaProgressDialog = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // Allinea la visibilità della schermata con l'indicatore ripristinato dal sistema
        updateTabVisibility(binding.bottomNavigation.selectedItemId)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun checkForUpdates() {
        val updateHandler = UpdateHandler(this, installPermissionLauncher)
        updateHandler.checkForUpdates()
    }

    private fun showUserProfileImageDialog(profilePicUrl: String?) {
        if (isFinishing || isDestroyed) return
        val dialog = Dialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_image_viewer, null)
        dialog.setContentView(view)
        val imageView = view.findViewById<com.github.chrisbanes.photoview.PhotoView>(R.id.full_screen_image)
        val closeButton = view.findViewById<ImageButton>(R.id.close_button)
        Glide.with(this).load(ApiClient.BASE_URL + profilePicUrl).placeholder(R.drawable.ic_person).into(imageView)
        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
        dialog.show()
    }

    private fun promptBackgroundTarget(title: String, onTargetSelected: (String) -> Unit) {
        val options = arrayOf(getString(R.string.bg_target_chat_only), getString(R.string.bg_target_home_only), getString(R.string.ai_bg_opt_both))
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                val target = when (which) {
                    0 -> ChatBackgroundManager.TARGET_CHAT
                    1 -> ChatBackgroundManager.TARGET_HOME
                    else -> ChatBackgroundManager.TARGET_BOTH
                }
                onTargetSelected(target)
            }
            .show()
    }

    private fun showChatBackgroundSettingsDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_background_settings, null)

        val sliderOpacity = dialogView.findViewById<Slider>(R.id.slider_opacity)
        val labelOpacity = dialogView.findViewById<TextView>(R.id.opacity_label)

        // Recupera l'opacità specifica della schermata Home
        val currentOpacity = ChatBackgroundManager.getOpacity(this, ChatBackgroundManager.TARGET_HOME)
        val currentPercentage = (currentOpacity * 100).toInt()
        sliderOpacity.value = currentPercentage.toFloat()
        labelOpacity.text = getString(R.string.bg_opacity_fmt, currentPercentage)

        sliderOpacity.addOnChangeListener { _, value, _ ->
            val intValue = value.toInt()
            labelOpacity.text = getString(R.string.bg_opacity_fmt, intValue)
            // Salva l'opacità specificando la destinazione TARGET_HOME
            ChatBackgroundManager.setOpacity(this, value / 100f, ChatBackgroundManager.TARGET_HOME)
            applyCurrentHomeBackground()
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settings_app_background))
            .setView(dialogView)
            .show()

        dialogView.findViewById<View>(R.id.option_gallery).setOnClickListener {
            dialog.dismiss()
            pickBackgroundLauncher.launch(arrayOf("image/*", "video/*"))
        }

        dialogView.findViewById<View>(R.id.option_library).setOnClickListener {
            dialog.dismiss()
            showPredefinedBackgroundsDialog()
        }

        dialogView.findViewById<View>(R.id.option_ai_generator).setOnClickListener {
            dialog.dismiss()
            AIDialogHelper.showAIGeneratorDialog(
                activity = this@MainActivity,
                target = ChatBackgroundManager.TARGET_HOME,
                groupId = null,
                onBackgroundApplied = { applyCurrentHomeBackground() }
            )
        }

        dialogView.findViewById<View>(R.id.option_remove).setOnClickListener {
            dialog.dismiss()
            promptBackgroundTarget(getString(R.string.bg_remove_prompt)) { target ->
                ChatBackgroundManager.removeBackground(this, target)
                applyCurrentHomeBackground()
                Toast.makeText(this, getString(R.string.bg_removed_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPredefinedBackgroundsDialog() {
        if (isFinishing || isDestroyed) return

        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.bg_loading_title))
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
                    Toast.makeText(this@MainActivity, getString(R.string.bg_none_on_server), Toast.LENGTH_SHORT).show()
                    return
                }

                val dialogView = LayoutInflater.from(this@MainActivity).inflate(R.layout.dialog_predefined_backgrounds, null)
                val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerViewBackgrounds)
                val btnClose = dialogView.findViewById<ImageButton>(R.id.btn_close_dialog)

                val dialog = MaterialAlertDialogBuilder(this@MainActivity).setView(dialogView).show()
                btnClose.setOnClickListener { dialog.dismiss() }

                recyclerView.layoutManager = GridLayoutManager(this@MainActivity, 2)
                recyclerView.adapter = PredefinedBackgroundAdapter(urls) { selectedUrl ->
                    dialog.dismiss()
                    promptBackgroundTarget(getString(R.string.bg_apply_prompt)) { target ->
                        ChatBackgroundManager.setPredefinedBackground(this@MainActivity, selectedUrl, target, null)
                        applyCurrentHomeBackground()
                        Toast.makeText(this@MainActivity, getString(R.string.bg_applied_success), Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onFailure(call: Call<PredefinedImagesResponse>, t: Throwable) {
                if (isFinishing || isDestroyed) {
                    try { progressDialog.dismiss() } catch (e: Exception) {}
                    return
                }
                try { progressDialog.dismiss() } catch (e: Exception) {}
                Toast.makeText(this@MainActivity, getString(R.string.err_server_connection), Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showSecuritySettingsDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_security_settings, null)

        val btnCloseSheet = dialogView.findViewById<ImageButton>(R.id.btn_close_security_sheet)
        val btnCancelSecurity = dialogView.findViewById<MaterialButton>(R.id.btn_cancel_security)
        val btnSaveSecurity = dialogView.findViewById<MaterialButton>(R.id.btn_save_security)

        val switchReadReceipts = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_read_receipts)
        val switchHideOnlineStatus = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_hide_online_status)
        val switchDisableTyping = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_disable_typing)
        val switchAutoSecureChats = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_auto_secure_chats)
        val switchGlobalAutoDownload = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_global_auto_download_media)
        val cardRotateGlobalKeys = dialogView.findViewById<MaterialCardView>(R.id.card_rotate_global_keys)
        val switchProxy = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_use_proxy)
        val layoutProxyConfig = dialogView.findViewById<View>(R.id.proxy_config_layout)
        val editHost = dialogView.findViewById<EditText>(R.id.edit_proxy_host)
        val editPort = dialogView.findViewById<EditText>(R.id.edit_proxy_port)
        val tvTorWarning = dialogView.findViewById<TextView>(R.id.tv_tor_warning)
        val editPanic = dialogView.findViewById<EditText>(R.id.edit_panic_code)
        val btnHelpPanic = dialogView.findViewById<ImageButton>(R.id.btn_help_panic)

        val editRemoteKill = dialogView.findViewById<EditText>(R.id.edit_remote_kill_code)
        val btnHelpRemoteKill = dialogView.findViewById<ImageButton>(R.id.btn_help_remote_kill)
        val btnShizukuGuide = dialogView.findViewById<MaterialButton>(R.id.btn_shizuku_guide)

        val sliderDeadMan = dialogView.findViewById<Slider>(R.id.slider_dead_man)
        val labelDeadMan = dialogView.findViewById<TextView>(R.id.dead_man_label)
        val switchPanicDeleteAccount = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_panic_delete_account)

        val prefs = SecurePrefs.get(this)

        val previousReadReceipts = prefs.getBoolean("send_read_receipts", true)
        switchReadReceipts.isChecked = previousReadReceipts
        switchHideOnlineStatus.isChecked = SessionLifecycleHelper.isOnlineStatusHidden(prefs)
        switchDisableTyping.isChecked = SessionLifecycleHelper.isTypingIndicatorDisabled(prefs)
        switchAutoSecureChats.isChecked = prefs.getBoolean("auto_secure_new_chats", true)
        // ATTIVO DI DEFAULT: Se la chiave non esiste ancora nei file di preferenze, restituisce true
        switchGlobalAutoDownload.isChecked = prefs.getBoolean("global_auto_download_media", true)

        cardRotateGlobalKeys.setOnClickListener {
            showGlobalKeyRegenerationDialog()
        }

        fun refreshTorWarningBanner(proxyEnabled: Boolean) {
            if (!proxyEnabled) {
                tvTorWarning.visibility = View.GONE
                return
            }
            lifecycleScope.launch {
                val unavailable = withContext(Dispatchers.IO) {
                    OrbotHelper.isTorEnforcedAndUnavailable(this@MainActivity)
                }
                if (!isFinishing && !isDestroyed) {
                    tvTorWarning.visibility = if (unavailable) View.VISIBLE else View.GONE
                }
            }
        }

        val useProxy = prefs.getBoolean("use_proxy", false)
        switchProxy.isChecked = useProxy
        layoutProxyConfig.visibility = if (useProxy) View.VISIBLE else View.GONE
        editHost.setText(prefs.getString("proxy_host", "127.0.0.1"))
        editPort.setText(prefs.getString("proxy_port", "9050"))
        refreshTorWarningBanner(useProxy)

        switchProxy.setOnCheckedChangeListener { _, isChecked ->
            layoutProxyConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
            prefs.edit().putBoolean("use_proxy", isChecked).apply()
            ApiClient.reloadConfiguration(this)
            refreshTorWarningBanner(isChecked)

            if (isChecked) {
                if (!OrbotHelper.isOrbotInstalled(this)) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.orbot_required_title))
                        .setMessage(getString(R.string.orbot_required_msg))
                        .setPositiveButton(getString(R.string.update_install_btn)) { _, _ -> OrbotHelper.promptInstallOrbot(this) }
                        .setNegativeButton(getString(R.string.call_dialog_btn_cancel)) { _, _ ->
                            switchProxy.isChecked = false
                            prefs.edit().putBoolean("use_proxy", false).apply()
                            ApiClient.reloadConfiguration(this)
                            tvTorWarning.visibility = View.GONE
                        }
                        .show()
                } else {
                    waitingForOrbot = true
                    OrbotHelper.requestStartOrbot(this)
                    Toast.makeText(this, getString(R.string.orbot_starting_toast), Toast.LENGTH_SHORT).show()
                }
            } else {
                waitingForOrbot = false
                OrbotHelper.requestStopOrbot(this)
            }
        }

        val existingPanic = PanicHelper.getPanicCode(this)
        if (!existingPanic.isNullOrBlank()) editPanic.hint = getString(R.string.code_active_edit_hint)

        btnHelpPanic.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle(getString(R.string.panic_help_title))
                .setMessage(android.text.Html.fromHtml(getString(R.string.panic_help_message), android.text.Html.FROM_HTML_MODE_COMPACT))
                .setPositiveButton(getString(R.string.action_got_it), null).show()
        }

        val existingRemote = prefs.getString("remote_kill_code", null)
        if (!existingRemote.isNullOrBlank()) editRemoteKill.hint = getString(R.string.code_active_edit_hint)

        btnHelpRemoteKill.setOnClickListener {
            showRemoteKillHelpDialog()
        }

        btnShizukuGuide.setOnClickListener {
            showShizukuGuideDialog()
        }

        val currentDeadManDays = prefs.getInt("dead_man_days", 0)
        sliderDeadMan.value = currentDeadManDays.toFloat()
        labelDeadMan.text = if (currentDeadManDays == 0) getString(R.string.state_disabled) else getString(R.string.days_fmt, currentDeadManDays)
        sliderDeadMan.addOnChangeListener { _, value, _ ->
            labelDeadMan.text = if (value.toInt() == 0) getString(R.string.state_disabled) else getString(R.string.days_fmt, value.toInt())
        }
        switchPanicDeleteAccount.isChecked = prefs.getBoolean("panic_delete_account", false)

        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        bottomSheet.setContentView(dialogView)
        bottomSheet.setOnShowListener {
            val maxHeight = (resources.displayMetrics.heightPixels * 0.90f).toInt()
            dialogView.layoutParams = dialogView.layoutParams?.apply {
                height = maxHeight
            }
            bottomSheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = true
            }
        }
        bottomSheet.setOnDismissListener {
            if (securityDialog === bottomSheet) {
                securityDialog = null
                onSaveSecuritySettingsAction = null
            }
        }

        val saveAction = {
            val editor = prefs.edit()
            editor.putString("proxy_host", editHost.text.toString().trim())
            editor.putString("proxy_port", editPort.text.toString().trim())

            val sendReadReceipts = switchReadReceipts.isChecked
            val hideOnlineStatus = switchHideOnlineStatus.isChecked
            val disableTyping = switchDisableTyping.isChecked
            editor.putBoolean("send_read_receipts", sendReadReceipts)
            editor.putBoolean("privacy_hide_online_status", hideOnlineStatus)
            editor.putBoolean("privacy_disable_typing", disableTyping)

            val autoSecureNewChats = switchAutoSecureChats.isChecked
            editor.putBoolean("auto_secure_new_chats", autoSecureNewChats)

            if (autoSecureNewChats) {
                prefs.all.keys.filter { it.startsWith("zk_explicitly_disabled_") }.forEach { key ->
                    editor.remove(key)
                }
                Log.i("SecuritySettings", "Reset di tutti i blocchi manuali ZK locali per auto-attivazione.")
            }

            // SALVATAGGIO DELLA PREFERENZA GLOBALE PER IL DOWNLOAD AUTOMATICO MEDIA
            val globalAutoDownload = switchGlobalAutoDownload.isChecked
            editor.putBoolean("global_auto_download_media", globalAutoDownload)

            val newPanic = editPanic.text.toString().trim()
            if (newPanic.isNotEmpty()) PanicHelper.setPanicCode(this, newPanic)
            val newRemote = editRemoteKill.text.toString().trim()
            if (newRemote.isNotEmpty()) {
                editor.putString("remote_kill_code", newRemote)
            }
            editor.putInt("dead_man_days", sliderDeadMan.value.toInt())
            editor.putBoolean("panic_delete_account", switchPanicDeleteAccount.isChecked)
            editor.commit()

            ApiClient.reloadConfiguration(this)

            if (userId != -1) {
                ApiClient.instance.updatePrivacySettings(
                    userId = userId,
                    sendReadReceipts = sendReadReceipts,
                    hideOnlineStatus = hideOnlineStatus
                ).enqueue(object : Callback<BaseResponse> {
                    override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                        if (!response.isSuccessful || response.body()?.status != "success") {
                            Log.w("SecuritySettings", "Aggiornamento privacy fallito: ${response.code()}")
                        } else {
                            AppLifecycleManager.syncOnlineStatusNow(applicationContext, force = true)
                        }
                    }
                    override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                        Log.w("SecuritySettings", "Errore rete updatePrivacySettings: ${t.message}")
                        AppLifecycleManager.syncOnlineStatusNow(applicationContext, force = true)
                    }
                })
            }

            if (prefs.getBoolean("use_proxy", false)) {
                lifecycleScope.launch {
                    val torBlocked = withContext(Dispatchers.IO) {
                        OrbotHelper.isTorEnforcedAndUnavailable(this@MainActivity)
                    }
                    if (!isFinishing && !isDestroyed && torBlocked) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.tor_active_orbot_disconnected),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            Toast.makeText(this, getString(R.string.settings_saved_success), Toast.LENGTH_SHORT).show()
            bottomSheet.dismiss()
        }

        onSaveSecuritySettingsAction = saveAction
        btnCloseSheet.setOnClickListener { bottomSheet.dismiss() }
        btnCancelSecurity.setOnClickListener { bottomSheet.dismiss() }
        btnSaveSecurity.setOnClickListener { saveAction() }

        securityDialog = bottomSheet
        bottomSheet.show()
    }

    private fun showGlobalKeyRegenerationDialog() {
        if (isFinishing || isDestroyed || userId <= 0) return
        val sheetView = LayoutInflater.from(this).inflate(R.layout.dialog_regenerate_keys, null)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        sheet.setContentView(sheetView)
        sheet.setOnShowListener {
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { container ->
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(container)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        val cardRotatePrekeys = sheetView.findViewById<MaterialCardView>(R.id.card_rotate_prekeys)
        val btnRotatePrekeys = sheetView.findViewById<MaterialButton>(R.id.btn_action_rotate_prekeys)
        val cardResetIdentity = sheetView.findViewById<MaterialCardView>(R.id.card_reset_identity)
        val btnResetIdentity = sheetView.findViewById<MaterialButton>(R.id.btn_action_reset_identity)
        val btnCancel = sheetView.findViewById<MaterialButton>(R.id.btn_cancel_rekey_sheet)

        fun executeRotation(resetIdentity: Boolean) {
            btnRotatePrekeys.isEnabled = false
            btnResetIdentity.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = E2eeSessionOrchestrator.rotateLocalPreKeysSync(
                    context = applicationContext,
                    userId = userId,
                    resetIdentity = resetIdentity
                )
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    sheet.dismiss()
                    val msg = when {
                        ok && resetIdentity -> getString(R.string.rekey_identity_done)
                        ok -> getString(R.string.rekey_prekey_done)
                        else -> getString(R.string.rekey_upload_failed)
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }

        val triggerPrekeyRotation = View.OnClickListener { executeRotation(resetIdentity = false) }
        cardRotatePrekeys.setOnClickListener(triggerPrekeyRotation)
        btnRotatePrekeys.setOnClickListener(triggerPrekeyRotation)

        val triggerIdentityReset = View.OnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.rekey_confirm_identity_title))
                .setMessage(getString(R.string.rekey_confirm_identity_msg))
                .setPositiveButton(getString(R.string.rekey_confirm_identity_btn)) { _, _ ->
                    executeRotation(resetIdentity = true)
                }
                .setNegativeButton(getString(R.string.call_dialog_btn_cancel), null)
                .show()
        }
        cardResetIdentity.setOnClickListener(triggerIdentityReset)
        btnResetIdentity.setOnClickListener(triggerIdentityReset)
        btnCancel.setOnClickListener { sheet.dismiss() }

        sheet.show()
    }

    private fun showTranscriptionSettingsDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_transcription_settings, null)

        val statusText = dialogView.findViewById<TextView>(R.id.model_status_text)
        val storageText = dialogView.findViewById<TextView>(R.id.tv_storage_free_space)
        val progressLayout = dialogView.findViewById<LinearLayout>(R.id.progress_layout)
        val progressBar = dialogView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progress_bar)
        val progressText = dialogView.findViewById<TextView>(R.id.progress_text)
        val btnAction = dialogView.findViewById<MaterialButton>(R.id.btn_action_model)
        val btnDelete = dialogView.findViewById<MaterialButton>(R.id.btn_delete_model)
        val btnClose = dialogView.findViewById<MaterialButton>(R.id.btn_close_dialog)

        val cardVosk = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_vosk)
        val cardWhisperBase = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_whisper_base)
        val cardWhisperSmall = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_whisper_small)
        val cardWhisperSmallQ5 = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_whisper_small_q5)
        val cardWhisperSmallQ8 = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_whisper_small_q8)
        val cardWhisperTurboQ5 = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_whisper_turbo_q5)
        val cardHybrid = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_hybrid)

        val rbVosk = dialogView.findViewById<com.google.android.material.radiobutton.MaterialRadioButton>(R.id.rb_vosk)
        val rbWhisperBase = dialogView.findViewById<com.google.android.material.radiobutton.MaterialRadioButton>(R.id.rb_whisper_base)
        val rbWhisperSmall = dialogView.findViewById<com.google.android.material.radiobutton.MaterialRadioButton>(R.id.rb_whisper_small)
        val rbWhisperSmallQ5 = dialogView.findViewById<com.google.android.material.radiobutton.MaterialRadioButton>(R.id.rb_whisper_small_q5)
        val rbWhisperSmallQ8 = dialogView.findViewById<com.google.android.material.radiobutton.MaterialRadioButton>(R.id.rb_whisper_small_q8)
        val rbWhisperTurboQ5 = dialogView.findViewById<com.google.android.material.radiobutton.MaterialRadioButton>(R.id.rb_whisper_turbo_q5)
        val rbHybrid = dialogView.findViewById<com.google.android.material.radiobutton.MaterialRadioButton>(R.id.rb_hybrid)

        data class UiItem(val card: com.google.android.material.card.MaterialCardView, val rb: com.google.android.material.radiobutton.MaterialRadioButton, val type: String, val titleView: TextView, val descView: TextView, val helpButton: ImageButton)

        val uiItems = listOf(
            UiItem(cardVosk, rbVosk, AudioTranscriber.TYPE_VOSK, dialogView.findViewById(R.id.tv_vosk_title), dialogView.findViewById(R.id.tv_vosk_desc), dialogView.findViewById(R.id.help_vosk)),
            UiItem(cardWhisperBase, rbWhisperBase, AudioTranscriber.TYPE_WHISPER_BASE, dialogView.findViewById(R.id.tv_whisper_base_title), dialogView.findViewById(R.id.tv_whisper_base_desc), dialogView.findViewById(R.id.help_whisper_base)),
            UiItem(cardWhisperSmall, rbWhisperSmall, AudioTranscriber.TYPE_WHISPER_SMALL, dialogView.findViewById(R.id.tv_whisper_small_title), dialogView.findViewById(R.id.tv_whisper_small_desc), dialogView.findViewById(R.id.help_whisper_small)),
            UiItem(cardWhisperSmallQ5, rbWhisperSmallQ5, AudioTranscriber.TYPE_WHISPER_SMALL_Q5, dialogView.findViewById(R.id.tv_whisper_q5_title), dialogView.findViewById(R.id.tv_whisper_q5_desc), dialogView.findViewById(R.id.help_whisper_small_q5)),
            UiItem(cardWhisperSmallQ8, rbWhisperSmallQ8, AudioTranscriber.TYPE_WHISPER_SMALL_Q8, dialogView.findViewById(R.id.tv_whisper_q8_title), dialogView.findViewById(R.id.tv_whisper_q8_desc), dialogView.findViewById(R.id.help_whisper_small_q8)),
            UiItem(cardWhisperTurboQ5, rbWhisperTurboQ5, AudioTranscriber.TYPE_WHISPER_TURBO_Q5, dialogView.findViewById(R.id.tv_whisper_turbo_title), dialogView.findViewById(R.id.tv_whisper_turbo_desc), dialogView.findViewById(R.id.help_whisper_turbo_q5)),
            UiItem(cardHybrid, rbHybrid, AudioTranscriber.TYPE_HYBRID, dialogView.findViewById(R.id.tv_hybrid_title), dialogView.findViewById(R.id.tv_hybrid_desc), dialogView.findViewById(R.id.help_hybrid))
        )

        val currentType = AudioTranscriber.getModelType(this)
        uiItems.forEach { it.rb.isChecked = (it.type == currentType) }

        val dialog = MaterialAlertDialogBuilder(this).setView(dialogView).create()
        btnClose.setOnClickListener { dialog.dismiss() }

        fun updateUI() {
            if (isFinishing || isDestroyed) return

            try {
                val stat = android.os.StatFs(filesDir.path)
                val bytesAvailable = stat.availableBlocksLong * stat.blockSizeLong
                val gigaBytesAvailable = bytesAvailable / (1024.0 * 1024.0 * 1024.0)
                storageText.text = getString(R.string.voice_free_space_fmt, String.format(java.util.Locale.getDefault(), "%.2f", gigaBytesAvailable))
            } catch (e: Exception) {
                storageText.text = getString(R.string.voice_free_space_err)
            }

            uiItems.forEach { item ->
                if (item.rb.isChecked) {
                    item.card.strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics).toInt()
                    item.card.setStrokeColor(ContextCompat.getColor(this, R.color.primary_light))
                    item.card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.primary_container_light))
                    val darkColor = ContextCompat.getColor(this, R.color.on_primary_container_light)
                    item.titleView.setTextColor(darkColor)
                    item.descView.setTextColor(darkColor)
                    item.helpButton.setColorFilter(darkColor)
                } else {
                    item.card.strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics).toInt()
                    val typedValue = TypedValue()
                    theme.resolveAttribute(com.google.android.material.R.attr.colorOutline, typedValue, true)
                    item.card.setStrokeColor(typedValue.data)
                    item.card.setCardBackgroundColor(Color.TRANSPARENT)

                    theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedValue, true)
                    item.titleView.setTextColor(typedValue.data)

                    theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true)
                    item.descView.setTextColor(typedValue.data)
                    item.helpButton.setColorFilter(typedValue.data)
                }
            }

            val isAvailable = AudioTranscriber.isModelAvailable(this)
            if (isAvailable) {
                val size = AudioTranscriber.getModelSize(this)
                statusText.text = getString(R.string.voice_status_installed, size)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.primary_light))
                btnAction.text = getString(R.string.voice_btn_redownload)
                btnAction.setIconResource(R.drawable.ic_system_update)
                btnDelete.visibility = View.VISIBLE
            } else {
                statusText.text = getString(R.string.voice_status_not_installed)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.error_light))
                val selectedItem = uiItems.find { it.rb.isChecked }
                val sizeEstimate = when (selectedItem?.type) {
                    AudioTranscriber.TYPE_VOSK -> "~45 MB"
                    AudioTranscriber.TYPE_WHISPER_BASE -> "~148 MB"
                    AudioTranscriber.TYPE_WHISPER_SMALL -> "~488 MB"
                    AudioTranscriber.TYPE_WHISPER_SMALL_Q5 -> "~190 MB"
                    AudioTranscriber.TYPE_WHISPER_SMALL_Q8 -> "~264 MB"
                    AudioTranscriber.TYPE_WHISPER_TURBO_Q5 -> "~547 MB"
                    AudioTranscriber.TYPE_HYBRID -> "~193 MB"
                    else -> "~45 MB"
                }
                btnAction.text = getString(R.string.voice_btn_download_fmt, sizeEstimate)
                btnAction.setIconResource(R.drawable.ic_file_download)
                btnDelete.visibility = View.GONE
            }
            progressLayout.visibility = View.GONE
            btnAction.isEnabled = true
            btnDelete.isEnabled = true
            btnClose.isEnabled = true
            uiItems.forEach { it.card.isEnabled = true }
        }

        val onCardClicked = View.OnClickListener { view ->
            try {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (vibrator?.hasVibrator() == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        vibrator.vibrate(android.os.VibrationEffect.createOneShot(20, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION") vibrator.vibrate(20)
                    }
                }
            } catch (e: Exception) {}

            val clickedItem = uiItems.find { it.card == view } ?: return@OnClickListener
            uiItems.forEach { item -> item.rb.isChecked = false }
            clickedItem.rb.isChecked = true
            AudioTranscriber.setModelType(this, clickedItem.type)
            updateUI()
        }

        uiItems.forEach { item -> item.card.setOnClickListener(onCardClicked) }

        fun showHelp(title: String, message: String) {
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(getString(R.string.action_got_it), null)
                .show()
        }

        dialogView.findViewById<ImageButton>(R.id.help_vosk).setOnClickListener {
            showHelp(getString(R.string.voice_help_vosk_title), getString(R.string.voice_help_vosk_desc))
        }
        dialogView.findViewById<ImageButton>(R.id.help_whisper_base).setOnClickListener {
            showHelp(getString(R.string.voice_whisper_base_title), getString(R.string.voice_help_whisper_base_desc))
        }
        dialogView.findViewById<ImageButton>(R.id.help_whisper_small_q5).setOnClickListener {
            showHelp(getString(R.string.voice_whisper_small_q5_title), getString(R.string.voice_help_whisper_small_q5_desc))
        }
        dialogView.findViewById<ImageButton>(R.id.help_whisper_small_q8).setOnClickListener {
            showHelp(getString(R.string.voice_whisper_small_q8_title), getString(R.string.voice_help_whisper_small_q8_desc))
        }
        dialogView.findViewById<ImageButton>(R.id.help_whisper_small).setOnClickListener {
            showHelp(getString(R.string.voice_whisper_small_full_title), getString(R.string.voice_help_whisper_small_full_desc))
        }
        dialogView.findViewById<ImageButton>(R.id.help_whisper_turbo_q5).setOnClickListener {
            showHelp(getString(R.string.voice_help_whisper_turbo_title), getString(R.string.voice_help_whisper_turbo_desc))
        }
        dialogView.findViewById<ImageButton>(R.id.help_hybrid).setOnClickListener {
            showHelp(getString(R.string.voice_hybrid_title), getString(R.string.voice_help_hybrid_desc))
        }

        updateUI()

        btnAction.setOnClickListener {
            if (!isNetworkAvailable) {
                Toast.makeText(this, getString(R.string.no_internet_connection), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            progressLayout.visibility = View.VISIBLE
            progressBar.isIndeterminate = false
            progressText.text = getString(R.string.voice_download_init)
            btnAction.isEnabled = false
            btnDelete.isEnabled = false
            btnClose.isEnabled = false
            uiItems.forEach { it.card.isEnabled = false }
            dialog.setCancelable(false)

            AudioTranscriber.downloadAndInstallModel(this,
                onProgress = { progress ->
                    runOnUiThread {
                        if (progress > 100) {
                            progressBar.isIndeterminate = true
                            progressText.text = getString(R.string.voice_download_extracting)
                        } else {
                            progressBar.progress = progress
                            progressText.text = getString(R.string.voice_download_progress, progress)
                        }
                    }
                },
                onSuccess = {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.voice_model_installed_toast), Toast.LENGTH_SHORT).show()
                        dialog.setCancelable(true)
                        updateUI()
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.error_prefix, error), Toast.LENGTH_LONG).show()
                        dialog.setCancelable(true)
                        updateUI()
                    }
                }
            )
        }

        btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.voice_remove_title))
                .setMessage(getString(R.string.voice_remove_msg))
                .setPositiveButton(getString(R.string.action_remove)) { _, _ ->
                    progressLayout.visibility = View.VISIBLE
                    progressBar.isIndeterminate = true
                    progressText.text = getString(R.string.voice_removing_files)
                    btnAction.isEnabled = false
                    btnDelete.isEnabled = false
                    btnClose.isEnabled = false
                    uiItems.forEach { it.card.isEnabled = false }

                    lifecycleScope.launch(Dispatchers.IO) {
                        val success = AudioTranscriber.deleteModel(this@MainActivity)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(this@MainActivity, getString(R.string.voice_removed_toast), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, getString(R.string.voice_remove_partial_toast), Toast.LENGTH_SHORT).show()
                            }
                            updateUI()
                        }
                    }
                }
                .setNegativeButton(getString(R.string.call_dialog_btn_cancel), null)
                .show()
        }

        dialog.show()
    }

    override fun onNetworkStatusChanged(status: NetworkQualityMonitor.NetworkStatus) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            isNetworkAvailable = (status != NetworkQualityMonitor.NetworkStatus.DISCONNECTED)
            if (status != NetworkQualityMonitor.NetworkStatus.DISCONNECTED) {
                fetchUserGroups(isInitialLoad = false)
            }
        }
    }

    private fun showBetaTesterDialog() {
        if (isFinishing || isDestroyed) return
        val prefs = SecurePrefs.get(this)
        val isBetaTester = prefs.getBoolean("is_beta_tester", false)
        val title = getString(R.string.beta_enroll_title)
        val message = if (isBetaTester) getString(R.string.beta_leave_message) else getString(R.string.beta_enroll_message)
        val positiveBtn = if (isBetaTester) getString(R.string.beta_leave_positive) else getString(R.string.beta_enroll_positive)

        betaTesterDialog?.dismiss() // Chiude eventuali dialoghi precedenti rimasti aperti

        betaTesterDialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(Html.fromHtml(message, Html.FROM_HTML_MODE_COMPACT))
            .setPositiveButton(positiveBtn) { _, _ ->
                val newState = !isBetaTester
                updateBetaStatusOnServer(newState)
            }
            .setNegativeButton(getString(R.string.call_dialog_btn_cancel), null)
            .create()

        betaTesterDialog?.show()
    }

    private fun updateBetaStatusOnServer(newState: Boolean) {
        if (isFinishing || isDestroyed) return

        betaProgressDialog?.dismiss() // Chiude eventuali dialoghi precedenti rimasti aperti

        betaProgressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.beta_syncing_title))
            .setView(ProgressBar(this).apply { setPadding(50, 50, 50, 50) })
            .setCancelable(false)
            .create()

        betaProgressDialog?.show()

        ApiClient.instance.updateBetaStatus(userId = userId, isBetaTester = newState).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                if (isFinishing || isDestroyed) return
                betaProgressDialog?.dismiss()

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body?.status == "success") {
                        Log.d("MainActivity", "Stato programma Beta sincronizzato con successo sul server.")

                        val prefs = SecurePrefs.get(this@MainActivity)
                        prefs.edit().apply {
                            putBoolean("is_beta_tester", newState)
                            putBoolean("is_beta_synced_to_server_v1", true)
                            commit()
                        }

                        AutoUpdateManager.getInstance(this@MainActivity).onBetaStatusChanged()

                        val toastMsg = if (newState) getString(R.string.beta_joined_toast) else getString(R.string.beta_left_toast)
                        Toast.makeText(this@MainActivity, toastMsg, Toast.LENGTH_SHORT).show()

                        recreate()
                    } else {
                        Log.e("MainActivity", "Errore logico server: ${body?.message}")
                        Toast.makeText(this@MainActivity, getString(R.string.error_prefix, body?.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                } else {
                    val httpCode = response.code()
                    val errorBodyString = response.errorBody()?.string()

                    Log.e("MainActivity", "Errore HTTP del server: $httpCode | Dettagli: $errorBodyString")
                    Toast.makeText(this@MainActivity, getString(R.string.beta_server_error_fmt, httpCode), Toast.LENGTH_LONG).show()
                }
            }

            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                if (isFinishing || isDestroyed) return
                betaProgressDialog?.dismiss()
                Log.e("MainActivity", "Connessione di rete fallita per la sincronizzazione dello stato Beta.", t)
                Toast.makeText(this@MainActivity, getString(R.string.connection_error_retry), Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun checkAllMandatoryPermissions() {
        if (isFinishing || isDestroyed) return
        val prefs = SecurePrefs.get(this)

        // --------------------------------------------------------------------------------
        // FASE 1: Permessi Runtime di base (Microfono, Fotocamera, Notifiche)
        // --------------------------------------------------------------------------------
        val missingPermissions = getMissingRuntimePermissions()
        if (missingPermissions.isNotEmpty()) {
            showRuntimePermissionsDialog(missingPermissions)
            return
        }

        // --------------------------------------------------------------------------------
        // FASE 2: Accesso a tutti i file (MANAGE_EXTERNAL_STORAGE) - Android 11+
        // --------------------------------------------------------------------------------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showAllFilesPermissionDialog()
                return
            }
        }

        // --------------------------------------------------------------------------------
        // FASE 3: Visualizzazione sopra altre app (Overlay)
        // --------------------------------------------------------------------------------
        val isOverlayGranted = Settings.canDrawOverlays(this)
        val skipOverlay = prefs.getBoolean("skip_overlay_permission", false)

        if (isOverlayGranted) {
            // Se l'utente lo ha attivato manualmente dalle impostazioni Android,
            // puliamo in automatico i flag di "salto"
            if (skipOverlay || prefs.contains("overlay_settings_visited")) {
                prefs.edit().remove("skip_overlay_permission").remove("overlay_settings_visited").apply()
            }
        } else if (!skipOverlay) {
            // Se non è concesso e NON è stato saltato, mostra il dialogo
            showOverlayPermissionDialog()
            return
        }

        // Se tutte le fasi bloccanti precedenti sono state superate, chiude i dialoghi attivi
        permissionDialog?.dismiss()
        permissionDialog = null

        // --------------------------------------------------------------------------------
        // FASE 4: Ottimizzazione della batteria (Esclusione dal risparmio energetico)
        // --------------------------------------------------------------------------------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                handler.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                            showStandardBatteryOptimizationDialog()
                        }
                    }
                }, 400)
                return
            }
        }

        PowerSaverHelper.checkAndRequestPermissions(this)

        // --------------------------------------------------------------------------------
        // FASE FINALE: Conferma completamento setup (Mostrato solo una volta)
        // --------------------------------------------------------------------------------
        val setupCompleted = prefs.getBoolean("setup_completed", false)
        if (!setupCompleted) {
            prefs.edit().putBoolean("setup_completed", true).apply()
            Toast.makeText(this, getString(R.string.all_perms_configured), Toast.LENGTH_LONG).show()
        }
    }

    private fun showStandardBatteryOptimizationDialog() {
        if (isFinishing || isDestroyed || permissionDialog?.isShowing == true) return
        permissionDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.battery_perm_title))
            .setMessage(getString(R.string.battery_perm_message))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.battery_perm_fix)) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    batteryOptimizationLauncher.launch(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    batteryOptimizationLauncher.launch(intent)
                }
            }
            .show()
    }

    private fun showAllFilesPermissionDialog() {
        if (isFinishing || isDestroyed || permissionDialog?.isShowing == true) return
        permissionDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.perm_all_files_title))
            .setMessage(Html.fromHtml(getString(R.string.perm_all_files_message), Html.FROM_HTML_MODE_COMPACT))
            .setCancelable(false) // Impedisce la chiusura accidentale toccando all'esterno
            .setPositiveButton(getString(R.string.perm_all_files_btn)) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        manageAllFilesPermissionLauncher.launch(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        manageAllFilesPermissionLauncher.launch(intent)
                    }
                }
            }
            .show()
    }


    private fun getMissingRuntimePermissions(): List<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        return permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun showOverlayPermissionDialog() {
        if (isFinishing || isDestroyed || permissionDialog?.isShowing == true) return

        val prefs = SecurePrefs.get(this)
        val visitedSettings = prefs.getBoolean("overlay_settings_visited", false)

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.perm_overlay_title))
            .setMessage(Html.fromHtml(
                getString(R.string.perm_overlay_message) +
                        getString(R.string.perm_overlay_restricted_note),
                Html.FROM_HTML_MODE_COMPACT
            ))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.perm_overlay_btn)) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    overlayPermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    overlayPermissionLauncher.launch(intent)
                }
            }
            .setNeutralButton(getString(R.string.perm_unlock_guide_btn)) { _, _ ->
                showRestrictedSettingsGuideDialog()
            }

        // Il pulsante "Salta Permesso" compare SOLO se l'utente è già stato nelle impostazioni ed è tornato indietro
        if (visitedSettings) {
            builder.setNegativeButton(getString(R.string.perm_overlay_skip)) { _, _ ->
                prefs.edit().putBoolean("skip_overlay_permission", true).apply()
                permissionDialog?.dismiss()
                permissionDialog = null
                Toast.makeText(this, getString(R.string.perm_overlay_skipped_toast), Toast.LENGTH_SHORT).show()
                checkAllMandatoryPermissions()
            }
        }

        permissionDialog = builder.show()
    }

    private fun showRestrictedSettingsGuideDialog() {
        if (isFinishing || isDestroyed) return

        val guideMessage = getString(R.string.restricted_guide_html)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.restricted_guide_title))
            .setMessage(Html.fromHtml(guideMessage, Html.FROM_HTML_MODE_COMPACT))
            .setPositiveButton(getString(R.string.notification_permission_positive)) { _, _ ->
                // Riapre il dialogo principale di blocco costringendo l'utente ad abilitare il permesso reale
                showOverlayPermissionDialog()
            }
            .setCancelable(false)
            .show()
    }

    private fun showRuntimePermissionsDialog(permissions: List<String>) {
        if (isFinishing || isDestroyed || permissionDialog?.isShowing == true) return
        permissionDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.perm_essential_title))
            .setMessage(Html.fromHtml(getString(R.string.perm_essential_message), Html.FROM_HTML_MODE_COMPACT))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.perm_essential_btn)) { _, _ ->
                requestMultiplePermissionsLauncher.launch(permissions.toTypedArray())
            }
            .show()
    }

    private fun checkExactAlarmPermission() {
        if (isFinishing || isDestroyed) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // Utilizzo del nome di classe qualificato per evitare conflitti o errori di importazione
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager

            // Verifichiamo in tempo reale se l'utente ha già rimosso i limiti della batteria
            val isBatteryExempted = powerManager.isIgnoringBatteryOptimizations(packageName)

            // Se l'ottimizzazione della batteria è già stata disattivata (Senza restrizioni),
            // saltiamo completamente il controllo degli allarmi esatti per prevenire il blocco di Android 14/15.
            if (!isBatteryExempted && !alarmManager.canScheduleExactAlarms()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.exact_alarm_permission_title))
                    .setMessage(getString(R.string.exact_alarm_permission_message))
                    .setPositiveButton(getString(R.string.notification_permission_settings)) { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                        startActivity(intent)
                    }
                    .setCancelable(false)
                    .show()
            } else {
                // Se gli allarmi sono già attivi, o se l'utente ha già impostato l'app su "Senza restrizioni",
                // procediamo verso il risparmio energetico
                PowerSaverHelper.checkAndRequestPermissions(this)
            }
        } else {
            PowerSaverHelper.checkAndRequestPermissions(this)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_scan_qr -> { startQrScanner(); true }
            R.id.action_search -> { startActivity(Intent(this, SearchUserActivity::class.java)); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showNotificationSettingsDialog() {
        if (isFinishing || isDestroyed) return
        val dialogBinding = DialogNotificationSettingsBinding.inflate(layoutInflater)
        val prefs = SecurePrefs.get(this)

        val currentDuration = prefs.getInt("mention_sound_duration", 10)
        val currentRespectDnd = prefs.getBoolean("mention_respect_dnd", false)
        val currentShowContent = prefs.getBoolean("show_notification_content", false)

        dialogBinding.durationSlider.value = currentDuration.toFloat()
        dialogBinding.durationLabel.text = getString(R.string.notification_sound_duration_label, currentDuration)
        dialogBinding.switchRespectDnd.isChecked = currentRespectDnd
        dialogBinding.switchShowContent.isChecked = currentShowContent

        dialogBinding.durationSlider.addOnChangeListener { _, value, _ ->
            dialogBinding.durationLabel.text = getString(R.string.notification_sound_duration_label, value.toInt())
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.notification_settings_title))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.action_save)) { _, _ ->
                val newDuration = dialogBinding.durationSlider.value.toInt()
                val newRespectDnd = dialogBinding.switchRespectDnd.isChecked
                val newShowContent = dialogBinding.switchShowContent.isChecked

                prefs.edit {
                    putInt("mention_sound_duration", newDuration)
                    putBoolean("mention_respect_dnd", newRespectDnd)
                    putBoolean("show_notification_content", newShowContent)
                }
                Toast.makeText(this, getString(R.string.settings_saved_short), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.call_dialog_btn_cancel), null)
            .show()
    }

    private fun showChangeUsernameDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_change_username, null)
        val newUsernameEditText = dialogView.findViewById<EditText>(R.id.editTextNewUsername)
        val passwordEditText = dialogView.findViewById<EditText>(R.id.editTextCurrentPassword)
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.action_change_username)).setView(dialogView)
            .setPositiveButton(getString(R.string.action_save)) { _, _ ->
                val newUsername = newUsernameEditText.text.toString().trim()
                val password = passwordEditText.text.toString().trim()
                if (newUsername.length < 3) { Toast.makeText(this, getString(R.string.username_min_3), Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                if (password.isEmpty()) { Toast.makeText(this, getString(R.string.password_required_confirm), Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                changeUsername(newUsername, password)
            }
            .setNegativeButton(getString(R.string.call_dialog_btn_cancel), null).show()
    }

    private fun showFeedbackDialog() {
        if (isFinishing || isDestroyed) return

        val context = this
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()
            setPadding(padding, 16, padding, 0)
        }

        val descText = TextView(context).apply {
            text = getString(R.string.feedback_desc)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.outline_dark))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
            }
            layoutParams = params
        }
        rootLayout.addView(descText)

        val categoryLabel = TextView(context).apply {
            text = getString(R.string.feedback_type_label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.primary_light))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
            }
            layoutParams = params
        }
        rootLayout.addView(categoryLabel)

        val chipGroup = ChipGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
            }
            layoutParams = params
        }

        val chipBug = Chip(context).apply {
            text = getString(R.string.feedback_chip_bug)
            isCheckable = true
            isChecked = true
        }
        chipGroup.addView(chipBug)

        val chipFeature = Chip(context).apply {
            text = getString(R.string.feedback_chip_idea)
            isCheckable = true
        }
        chipGroup.addView(chipFeature)
        rootLayout.addView(chipGroup)

        val textInputLayout = TextInputLayout(context).apply {
            hint = getString(R.string.feedback_hint_details)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            isCounterEnabled = true
            counterMaxLength = 500
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params
        }

        val editText = TextInputEditText(context).apply {
            minLines = 4
            maxLines = 8
            gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(android.text.InputFilter.LengthFilter(500))
        }
        textInputLayout.addView(editText)
        rootLayout.addView(textInputLayout)

        chipBug.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                textInputLayout.hint = getString(R.string.feedback_hint_bug)
                textInputLayout.helperText = getString(R.string.feedback_helper_bug)
            }
        }
        chipFeature.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                textInputLayout.hint = getString(R.string.feedback_hint_idea)
                textInputLayout.helperText = getString(R.string.feedback_helper_idea)
            }
        }

        textInputLayout.helperText = getString(R.string.feedback_helper_bug)

        MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.feedback_dialog_title))
            .setIcon(R.drawable.ic_feedback)
            .setView(rootLayout)
            .setNegativeButton(getString(R.string.call_dialog_btn_cancel), null)
            .setPositiveButton(getString(R.string.cd_send)) { _, _ ->
                val feedbackText = editText.text.toString().trim()
                if (feedbackText.isNotEmpty()) {
                    val category = if (chipBug.isChecked) "[BUG]" else "[IDEA]"
                    val finalFeedback = "$category $feedbackText"
                    sendFeedback(finalFeedback)
                } else {
                    Toast.makeText(context, getString(R.string.feedback_empty_toast), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showClearCacheConfirmationDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.clear_cache_title))
            .setMessage(getString(R.string.clear_cache_msg))
            .setNegativeButton(getString(R.string.call_dialog_btn_cancel), null)
            .setPositiveButton(getString(R.string.clear_cache_confirm)) { _, _ -> clearDownloadedFilesCache() }
            .show()
    }

    private fun showResetAppConfirmationDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.reset_app_title))
            .setMessage(getString(R.string.reset_app_msg))
            .setPositiveButton(getString(R.string.reset_app_confirm)) { _, _ -> performAppReset() }
            .setNegativeButton(getString(R.string.call_dialog_btn_cancel), null).show()
    }

    private fun showDeleteConfirmationDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.delete_account_title))
            .setMessage(getString(R.string.delete_account_msg))
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(getString(R.string.delete_account_confirm)) { _, _ -> deleteUserAccount() }
            .setNegativeButton(getString(R.string.call_dialog_btn_cancel), null).show()
    }

    private fun showBiometricAuthDialog() {
        if (isFinishing || isDestroyed) return
        if (!BiometricHelper.canAuthenticate(this)) { Toast.makeText(this, getString(R.string.biometric_not_supported), Toast.LENGTH_LONG).show(); return }
        val prefs = SecurePrefs.get(this)
        val isEnabled = prefs.getBoolean("biometric_auth_enabled", false)
        val title = if (isEnabled) getString(R.string.biometric_disable_title) else getString(R.string.biometric_enable_title)
        val message = if (isEnabled) getString(R.string.biometric_disable_msg) else getString(R.string.biometric_enable_msg)
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setNegativeButton(getString(R.string.call_dialog_btn_cancel), null)
            .setPositiveButton(if (isEnabled) getString(R.string.action_disable) else getString(R.string.action_enable)) { _, _ ->
                prefs.edit { putBoolean("biometric_auth_enabled", !isEnabled) }
                Toast.makeText(this, getString(R.string.setting_saved_short), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showRootToolsDialog() {
        if (isFinishing || isDestroyed) return

        val isRooted = RootDetectionHelper.isDeviceRooted(this)
        val isWhitelisted = false

        if (!isRooted || !isWhitelisted) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.access_denied_title))
                .setMessage(getString(R.string.root_menu_dev_only))
                .setPositiveButton(getString(R.string.ok), null)
                .show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_root_tools, null)
        val btnClearCache = dialogView.findViewById<MaterialButton>(R.id.btn_root_clear_cache)
        val btnWipeData = dialogView.findViewById<MaterialButton>(R.id.btn_root_wipe_data)
        val btnCorruptKeys = dialogView.findViewById<MaterialButton>(R.id.btn_root_corrupt_keys)
        val btnForceStop = dialogView.findViewById<MaterialButton>(R.id.btn_root_force_stop)
        val btnDumpDb = dialogView.findViewById<MaterialButton>(R.id.btn_root_dump_db)
        val btnDumpLogs = dialogView.findViewById<MaterialButton>(R.id.btn_root_dump_logs)
        val btnRunCustom = dialogView.findViewById<MaterialButton>(R.id.btn_run_custom_command)
        val editCommand = dialogView.findViewById<TextInputEditText>(R.id.edit_custom_command)
        val textOutput = dialogView.findViewById<TextView>(R.id.text_terminal_output)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.action_close), null)
            .show()

        fun runRootCommandUI(command: String) {
            textOutput.text = getString(R.string.root_running_fmt, command)
            lifecycleScope.launch(Dispatchers.IO) {
                val (success, output) = RootDetectionHelper.executeCommandAsRoot(command)
                withContext(Dispatchers.Main) {
                    val statusText = if (success) "[SUCCESS]" else "[FAILED]"
                    val finalOutput = if (output.isBlank()) getString(R.string.root_no_output) else output
                    textOutput.text = "> $command\n$statusText\n\n$finalOutput"
                }
            }
        }

        btnClearCache.setOnClickListener {
            runRootCommandUI("rm -rf /data/data/$packageName/cache/* && rm -rf /data/data/$packageName/code_cache/*")
        }

        btnWipeData.setOnClickListener {
            Toast.makeText(this, getString(R.string.root_wipe_in_progress), Toast.LENGTH_SHORT).show()
            Thread { RootDetectionHelper.executeCommandAsRoot("pm clear $packageName") }.start()
        }

        btnCorruptKeys.setOnClickListener {
            runRootCommandUI("rm -f /data/data/$packageName/shared_prefs/cryptionchat_secure_prefs.xml")
        }

        btnForceStop.setOnClickListener {
            Toast.makeText(this, getString(R.string.root_force_stop_toast), Toast.LENGTH_SHORT).show()
            Thread { RootDetectionHelper.executeCommandAsRoot("am force-stop $packageName") }.start()
        }

        btnDumpDb.setOnClickListener {
            val dumpSavedMsg = getString(R.string.root_dump_saved_msg)
            val cmd = """
                mkdir -p /sdcard/Download/CryptionChat_Dump
                cp -r /data/data/$packageName/databases /sdcard/Download/CryptionChat_Dump/
                cp -r /data/data/$packageName/shared_prefs /sdcard/Download/CryptionChat_Dump/
                chmod -R 777 /sdcard/Download/CryptionChat_Dump
                echo "$dumpSavedMsg"
            """.trimIndent()
            runRootCommandUI(cmd)
        }

        btnDumpLogs.setOnClickListener {
            val logcatSavedMsg = getString(R.string.root_logcat_saved_msg)
            val cmd = """
                logcat -d > /sdcard/Download/cryptionchat_logcat.txt
                chmod 777 /sdcard/Download/cryptionchat_logcat.txt
                echo "$logcatSavedMsg"
            """.trimIndent()
            runRootCommandUI(cmd)
        }

        btnRunCustom.setOnClickListener {
            val cmd = editCommand.text.toString().trim()
            if (cmd.isNotEmpty()) {
                runRootCommandUI(cmd)
            }
        }
    }

    private fun setupRecyclerView() {
        groupAdapter = GroupAdapter(emptyList(), currentUsername = username,
            onGroupClicked = { group ->
                // Permette l'accesso alla chat anche offline per consultare i messaggi salvati nel DB locale
                val intent = Intent(this, ChatActivity::class.java).apply {
                    putExtra("GROUP_ID", group.id)
                    putExtra("GROUP_NAME", group.groupName)
                    putExtra("USER_ID", userId)
                    putExtra("USERNAME", username)
                    putExtra("IS_PRIVATE_CHAT", group.isPrivateChat)
                }
                startActivity(intent)
            },
            onGroupLongClicked = { group ->
                showGroupOptionsDialog(group)
            },
            onGroupIconClicked = { group -> showImageDialog(group) }
        )
        binding.recyclerViewGroups.apply {
            adapter = groupAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
    }

    private fun setupGroupPolling() {
        groupPollingRunnable = Runnable { if (!isFinishing && !isDestroyed && isNetworkAvailable) fetchUserGroups(isInitialLoad = false); handler.postDelayed(groupPollingRunnable, pollingInterval) }
    }

    private fun startGroupPolling() { stopGroupPolling(); handler.postDelayed(groupPollingRunnable, pollingInterval); }
    private fun stopGroupPolling() { handler.removeCallbacks(groupPollingRunnable); }

    private fun fetchUserGroups(isInitialLoad: Boolean) {
        if (isInitialLoad && groupAdapter.itemCount == 0) { binding.progressBar.visibility = View.VISIBLE; binding.layoutEmptyGroups.visibility = View.GONE; binding.recyclerViewGroups.visibility = View.GONE }
        ApiClient.instance.getUserGroups(userId = userId).enqueue(object : Callback<GetGroupsResponse> {
            override fun onResponse(call: Call<GetGroupsResponse>, response: Response<GetGroupsResponse>) {
                if (isFinishing || isDestroyed) return
                binding.progressBar.visibility = View.GONE
                val getGroupsResponse = response.body()
                if (response.isSuccessful && getGroupsResponse?.status == "success") {
                    val groups = getGroupsResponse.data ?: emptyList()

                    // Calcolo ed allineamento locale del badge inferiore escludendo i messaggi di stato ZK
                    val prefs = SecurePrefs.get(this@MainActivity)
                    var prefsEditor: android.content.SharedPreferences.Editor? = null
                    groups.forEach { g ->
                        val verKey = "e2ee_version_${g.id}"
                        val epochKey = "membership_epoch_${g.id}"
                        if (prefs.getInt(verKey, -1) != g.e2eeVersion) {
                            (prefsEditor ?: prefs.edit().also { prefsEditor = it }).putInt(verKey, g.e2eeVersion)
                        }
                        if (prefs.getInt(epochKey, -1) != g.membershipEpoch) {
                            (prefsEditor ?: prefs.edit().also { prefsEditor = it }).putInt(epochKey, g.membershipEpoch)
                        }
                    }
                    prefsEditor?.apply()

                    val totalUnread = groups.sumOf { group ->
                        var adjustedCount = group.unreadCount
                        if (adjustedCount > 0 && !group.lastMessage.isNullOrBlank()) {
                            val isZk = prefs.getBoolean("is_zk_${group.id}", false)
                            val key = if (isZk) prefs.getString("zk_key_${group.id}", null) else prefs.getString("key_${group.id}", null)
                            val iv = if (isZk) prefs.getString("zk_iv_${group.id}", null) else prefs.getString("iv_${group.id}", null)

                            val isE2eeV2Payload = group.lastMessage.startsWith(SenderKeyHelper.SK_PREFIX) ||
                                    group.lastMessage.startsWith(DoubleRatchetHelper.DR_PREFIX)

                            var decrypted = if ((key != null && iv != null) || isE2eeV2Payload) {
                                EncryptionHelper.decrypt(group.lastMessage, key ?: "", iv ?: "")
                            } else null

                            if (decrypted.isNullOrBlank() && group.lastMessage.startsWith(DoubleRatchetHelper.DR_PREFIX) && group.isPrivateChat) {
                                val peerId = listOf(
                                    "private_peer_uid_${group.id}",
                                    "peer_user_id_${group.id}",
                                    "other_user_id_${group.id}",
                                    "private_peer_${group.id}"
                                ).firstNotNullOfOrNull { prefKey ->
                                    prefs.getInt(prefKey, -1).takeIf { it > 0 }
                                }
                                if (peerId != null) {
                                    decrypted = DoubleRatchetHelper.decryptMessage(
                                        DoubleRatchetHelper.pairwiseSessionKey(peerId),
                                        group.lastMessage
                                    )
                                }
                            }

                            if (decrypted.isNullOrBlank() && isZk) {
                                decrypted = EncryptionHelper.decrypt(group.lastMessage, prefs.getString("key_${group.id}", "") ?: "", prefs.getString("iv_${group.id}", "") ?: "")
                            }

                            val isSystemStatus = decrypted != null && (
                                    decrypted.startsWith("[ZK_ECDH]:") ||
                                    decrypted.startsWith("[ZK_EXCHANGE]:") ||
                                            decrypted.contains("ha attivato la Chat Ultra Sicura") ||
                                            decrypted.contains("Le chiavi della Chat Ultra Sicura sono state rinegoziate") ||
                                            decrypted.contains("ha rigenerato la chiave") ||
                                            decrypted.contains("ha disattivato la Chat Ultra Sicura")
                                    )

                            if (isSystemStatus) {
                                if (adjustedCount == 1) {
                                    adjustedCount = 0
                                } else if (adjustedCount > 1) {
                                    adjustedCount -= 1
                                }
                            }
                        }
                        adjustedCount
                    }
                    updateChatsBadgeCount(totalUnread)

                    if (groups.isEmpty()) { binding.layoutEmptyGroups.visibility = View.VISIBLE; binding.recyclerViewGroups.visibility = View.GONE }
                    else {
                        binding.layoutEmptyGroups.visibility = View.GONE; binding.recyclerViewGroups.visibility = View.VISIBLE
                        val pinnedGroupIds = prefs.getStringSet("pinned_group_ids", emptySet()) ?: emptySet()

                        val sortedGroups = groups.sortedByDescending { it.lastMessageTimestamp }

                        val listItems = mutableListOf<GroupListItem>()
                        val pinnedList = sortedGroups.filter { it.id.toString() in pinnedGroupIds }
                        val recentList = sortedGroups.filter { it.id.toString() !in pinnedGroupIds }

                        if (pinnedList.isNotEmpty()) {
                            listItems.add(GroupListItem.Header(getString(R.string.section_pinned)))
                            listItems.addAll(pinnedList.map { GroupListItem.GroupData(it, true) })
                        }
                        if (recentList.isNotEmpty()) {
                            listItems.add(GroupListItem.Header(getString(R.string.section_recent)))
                            listItems.addAll(recentList.map { GroupListItem.GroupData(it, false) })
                        }

                        groupAdapter.updateItems(listItems, username)
                        syncMissingKeys(groups)
                    }
                } else Log.e("MainActivity", "Errore API nel recupero dei gruppi: ${getGroupsResponse?.message}")
            }
            override fun onFailure(call: Call<GetGroupsResponse>, t: Throwable) {
                if (isFinishing || isDestroyed) return
                binding.progressBar.visibility = View.GONE
                Log.e("MainActivity", "Errore di rete nel recupero dei gruppi: ${t.message}")
            }
        })
    }

    private fun showAddGroupOptions() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_group, null)
        val createOption = dialogView.findViewById<LinearLayout>(R.id.option_create_group)
        val joinOption = dialogView.findViewById<LinearLayout>(R.id.option_join_group)
        val dialog = MaterialAlertDialogBuilder(this).setView(dialogView).create()
        createOption.setOnClickListener { showCreateGroupDialog(); dialog.dismiss() }
        joinOption.setOnClickListener { showJoinGroupDialog(); dialog.dismiss() }
        dialog.show()
    }

    private fun showCreateGroupDialog() {
        if (isFinishing || isDestroyed) return
        val input = EditText(this).apply { hint = getString(R.string.group_name_hint_lower) }
        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val margin = (24 * resources.displayMetrics.density).toInt()
        params.leftMargin = margin; params.rightMargin = margin
        input.layoutParams = params
        container.addView(input)
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.action_create_new_group)).setView(container)
            .setPositiveButton(getString(R.string.action_create)) { _, _ ->
                val groupName = input.text.toString().trim()
                if (groupName.isNotEmpty()) createGroup(groupName) else Toast.makeText(this, getString(R.string.group_name_empty_toast), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.call_dialog_btn_cancel), null).show()
    }

    private fun showJoinGroupDialog() {
        if (isFinishing || isDestroyed) return
        val input = EditText(this).apply { hint = getString(R.string.group_or_invite_code_hint); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val margin = (24 * resources.displayMetrics.density).toInt()
        params.leftMargin = margin; params.rightMargin = margin
        input.layoutParams = params
        container.addView(input)
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.action_join_group)).setView(container)
            .setPositiveButton(getString(R.string.action_join)) { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotEmpty()) joinGroup(code) else Toast.makeText(this, getString(R.string.code_empty_toast), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.call_dialog_btn_cancel), null).show()
    }

    private fun createGroup(groupName: String) {
        ApiClient.instance.createGroup(userId = userId, groupName = groupName).enqueue(object: Callback<CreateGroupResponse> {
            override fun onResponse(call: Call<CreateGroupResponse>, response: Response<CreateGroupResponse>) {
                val createGroupResponse = response.body()
                if (response.isSuccessful && createGroupResponse?.status == "success") { Toast.makeText(this@MainActivity, getString(R.string.group_created_toast), Toast.LENGTH_SHORT).show(); fetchUserGroups(isInitialLoad = true) }
                else Toast.makeText(this@MainActivity, getString(R.string.error_prefix, createGroupResponse?.message ?: ""), Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(call: Call<CreateGroupResponse>, t: Throwable) { Toast.makeText(this@MainActivity, getString(R.string.error_network_msg, t.message ?: ""), Toast.LENGTH_SHORT).show() }
        })
    }

    private fun joinGroup(code: String) {
        ApiClient.instance.joinGroup(userId = userId, code = code).enqueue(object: Callback<JoinGroupResponse> {
            override fun onResponse(call: Call<JoinGroupResponse>, response: Response<JoinGroupResponse>) {
                val joinGroupResponse = response.body()
                if (response.isSuccessful && joinGroupResponse?.status == "success") { Toast.makeText(this@MainActivity, getString(R.string.group_joined_toast), Toast.LENGTH_SHORT).show(); fetchUserGroups(isInitialLoad = true) }
                else Toast.makeText(this@MainActivity, getString(R.string.error_prefix, joinGroupResponse?.message ?: ""), Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(call: Call<JoinGroupResponse>, t: Throwable) { Toast.makeText(this@MainActivity, getString(R.string.error_network_msg, t.message ?: ""), Toast.LENGTH_SHORT).show() }
        })
    }

    private fun handleDeepLink(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data != null && data.scheme == "https" && data.host?.contains("your-server.example.com") == true) {
            if (data.path?.startsWith("/CryptionChat/join.php") == true) {
                val code = data.getQueryParameter("code")
                if (!code.isNullOrBlank()) { Log.d("DeepLink", "Trovato codice dal link: $code"); Toast.makeText(this, getString(R.string.deep_link_joining_fmt, code), Toast.LENGTH_SHORT).show(); joinGroup(code); intent.data = null }
            }
        }
    }

    private fun startQrScanner() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt(getString(R.string.qr_prompt_invite))
        options.setCameraId(0)
        options.setBeepEnabled(false)
        options.setBarcodeImageEnabled(false)
        options.setCaptureActivity(PortraitCaptureActivity::class.java)
        options.setOrientationLocked(true)
        barcodeLauncher.launch(options)
    }

    private fun handleScannedQrCode(scannedContent: String) {
        val uri = Uri.parse(scannedContent)
        val code = uri.getQueryParameter("code")
        if (!code.isNullOrBlank()) { Toast.makeText(this, getString(R.string.qr_code_detected_joining, code), Toast.LENGTH_SHORT).show(); joinGroup(code) }
        else { if (scannedContent.length in 4..20) joinGroup(scannedContent) else Toast.makeText(this, getString(R.string.qr_invalid_cryptionchat), Toast.LENGTH_LONG).show() }
    }

    private fun checkForBroadcastMessage() {
        ApiClient.instance.getBroadcastMessage().enqueue(object : Callback<ApiResponse<BroadcastMessageData>> {
            override fun onResponse(call: Call<ApiResponse<BroadcastMessageData>>, response: Response<ApiResponse<BroadcastMessageData>>) {
                if (isFinishing || isDestroyed) return
                if (response.isSuccessful && response.body()?.status == "success") {
                    val broadcastData = response.body()?.data
                    if (broadcastData != null && broadcastData.isActive) {
                        val prefs = SecurePrefs.get(this@MainActivity)
                        val lastSeenId = prefs.getLong("last_seen_broadcast_id", 0L)
                        if (broadcastData.messageId > lastSeenId) showBroadcastMessageDialog(broadcastData)
                    }
                }
            }
            override fun onFailure(call: Call<ApiResponse<BroadcastMessageData>>, t: Throwable) { Log.e("MainActivity", "Errore nel recupero del messaggio broadcast", t) }
        })
    }

    private fun showBroadcastMessageDialog(data: BroadcastMessageData) {
        if (isFinishing || isDestroyed) return
        val dialogTitle = data.messageTitle.ifEmpty { getString(R.string.broadcast_default_title) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(dialogTitle).setMessage(data.messageText)
            .setPositiveButton(getString(R.string.ok)) { dialog, _ -> SecurePrefs.get(this@MainActivity).edit { putLong("last_seen_broadcast_id", data.messageId) }; dialog.dismiss() }
            .setCancelable(false).show()
        val messageView = dialog.findViewById<TextView>(android.R.id.message)
        if (messageView != null) { Linkify.addLinks(messageView, Linkify.WEB_URLS); messageView.movementMethod = LinkMovementMethod.getInstance() }
    }

    private fun updateUserAppVersionOnServer() {
        if (userId == -1) return
        val prefs = SecurePrefs.get(this)
        val currentVersionCode = BuildConfig.VERSION_CODE
        val currentVersionName = BuildConfig.VERSION_NAME
        val lastUploadedUserId = prefs.getInt("last_uploaded_version_user_id", -1)
        val lastUploadedVersionCode = prefs.getInt("last_uploaded_version_code", -1)
        val lastUploadedVersionName = prefs.getString("last_uploaded_version_name", "")
        if (userId != lastUploadedUserId ||
            currentVersionCode != lastUploadedVersionCode ||
            currentVersionName != lastUploadedVersionName
        ) {
            ApiClient.instance.updateUserVersion(userId, currentVersionName, currentVersionCode).enqueue(object : Callback<BaseResponse> {
                override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                    if (response.isSuccessful && response.body()?.status == "success") {
                        prefs.edit {
                            putInt("last_uploaded_version_user_id", userId)
                            putInt("last_uploaded_version_code", currentVersionCode)
                            putString("last_uploaded_version_name", currentVersionName)
                        }
                    }
                }
                override fun onFailure(call: Call<BaseResponse>, t: Throwable) { }
            })
        }
    }


    private fun changeUsername(newUsername: String, password: String) {
        ApiClient.instance.changeUsername(userId = userId, newUsername = newUsername, password = password).enqueue(object : Callback<ChangeUsernameResponse> {
            override fun onResponse(call: Call<ChangeUsernameResponse>, response: Response<ChangeUsernameResponse>) {
                if (isFinishing || isDestroyed) return
                val changeResponse = response.body()
                if (response.isSuccessful && changeResponse?.status == "success") {
                    val updatedUsername = changeResponse.data?.newUsername ?: newUsername
                    SecurePrefs.get(this@MainActivity).edit { putString("username", updatedUsername) }
                    this@MainActivity.username = updatedUsername
                    fetchUserGroups(isInitialLoad = false)
                    updateMeTabUI()
                    Toast.makeText(this@MainActivity, getString(R.string.username_updated_toast), Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this@MainActivity, getString(R.string.error_prefix, changeResponse?.message ?: ""), Toast.LENGTH_LONG).show()
            }
            override fun onFailure(call: Call<ChangeUsernameResponse>, t: Throwable) { if (isFinishing || isDestroyed) return; Toast.makeText(this@MainActivity, getString(R.string.error_network_msg, t.message ?: ""), Toast.LENGTH_LONG).show() }
        })
    }

    private fun sendFeedback(feedbackText: String) {
        val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        val deviceModel = Build.MODEL
        val deviceBrand = Build.MANUFACTURER
        val osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        ApiClient.instance.sendFeedback(userId = userId, username = username, feedbackText = feedbackText, appVersion = appVersion, deviceModel = deviceModel, deviceBrand = deviceBrand, osVersion = osVersion)
            .enqueue(object : Callback<BaseResponse> {
                override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) { if (isFinishing || isDestroyed) return; if (response.isSuccessful && response.body()?.status == "success") Toast.makeText(this@MainActivity, getString(R.string.feedback_sent_thanks), Toast.LENGTH_LONG).show() else Toast.makeText(this@MainActivity, getString(R.string.error_prefix, response.body()?.message ?: ""), Toast.LENGTH_LONG).show() }
                override fun onFailure(call: Call<BaseResponse>, t: Throwable) { if (isFinishing || isDestroyed) return; Toast.makeText(this@MainActivity, getString(R.string.error_network_msg, t.message ?: ""), Toast.LENGTH_LONG).show() }
            })
    }

    private fun uploadProfilePicture(uri: Uri) {
        val progressIndicator = findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.me_profile_progress)
        progressIndicator?.progress = 0
        progressIndicator?.visibility = View.VISIBLE

        val file = FileUtils.getFileFromUri(this, uri) ?: run {
            progressIndicator?.visibility = View.GONE
            Toast.makeText(this, getString(R.string.cannot_open_selected_file), Toast.LENGTH_SHORT).show()
            return
        }

        val mimeType = contentResolver.getType(uri) ?: "image/*"

        // ProgressRequestBody invia l'avanzamento reale in percentuale (0..100) all'indicatore circolare
        val requestFile = ProgressRequestBody(file, mimeType) { progress ->
            runOnUiThread {
                progressIndicator?.progress = progress
            }
        }

        val body = MultipartBody.Part.createFormData("profile_picture", file.name, requestFile)
        val action = "upload_profile_picture".toRequestBody("text/plain".toMediaTypeOrNull())
        val userIdBody = userId.toString().toRequestBody("text/plain".toMediaTypeOrNull())

        ApiClient.instance.uploadProfilePicture(action, userIdBody, body).enqueue(object : Callback<ProfilePictureResponse> {
            override fun onResponse(call: Call<ProfilePictureResponse>, response: Response<ProfilePictureResponse>) {
                progressIndicator?.visibility = View.GONE
                if (isFinishing || isDestroyed) return
                if (response.isSuccessful && response.body()?.status == "success") {
                    val newUrl = response.body()?.data?.profilePictureUrl
                    SecurePrefs.get(this@MainActivity).edit { putString("profile_picture_url", newUrl) }
                    Toast.makeText(this@MainActivity, getString(R.string.profile_pic_updated), Toast.LENGTH_SHORT).show()
                    fetchUserGroups(isInitialLoad = false)
                    updateMeTabUI()
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.upload_failed_with_msg, response.body()?.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }

            override fun onFailure(call: Call<ProfilePictureResponse>, t: Throwable) {
                progressIndicator?.visibility = View.GONE
                if (isFinishing || isDestroyed) return
                Toast.makeText(this@MainActivity, getString(R.string.error_network_msg, t.message ?: ""), Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun removeProfilePicture() {
        ApiClient.instance.removeProfilePicture(userId = userId).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                if (isFinishing || isDestroyed) return
                if (response.isSuccessful && response.body()?.status == "success") {
                    SecurePrefs.get(this@MainActivity).edit { remove("profile_picture_url") }
                    Toast.makeText(this@MainActivity, getString(R.string.profile_pic_removed), Toast.LENGTH_SHORT).show()
                    fetchUserGroups(isInitialLoad = false)
                    updateMeTabUI()
                } else Toast.makeText(this@MainActivity, getString(R.string.remove_failed_with_msg, response.body()?.message ?: ""), Toast.LENGTH_LONG).show()
            }
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) { if (isFinishing || isDestroyed) return; Toast.makeText(this@MainActivity, getString(R.string.error_network_msg, t.message ?: ""), Toast.LENGTH_LONG).show() }
        })
    }

    private fun getFileFromUri(uri: Uri): File? {
        return try {
            val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
            val extension = if (mimeType == "image/gif") ".gif" else ".jpg"
            val file = File(cacheDir, "temp_profile_pic_${System.currentTimeMillis()}$extension")
            val copied = contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                true
            } ?: false
            if (copied) file else null
        } catch (e: Exception) { null }
    }

    private fun clearDownloadedFilesCache() {
        lifecycleScope.launch {
            val deletedFilesCount = withContext(Dispatchers.IO) {
                try {
                    var count = 0
                    // Cartella interna privata
                    val internalDir = File(filesDir, "CryptionChat_Files")
                    if (internalDir.exists() && internalDir.isDirectory) {
                        internalDir.listFiles()?.forEach { file -> if (PanicHelper.secureDelete(file)) count++ }
                    }
                    // Cartella esterna legacy per pulizia residui
                    val legacyDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "CryptionChat_Files") }
                    if (legacyDir != null && legacyDir.exists() && legacyDir.isDirectory) {
                        legacyDir.listFiles()?.forEach { file -> if (PanicHelper.secureDelete(file)) count++ }
                    }
                    count
                } catch (e: Exception) { -1 }
            }
            if (!isFinishing && !isDestroyed) {
                withContext(Dispatchers.Main) {
                    when {
                        deletedFilesCount > 0 -> Toast.makeText(this@MainActivity, getString(R.string.cache_cleared_count, deletedFilesCount), Toast.LENGTH_LONG).show()
                        deletedFilesCount == 0 -> Toast.makeText(this@MainActivity, getString(R.string.cache_already_empty), Toast.LENGTH_SHORT).show()
                        else -> Toast.makeText(this@MainActivity, getString(R.string.cache_clear_error), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun logoutUser() {
        SessionLifecycleHelper.clearAllLocalSessionAndDatabase(this)
        val intent = Intent(this, AuthActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
        startActivity(intent)
        finish()
    }

    private fun performAppReset() {
        ServiceScheduler.stopServiceAndWatchdog(this)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notificationChannels.forEach { channel: NotificationChannel -> notificationManager.deleteNotificationChannel(channel.id) }
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        activityManager?.clearApplicationUserData()
    }

    private fun deleteUserAccount() {
        ApiClient.instance.deleteAccount(userId = userId).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                if (isFinishing || isDestroyed) return
                if (response.isSuccessful && response.body()?.status == "success") {
                    Toast.makeText(this@MainActivity, getString(R.string.account_deleted_success), Toast.LENGTH_LONG).show()
                    logoutUser()
                } else Toast.makeText(this@MainActivity, getString(R.string.error_prefix, response.body()?.message ?: ""), Toast.LENGTH_LONG).show()
            }
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) { if (isFinishing || isDestroyed) return; Toast.makeText(this@MainActivity, getString(R.string.error_network_msg, t.message ?: ""), Toast.LENGTH_LONG).show() }
        })
    }

    private fun showImageDialog(group: Group) {
        if (isFinishing || isDestroyed) return
        val imageUrl = if (group.isPrivateChat) group.profilePictureUrl else group.groupPictureUrl
        val placeholder = if (group.isPrivateChat) R.drawable.ic_person else  R.drawable.ic_group
        if (imageUrl.isNullOrBlank()) return

        val dialog = android.app.Dialog(this)

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_image_viewer, null)
        dialog.setContentView(view)
        val imageView = view.findViewById<com.github.chrisbanes.photoview.PhotoView>(R.id.full_screen_image)
        val closeButton = view.findViewById<ImageButton>(R.id.close_button)
        Glide.with(this).load(ApiClient.BASE_URL + imageUrl).placeholder(placeholder).into(imageView)
        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
    }

    private fun showGroupOptionsDialog(group: Group) {
        if (isFinishing || isDestroyed) return
        if (group.isPrivateChat) showPrivateChatOptionsDialog(group) else showPublicGroupOptionsDialog(group)
    }

    private fun showPublicGroupOptionsDialog(group: Group) {
        if (isFinishing || isDestroyed) return
        val options = mutableListOf<String>()
        val prefs = SecurePrefs.get(this)
        val pinnedGroupIds = prefs.getStringSet("pinned_group_ids", emptySet()) ?: emptySet()
        val isPinned = group.id.toString() in pinnedGroupIds
        val optPin = getString(R.string.group_opt_pin)
        val optUnpin = getString(R.string.group_opt_unpin)
        val optOneTime = getString(R.string.group_opt_create_one_time_invite)
        val optPermCode = getString(R.string.group_opt_share_perm_code)
        val optLeave = getString(R.string.group_opt_leave)
        val optDelete = getString(R.string.group_opt_delete)
        options.add(if (isPinned) optUnpin else optPin)
        if (group.isAdmin) {
            options.add(optOneTime)
            options.add(optPermCode)
        }
        options.add(optLeave)
        if (group.isAdmin) options.add(optDelete)
        MaterialAlertDialogBuilder(this).setTitle(group.groupName).setItems(options.toTypedArray()) { dialog, which ->
            when (options[which]) {
                optPin -> pinGroup(group.id, true)
                optUnpin -> pinGroup(group.id, false)
                optOneTime -> createAndShareInviteLink(group)
                optPermCode -> sharePermanentCode(group)
                optLeave -> showLeaveGroupConfirmationDialog(group)
                optDelete -> showDeleteGroupConfirmationDialog(group)
            }
            dialog.dismiss()
        }.show()
    }

    private fun showPrivateChatOptionsDialog(group: Group) {
        if (isFinishing || isDestroyed) return
        val options = mutableListOf<String>()
        val prefs = SecurePrefs.get(this)
        val pinnedGroupIds = prefs.getStringSet("pinned_group_ids", emptySet()) ?: emptySet()
        val isPinned = group.id.toString() in pinnedGroupIds
        val optPin = getString(R.string.group_opt_pin)
        val optUnpin = getString(R.string.group_opt_unpin)
        val optDeleteChat = getString(R.string.chat_opt_delete)
        options.add(if (isPinned) optUnpin else optPin)
        options.add(optDeleteChat)
        val otherUserName = run {
            val parts = group.groupName.split("&").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                parts.firstOrNull { !it.equals(username.trim(), ignoreCase = true) } ?: parts.first()
            } else {
                group.groupName.replace(username, "").replace("&", "").trim()
            }
        }
        MaterialAlertDialogBuilder(this).setTitle(otherUserName).setItems(options.toTypedArray()) { dialog, which ->
            when (options[which]) {
                optPin -> pinGroup(group.id, true)
                optUnpin -> pinGroup(group.id, false)
                optDeleteChat -> showDeletePrivateChatConfirmationDialog(group)
            }
            dialog.dismiss()
        }.show()
    }

    private fun showDeletePrivateChatConfirmationDialog(group: Group) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.chat_opt_delete))
            .setMessage(getString(R.string.delete_chat_msg))
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(getString(R.string.confirm_yes_delete)) { _, _ -> deleteGroup(group.id) }
            .setNegativeButton(getString(R.string.call_dialog_btn_cancel), null).show()
    }

    private fun showLeaveGroupConfirmationDialog(group: Group) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.group_opt_leave)).setMessage(getString(R.string.leave_group_msg, group.groupName))
            .setPositiveButton(getString(R.string.confirm_yes_leave)) { _, _ -> leaveGroup(group.id) }
            .setNegativeButton(getString(R.string.call_dialog_btn_cancel), null).show()
    }

    private fun showDeleteGroupConfirmationDialog(group: Group) {
        if (isFinishing || isDestroyed) return
        val input = EditText(this)
        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val margin = (24 * resources.displayMetrics.density).toInt()
        params.leftMargin = margin; params.rightMargin = margin
        input.layoutParams = params
        input.hint = getString(R.string.confirm_type_yes_hint)
        container.addView(input)
        val dialog = MaterialAlertDialogBuilder(this).setTitle(getString(R.string.group_opt_delete))
            .setMessage(getString(R.string.delete_group_msg, group.groupName))
            .setView(container).setPositiveButton(getString(R.string.action_delete_short), null).setNegativeButton(getString(R.string.call_dialog_btn_cancel), null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = input.text.toString().trim().lowercase()
            if (text == "si" || text == "sì" || text == "yes") { deleteGroup(group.id); dialog.dismiss() }
            else input.error = getString(R.string.confirm_type_yes_error)
        }
    }

    private fun pinGroup(groupId: Int, shouldPin: Boolean) {
        val prefs = SecurePrefs.get(this)
        val pinnedGroupIds = prefs.getStringSet("pinned_group_ids", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (shouldPin) pinnedGroupIds.add(groupId.toString()) else pinnedGroupIds.remove(groupId.toString())
        prefs.edit { putStringSet("pinned_group_ids", pinnedGroupIds) }
        fetchUserGroups(isInitialLoad = false)
    }

    private fun createAndShareInviteLink(group: Group) {
        ApiClient.instance.createInviteLink(userId = userId, groupId = group.id).enqueue(object : Callback<CreateInviteResponse> {
            override fun onResponse(call: Call<CreateInviteResponse>, response: Response<CreateInviteResponse>) {
                if (isFinishing || isDestroyed) return
                val inviteResponse = response.body()
                if (response.isSuccessful && inviteResponse?.status == "success") {
                    val inviteCode = inviteResponse.data?.inviteCode
                    if (inviteCode != null) {
                        val link = "https://your-server.example.com/CryptionChat/join.php?code=$inviteCode"
                        val shareText = getString(R.string.invite_share_one_time_msg, group.groupName, link)
                        showQrCodeDialog(group.groupName, link, shareText)
                    } else Toast.makeText(this@MainActivity, getString(R.string.invite_code_missing), Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this@MainActivity, getString(R.string.error_prefix, inviteResponse?.message ?: ""), Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(call: Call<CreateInviteResponse>, t: Throwable) { if (isFinishing || isDestroyed) return; Toast.makeText(this@MainActivity, getString(R.string.error_network_msg, t.message ?: ""), Toast.LENGTH_SHORT).show() }
        })
    }

    private fun sharePermanentCode(group: Group) {
        if (group.groupCode.isNullOrEmpty()) { Toast.makeText(this, getString(R.string.group_code_unavailable), Toast.LENGTH_SHORT).show(); return }
        val link = "https://your-server.example.com/CryptionChat/join.php?code=${group.groupCode}"
        val shareText = getString(R.string.invite_share_perm_msg, group.groupName, group.groupCode, link)
        showQrCodeDialog(group.groupName, link, shareText)
    }

    private fun showQrCodeDialog(groupName: String, link: String, shareMessage: String) {
        if (isFinishing || isDestroyed) return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_qr_invite, null)
        val qrImageView = dialogView.findViewById<ImageView>(R.id.qr_image_view)
        val btnShare = dialogView.findViewById<Button>(R.id.btn_share_link)
        val btnClose = dialogView.findViewById<Button>(R.id.btn_close)
        val titleView = dialogView.findViewById<TextView>(R.id.qr_title)
        titleView.text = getString(R.string.invite_title_fmt, groupName)
        lifecycleScope.launch(Dispatchers.Default) {
            val bitmap = QrCodeHelper.generateQrBitmap(link, 512, 512)
            withContext(Dispatchers.Main) { if (bitmap != null) qrImageView.setImageBitmap(bitmap) else Toast.makeText(this@MainActivity, getString(R.string.qr_gen_error), Toast.LENGTH_SHORT).show() }
        }
        val dialog = MaterialAlertDialogBuilder(this).setView(dialogView).setCancelable(true).create()
        btnShare.setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, getString(R.string.invite_share_subject)); putExtra(Intent.EXTRA_TEXT, shareMessage) }
            startActivity(Intent.createChooser(intent, getString(R.string.invite_share_chooser)))
        }
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun leaveGroup(groupId: Int) {
        ApiClient.instance.leaveGroup(userId = userId, groupId = groupId).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                val baseResponse = response.body()
                if (response.isSuccessful && baseResponse?.status == "success") { Toast.makeText(this@MainActivity, getString(R.string.group_left_toast), Toast.LENGTH_SHORT).show(); fetchUserGroups(isInitialLoad = true) }
                else Toast.makeText(this@MainActivity, getString(R.string.error_prefix, baseResponse?.message ?: ""), Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) { Toast.makeText(this@MainActivity, getString(R.string.error_network_msg, t.message ?: ""), Toast.LENGTH_SHORT).show() }
        })
    }

    private fun deleteGroup(groupId: Int) {
        ApiClient.instance.deleteGroup(userId = userId, groupId = groupId).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                val baseResponse = response.body()
                if (response.isSuccessful && baseResponse?.status == "success") { Toast.makeText(this@MainActivity, getString(R.string.group_deleted_toast), Toast.LENGTH_SHORT).show(); fetchUserGroups(isInitialLoad = true) }
                else Toast.makeText(this@MainActivity, getString(R.string.error_prefix, baseResponse?.message ?: ""), Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) { Toast.makeText(this@MainActivity, getString(R.string.error_network_msg, t.message ?: ""), Toast.LENGTH_SHORT).show() }
        })
    }

    private fun showOledThemeDialog() {
        if (isFinishing || isDestroyed) return

        val prefs = SecurePrefs.get(this)
        val isOledEnabled = prefs.getBoolean("oled_theme", false)

        val title = if (isOledEnabled) getString(R.string.oled_disable_title) else getString(R.string.oled_enable_title)
        val message = getString(R.string.oled_dialog_msg)

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(if (isOledEnabled) getString(R.string.action_disable) else getString(R.string.action_enable)) { _, _ ->
                prefs.edit().putBoolean("oled_theme", !isOledEnabled).apply()
                Toast.makeText(this, getString(R.string.theme_updated_toast), Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton(getString(R.string.call_dialog_btn_cancel), null)
            .show()
    }

    private fun showShizukuGuideDialog() {
        if (isFinishing || isDestroyed) return

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_shizuku_guide, null)

        val textPart1 = dialogView.findViewById<TextView>(R.id.text_part_1)
        val textPart2 = dialogView.findViewById<TextView>(R.id.text_part_2)
        val btnCopyAdb = dialogView.findViewById<ImageButton>(R.id.btn_copy_adb)
        val btnUnderstand = dialogView.findViewById<MaterialButton>(R.id.btn_understand)
        val btnDownload = dialogView.findViewById<MaterialButton>(R.id.btn_download_shizuku)
        val btnOfficialGuide = dialogView.findViewById<MaterialButton>(R.id.btn_official_guide)

        textPart1.text = Html.fromHtml(getString(R.string.shizuku_guide_part1_html), Html.FROM_HTML_MODE_COMPACT)
        textPart2.text = Html.fromHtml(getString(R.string.shizuku_guide_part2_html), Html.FROM_HTML_MODE_COMPACT)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        val adbCommand = "adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh"
        btnCopyAdb.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText(getString(R.string.shizuku_adb_clip_label), adbCommand)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.adb_command_copied), Toast.LENGTH_SHORT).show()
        }

        btnUnderstand.setOnClickListener {
            dialog.dismiss()
        }

        btnDownload.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases"))
            startActivity(intent)
        }

        btnOfficialGuide.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"))
            startActivity(intent)
        }

        dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
        dialog.show()
    }

    private fun showRemoteKillHelpDialog() {
        if (isFinishing || isDestroyed) return

        val message = getString(R.string.remote_kill_help_html)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.remote_kill_help_title))
            .setMessage(android.text.Html.fromHtml(message, android.text.Html.FROM_HTML_MODE_COMPACT))
            .setPositiveButton(getString(R.string.action_got_it), null)
            .show()
    }

    private fun updateChatsBadgeCount(count: Int) {
        val badge = binding.bottomNavigation.getOrCreateBadge(R.id.nav_chats)
        if (count > 0) {
            badge.isVisible = true
            badge.number = count
            badge.backgroundColor = ContextCompat.getColor(this, R.color.error_light)
            badge.badgeTextColor = Color.WHITE
        } else {
            badge.isVisible = false
        }
    }

    private fun syncMissingKeys(groups: List<Group>) {
        val prefs = SecurePrefs.get(this)

        val missingKeyGroups = groups.filter {
            val isV2Payload = it.lastMessage?.startsWith(SenderKeyHelper.SK_PREFIX) == true ||
                    it.lastMessage?.startsWith(DoubleRatchetHelper.DR_PREFIX) == true
            it.e2eeVersion < 2 &&
                    !isV2Payload &&
                    prefs.getString("key_${it.id}", null) == null &&
                    !it.lastMessage.isNullOrBlank()
        }

        if (missingKeyGroups.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            for (group in missingKeyGroups) {
                try {
                    val response = ApiClient.instance.getMessages(
                        groupId = group.id,
                        userId = userId,
                        cacheBuster = System.currentTimeMillis()
                    ).execute()

                    if (response.isSuccessful && response.body()?.status == "success") {
                        val data = response.body()?.data
                        if (data != null) {
                            val editor = prefs.edit()
                                .putInt("e2ee_version_${group.id}", data.e2eeVersion)
                                .putInt("membership_epoch_${group.id}", data.membershipEpoch)
                            val hasKeys = !data.encryptionKey.isNullOrEmpty() && !data.encryptionIv.isNullOrEmpty()
                            if (hasKeys) {
                                editor.putString("key_${group.id}", data.encryptionKey)
                                    .putString("iv_${group.id}", data.encryptionIv)
                            }
                            editor.apply()

                            if (hasKeys) {
                                withContext(Dispatchers.Main) {
                                    groupAdapter.notifyDataSetChanged()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Errore sync chiavi per gruppo ${group.id}", e)
                }
            }
        }
    }
}
