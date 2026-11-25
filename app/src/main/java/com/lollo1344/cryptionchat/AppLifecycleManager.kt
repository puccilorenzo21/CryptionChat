package com.lollo1344.cryptionchat

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AppLifecycleManager : Application.ActivityLifecycleCallbacks {

    private const val TAG = "AppLifecycleManager"
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startedActivities = 0
    private lateinit var appContext: Context
    @Volatile
    private var currentResumedActivityRef: WeakReference<Activity>? = null

    val isAppInForeground: Boolean
        get() = startedActivities > 0

    fun getCurrentResumedActivity(): Activity? = currentResumedActivityRef?.get()

    var isAppUnlocked: Boolean = false

    @Volatile
    var tempBypassLock: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private var backgroundTransitionRunnable: Runnable? = null

    private const val OFFLINE_NOTIFY_DELAY_MS = 1000L
    private const val ONLINE_HEARTBEAT_INTERVAL_MS = 35_000L
    @Volatile private var lastSentStatus: String? = null
    @Volatile private var lastSentHide: Boolean? = null
    @Volatile private var lastSentAtMs: Long = 0L

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!::appContext.isInitialized || startedActivities <= 0) return
            val prefs = SecurePrefs.get(appContext)
            if (!SessionLifecycleHelper.isOnlineStatusHidden(prefs)) {
                setUserStatus(status = "online", hideOnline = false, ignorePrivacyCheck = true)
                handler.postDelayed(this, ONLINE_HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun initialize(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
        appContext = application.applicationContext
    }

    fun syncOnlineStatusNow(context: Context? = null, force: Boolean = false) {
        if (context != null && !::appContext.isInitialized) {
            appContext = context.applicationContext
        }
        if (!::appContext.isInitialized) return
        val prefs = SecurePrefs.get(appContext)
        val hideOnline = SessionLifecycleHelper.isOnlineStatusHidden(prefs)
        val targetStatus = if (!hideOnline && startedActivities > 0) "online" else "offline"
        val now = System.currentTimeMillis()

        handler.removeCallbacks(heartbeatRunnable)
        if (targetStatus == "online") {
            handler.postDelayed(heartbeatRunnable, ONLINE_HEARTBEAT_INTERVAL_MS)
        }
        if (!force && lastSentStatus == targetStatus && lastSentHide == hideOnline && (now - lastSentAtMs) < 3_000L) {
            return
        }
        lastSentStatus = targetStatus
        lastSentHide = hideOnline
        lastSentAtMs = now
        setUserStatus(status = targetStatus, hideOnline = hideOnline, ignorePrivacyCheck = true)
    }

    private fun setUserStatus(
        status: String,
        hideOnline: Boolean? = null,
        ignorePrivacyCheck: Boolean = false
    ) {
        val prefs = SecurePrefs.get(appContext)
        val isHidden = hideOnline ?: SessionLifecycleHelper.isOnlineStatusHidden(prefs)
        if (!ignorePrivacyCheck && isHidden && status == "online") return
        val userId = prefs.getInt("user_id", -1)
        if (userId == -1) return

        lifecycleScope.launch {
            try {
                ApiClient.instance.setUserStatus(
                    userId = userId,
                    status = if (isHidden) "offline" else status,
                    hideOnlineStatus = isHidden
                ).execute()
            } catch (_: Exception) { }
        }
    }

    override fun onActivityStarted(activity: Activity) {
        backgroundTransitionRunnable?.let {
            handler.removeCallbacks(it)
            backgroundTransitionRunnable = null
        }

        tempBypassLock = false

        val wasZero = startedActivities == 0
        startedActivities++
        if (wasZero) {
            if (::appContext.isInitialized) {
                AutoUpdateManager.getInstance(appContext).onAppEnteredForeground()
            }
            syncOnlineStatusNow(appContext)
            SecurePrefs.get(appContext).edit()
                .putLong("last_active_timestamp", System.currentTimeMillis())
                .apply()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (startedActivities > 0) startedActivities--

        if (startedActivities == 0) {
            if (activity.isChangingConfigurations) return

            handler.removeCallbacks(heartbeatRunnable)

            // BLOCCA IMMEDIATAMENTE LO STATO SBLOCCATO
            if (!tempBypassLock) {
                isAppUnlocked = false
                Log.d(TAG, "onActivityStopped: App bloccata istantaneamente.")
            }

            // Notifica l'offline al server con un leggero ritardo (per gestire i cambi rapidi di activity)
            backgroundTransitionRunnable?.let { handler.removeCallbacks(it) }
            val runnable = Runnable {
                if (startedActivities == 0) {
                    val hideOnline = SessionLifecycleHelper.isOnlineStatusHidden(SecurePrefs.get(appContext))
                    setUserStatus(status = "offline", hideOnline = hideOnline, ignorePrivacyCheck = true)
                    tempBypassLock = false
                }
                backgroundTransitionRunnable = null
            }
            backgroundTransitionRunnable = runnable
            handler.postDelayed(runnable, OFFLINE_NOTIFY_DELAY_MS)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {
        currentResumedActivityRef = WeakReference(activity)
        if (::appContext.isInitialized && !activity.isFinishing) {
            val mgr = AutoUpdateManager.getInstance(appContext)
            if (mgr.isEligiblePromptActivity(activity)) {
                mgr.onActivityReady(activity)
            }
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (currentResumedActivityRef?.get() === activity) {
            currentResumedActivityRef = null
        }
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}