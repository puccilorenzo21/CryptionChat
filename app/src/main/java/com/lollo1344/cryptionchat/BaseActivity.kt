package com.lollo1344.cryptionchat

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

open class BaseActivity : AppCompatActivity() {

    private val maintenanceHandler = Handler(Looper.getMainLooper())
    private lateinit var maintenanceCheckRunnable: Runnable
    private val MAINTENANCE_CHECK_INTERVAL = 60000L // 60 secondi
    protected var isBlocked = false // Flag generico per bloccare l'esecuzione

    // Riferimento al monitor di rete e al banner globale
    protected lateinit var baseNetworkMonitor: NetworkQualityMonitor
    private var networkBannerView: View? = null

    // Riferimento al dialog per chiuderlo correttamente in onDestroy
    private var rootDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // --- 1. APPLICA TEMA OLED ---
        val prefs = SecurePrefs.get(this)
        if (prefs.getBoolean("oled_theme", false)) {
            setTheme(R.style.Theme_CryptionChat_OLED)
        }

        super.onCreate(savedInstanceState)

        // Inizializza il monitoraggio di rete per la notifica e l'aggiornamento grafico del banner
        baseNetworkMonitor = NetworkQualityMonitor(this) { status ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateGlobalNetworkBanner(status)
                onNetworkStatusChanged(status)
            }
        }

        // --- 1B. CONFIGURA LE TRANSIZIONI DELL'INDIETRO PREDITTIVO (API 34+) ---
        setupPredictiveBackTransitions()

        // Elenco delle Activity "Pubbliche" a cui si può accedere senza essere loggati
        val isPublicActivity = this is AuthActivity ||
                this is LoginActivity ||
                this is RegisterActivity ||
                this is ForgotPasswordActivity ||
                this is ResetPasswordActivity ||
                this is VerifyCodeActivity ||
                this is BannedActivity ||
                this is MaintenanceActivity ||
                this is CallActivity

        // --- FIX VULNERABILITA' ACTIVITY LAUNCHER / ADB BYPASS IN ONCREATE ---
        if (!isPublicActivity) {
            val userId = prefs.getInt("user_id", -1)
            val biometricEnabled = prefs.getBoolean("biometric_auth_enabled", false)

            if (userId != -1 && biometricEnabled && !AppLifecycleManager.isAppUnlocked) {
                Log.w("Security", "Tentativo di bypass rilevato in onCreate. Reindirizzamento al blocco schermo.")
                isBlocked = true
                val intent = Intent(this, AuthActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
                return
            } else if (userId != -1 && !biometricEnabled) {
                AppLifecycleManager.isAppUnlocked = true
            }
        }

        // --- 2. CONTROLLO ROOT ---
        if (!isPublicActivity && RootDetectionHelper.isDeviceRooted(this) && !isBlocked) {
            isBlocked = true
            showRootActionChoiceDialog()
            return
        }

        // --- 3. GESTIONE SICUREZZA SCHERMO (SCREENSHOT) ---
        if (!isBlocked) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
            setupMaintenanceCheck()
        }
    }

    private fun setupPredictiveBackTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Transizione di apertura dell'Activity (da sinistra/destra)
            overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN,
                R.anim.slide_in_right,
                R.anim.slide_out_left
            )
            // Transizione di chiusura interattiva (indietro predittivo con trascinamento del dito)
            overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
        } else {
            // Retrocompatibilità per versioni precedenti ad Android 14
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
    }

    private fun showRootActionChoiceDialog() {
        rootDialog?.dismiss()

        rootDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Dispositivo Non Sicuro Rilevato")
            .setMessage("L'app è in esecuzione su un dispositivo con permessi di root. Per sicurezza, verrai disconnesso. Puoi anche scegliere di disinstallare l'app immediatamente utilizzando i permessi di root.")
            .setCancelable(false)
            .setPositiveButton("Disinstalla (Root)") { _, _ ->
                performCleanupAndForceUninstall()
            }
            .setNegativeButton("Solo Disconnetti") { _, _ ->
                performLogoutAndRedirect()
            }
            .show()
    }

    private fun performCleanupAndForceUninstall() {
        performCleanup()
        RootDetectionHelper.forceUninstall(this)
        finish()
    }

    private fun performLogoutAndRedirect() {
        performCleanup()
        val intent = Intent(this, AuthActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun performCleanup() {
        ServiceScheduler.stopService(this)
        val prefs = SecurePrefs.get(this).edit()
        prefs.clear()
        prefs.apply()
    }

    private fun setupMaintenanceCheck() {
        maintenanceCheckRunnable = Runnable {
            checkForMaintenance()
            maintenanceHandler.postDelayed(maintenanceCheckRunnable, MAINTENANCE_CHECK_INTERVAL)
        }
    }

    private fun checkForMaintenance() {
        if (this is MaintenanceActivity || isFinishing || isDestroyed) return

        val prefs = SecurePrefs.get(this@BaseActivity)
        val userId = prefs.getInt("user_id", -1)
        if (userId == -1) return

        // Inviamo il timestamp corrente come parametro per aggirare la cache del server
        ApiClient.instance.checkForUpdates(System.currentTimeMillis()).enqueue(object : Callback<UpdateInfoResponse> {
            override fun onResponse(call: Call<UpdateInfoResponse>, response: Response<UpdateInfoResponse>) {
                if (isFinishing || isDestroyed) return
                val responseBody = response.body()
                if (response.isSuccessful && responseBody != null) {
                    if (responseBody.maintenanceMode) {
                        Log.w("BaseActivity", "Manutenzione attivata in background. Reindirizzamento...")
                        goToMaintenanceScreen(responseBody.maintenanceMessage)
                    }
                }
            }

            override fun onFailure(call: Call<UpdateInfoResponse>, t: Throwable) {
                Log.w("BaseActivity", "Controllo manutenzione periodico fallito: ${t.message}")
            }
        })
    }

    private fun goToMaintenanceScreen(message: String?) {
        maintenanceHandler.removeCallbacks(maintenanceCheckRunnable)
        val intent = Intent(this, MaintenanceActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("MAINTENANCE_MESSAGE", message)
        }
        startActivity(intent)
        finish()
    }

    private fun updateGlobalNetworkBanner(status: NetworkQualityMonitor.NetworkStatus) {
        val isExcludedActivity = this is CallActivity || this is BannedActivity || this is MaintenanceActivity
        if (isExcludedActivity) return

        val rootLayout = findViewById<ViewGroup>(android.R.id.content) ?: return

        if (status == NetworkQualityMonitor.NetworkStatus.GOOD) {
            networkBannerView?.let { rootLayout.removeView(it) }
            networkBannerView = null
            return
        }

        var banner = networkBannerView
        if (banner == null) {
            banner = LayoutInflater.from(this).inflate(R.layout.layout_global_network_banner, rootLayout, false)
            rootLayout.addView(banner)
            networkBannerView = banner
        }

        val card = banner.findViewById<com.google.android.material.card.MaterialCardView>(R.id.network_card)
        val textView = banner.findViewById<TextView>(R.id.network_text)

        val params = card.layoutParams as FrameLayout.LayoutParams
        params.gravity = android.view.Gravity.TOP
        params.leftMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics).toInt()
        params.rightMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics).toInt()
        card.layoutParams = params

        // Calcoliamo la posizione esatta della fine della Toolbar rispetto alla finestra attiva
        rootLayout.post {
            if (isFinishing || isDestroyed || networkBannerView == null) return@post
            val appBar = findViewById<View>(R.id.appBarLayout) ?: findViewById<View>(R.id.toolbar)

            val updatedParams = card.layoutParams as FrameLayout.LayoutParams
            if (appBar != null && appBar.visibility == View.VISIBLE && appBar.height > 0) {
                val appBarLocation = IntArray(2)
                appBar.getLocationInWindow(appBarLocation)

                val contentLocation = IntArray(2)
                rootLayout.getLocationInWindow(contentLocation)

                // Calcolo millimetrico della differenza tra la fine della barra e l'inizio del layout di contenuto
                val relativeTop = (appBarLocation[1] + appBar.height) - contentLocation[1]
                updatedParams.topMargin = relativeTop + TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
            } else {
                // Se non c'è barra (es. schermate di autenticazione), posiziona in alto con un margine di base
                updatedParams.topMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
            }
            card.layoutParams = updatedParams
        }

        if (status == NetworkQualityMonitor.NetworkStatus.DISCONNECTED) {
            card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.error_container_dark))
            textView.text = getString(R.string.network_status_disconnected)
            textView.setTextColor(ContextCompat.getColor(this, R.color.on_error_container_dark))
            textView.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_signal_off, 0, 0, 0)
            textView.compoundDrawableTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.on_error_container_dark))
        } else if (status == NetworkQualityMonitor.NetworkStatus.POOR) {
            card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.warning_container_dark))
            textView.text = getString(R.string.network_status_poor)
            textView.setTextColor(ContextCompat.getColor(this, R.color.on_warning_container_dark))
            textView.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_signal_poor, 0, 0, 0)
            textView.compoundDrawableTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.on_warning_container_dark))
        }
    }

    protected val isNetworkMonitorInitialized: Boolean
        get() = ::baseNetworkMonitor.isInitialized

    protected fun getBaseNetworkStatus(): NetworkQualityMonitor.NetworkStatus {
        return if (::baseNetworkMonitor.isInitialized) {
            baseNetworkMonitor.getCurrentStatus()
        } else {
            NetworkQualityMonitor.NetworkStatus.DISCONNECTED
        }
    }

    protected open fun onNetworkStatusChanged(status: NetworkQualityMonitor.NetworkStatus) {
        // Metodo vuoto inteso per essere sovrascritto opzionalmente dalle sottoclassi
    }

    override fun onResume() {
        super.onResume()

        val prefs = SecurePrefs.get(this)
        val isPublicActivity = this is AuthActivity ||
                this is LoginActivity ||
                this is RegisterActivity ||
                this is ForgotPasswordActivity ||
                this is ResetPasswordActivity ||
                this is VerifyCodeActivity ||
                this is BannedActivity ||
                this is MaintenanceActivity ||
                this is CallActivity

        // --- VERIFICA SBLOCCO AD OGNI RITORNO IN PRIMO PIANO (FOREGROUND) ---
        if (!isPublicActivity) {
            val userId = prefs.getInt("user_id", -1)
            val biometricEnabled = prefs.getBoolean("biometric_auth_enabled", false)

            if (userId != -1 && biometricEnabled && !AppLifecycleManager.isAppUnlocked) {
                Log.w("Security", "Applicazione ritornata in primo piano ma bloccata. Reindirizzamento ad AuthActivity.")
                isBlocked = true
                val intent = Intent(this, AuthActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
                return
            }
        }

        if (!isBlocked) {
            if (::baseNetworkMonitor.isInitialized) {
                baseNetworkMonitor.register()
                updateGlobalNetworkBanner(baseNetworkMonitor.getCurrentStatus())
            }

            // Attiva il monitoraggio globale degli screenshot per tutte le schermate sensibili (non pubbliche)
            if (!isPublicActivity) {
                ScreenshotDestroyer.startWatching(this) {
                    if (this is ChatActivity) {
                        this.sendScreenshotWarningMessage()
                    }
                }
            }

            if (::maintenanceCheckRunnable.isInitialized) {
                maintenanceHandler.post(maintenanceCheckRunnable)
            }
        }
    }

    // --- OVERRIDES PER BYPASS AUTOMATICO DEL BLOCCO ---
    override fun startActivity(intent: Intent?) {
        AppLifecycleManager.tempBypassLock = true
        super.startActivity(intent)
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        AppLifecycleManager.tempBypassLock = true
        super.startActivity(intent, options)
    }

    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        AppLifecycleManager.tempBypassLock = true
        super.startActivityForResult(intent, requestCode)
    }

    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        AppLifecycleManager.tempBypassLock = true
        super.startActivityForResult(intent, requestCode, options)
    }

    @Deprecated("Deprecated in Java")
    override fun startIntentSenderForResult(
        intent: android.content.IntentSender,
        requestCode: Int,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int,
        options: Bundle?
    ) {
        AppLifecycleManager.tempBypassLock = true
        super.startIntentSenderForResult(
            intent,
            requestCode,
            fillInIntent,
            flagsMask,
            flagsValues,
            extraFlags,
            options
        )
    }

    override fun onPause() {
        super.onPause()
        if (::baseNetworkMonitor.isInitialized) {
            baseNetworkMonitor.unregister()
        }

        // Arresta il monitoraggio degli screenshot ad ogni sospensione dell'Activity
        ScreenshotDestroyer.stopWatching(this)

        if (::maintenanceCheckRunnable.isInitialized) {
            maintenanceHandler.removeCallbacks(maintenanceCheckRunnable)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::baseNetworkMonitor.isInitialized) {
            baseNetworkMonitor.unregister()
        }
        rootDialog?.dismiss()
        rootDialog = null
        maintenanceHandler.removeCallbacksAndMessages(null)
    }
}