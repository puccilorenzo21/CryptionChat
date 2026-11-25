package com.lollo1344.cryptionchat

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Html
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import retrofit2.Call as RetrofitCall
import retrofit2.Callback as RetrofitCallback
import retrofit2.Response as RetrofitResponse
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AutoUpdateManager private constructor(context: Context) {

    fun interface UpdateProgressListener {
        fun onUpdateDownloadStateChanged(
            isVisible: Boolean,
            isIndeterminate: Boolean,
            progressPercent: Int,
            versionName: String,
            isBeta: Boolean,
            isReadyToInstall: Boolean,
            statusLabel: String
        )
    }

    companion object {
        private const val TAG = "AutoUpdateManager"

        const val UPDATE_FILE_NAME = "cryptionchat_update.apk"
        const val UPDATE_TMP_FILE_NAME = "update_downloading.tmp"

        const val NOTIFICATION_CHANNEL_ID = "auto_update_download_channel"
        const val NOTIFICATION_ID_UPDATE_DOWNLOAD = 4040

        const val PREF_LAST_KNOWN_INSTALLED_VC = "pref_last_known_installed_vc"
        const val PREF_LAST_KNOWN_INSTALLED_VN = "pref_last_known_installed_vn"
        const val PREF_DOWNLOADED_APK_VC = "downloaded_apk_version_code"
        const val PREF_DOWNLOADED_APK_VN = "downloaded_apk_version_name"
        const val PREF_DOWNLOADED_APK_UPDATE_ID = "downloaded_apk_update_id"
        const val PREF_DOWNLOADED_APK_IS_BETA = "downloaded_apk_is_beta"
        const val PREF_DOWNLOADED_APK_IS_MANDATORY = "downloaded_apk_is_mandatory"
        const val PREF_DOWNLOADED_APK_CHANGELOG = "downloaded_apk_changelog"
        const val PREF_LAST_BG_UPDATE_CHECK_MS = "last_bg_update_check_ms"
        const val PREF_LAST_STABLE_UPDATE_ID = "last_stable_update_id"
        const val PREF_LAST_BETA_UPDATE_ID = "last_beta_update_id"

        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_EMIT_INTERVAL_MS = 250L

        @Volatile
        private var instance: AutoUpdateManager? = null

        fun getInstance(context: Context): AutoUpdateManager {
            return instance ?: synchronized(this) {
                instance ?: AutoUpdateManager(context.applicationContext).also { instance = it }
            }
        }

        fun getAppVersionCode(context: Context): Long {
            return try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode.toLong()
                }
            } catch (_: Exception) {
                0L
            }
        }
    }

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isDownloading = AtomicBoolean(false)

    // Eredita proxy Tor/SOCKS5 globale da ApiClient mantenendo HTTP/1.1 e timeout estesi per APK
    private val httpClient by lazy {
        ApiClient.okHttpClient.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile private var currentDownloadCall: Call? = null
    @Volatile private var promptedInCurrentForegroundSession = false
    @Volatile private var waitingForInstallPermission = false

    @Volatile private var currentUpdateVersionName = ""
    @Volatile private var currentUpdateChangelog = ""
    @Volatile private var currentUpdateIsBeta = false
    @Volatile private var currentUpdateIsMandatory = false
    @Volatile private var currentProgressPercent = -1
    @Volatile private var currentIsIndeterminate = false
    @Volatile private var currentStatusLabel = ""

    private val progressListeners = CopyOnWriteArrayList<UpdateProgressListener>()

    private var mandatoryDialogRef: WeakReference<AlertDialog>? = null
    private var mandatoryDialogActivityRef: WeakReference<Activity>? = null

    @Volatile private var cachedApkMetaKey: String? = null
    @Volatile private var cachedApkArchiveInfo: PackageInfo? = null
    @Volatile private var cachedCleanupResult = false
    @Volatile private var lastCleanupCheckMs = 0L
    @Volatile private var hasPendingLocalApkFiles = true
    @Volatile private var lastMandatorySyncMs = 0L
    @Volatile private var lastReadyActivityRef: WeakReference<Activity>? = null
    @Volatile private var lastReadyCheckMs = 0L

    // Cache immutabile di processo per evitare ripetute IPC Binder su PackageManager e chiamate FUSE su MediaProvider
    private val cachedInstalledVersionCode: Long by lazy { getAppVersionCode(appContext) }
    private val cachedInstalledVersionName: String by lazy {
        try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
    private val cachedInstalledLastUpdateTime: Long by lazy {
        try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime
        } catch (_: Exception) {
            0L
        }
    }
    private val cachedDownloadDir: File? by lazy {
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (dir != null && !dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    private fun getPrefs() = SecurePrefs.get(appContext)

    fun refreshNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                appContext.getString(R.string.update_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = appContext.getString(R.string.update_notif_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun getDownloadDir(): File? {
        val dir = cachedDownloadDir ?: appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (dir != null && !dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun addProgressListener(listener: UpdateProgressListener?) {
        if (listener == null) return
        if (!progressListeners.contains(listener)) {
            progressListeners.add(listener)
        }
        emitStateToListener(listener)
    }

    fun removeProgressListener(listener: UpdateProgressListener?) {
        if (listener == null) return
        progressListeners.remove(listener)
    }

    private fun notifyProgressListeners() {
        for (listener in progressListeners) {
            emitStateToListener(listener)
        }
    }

    private fun emitStateToListener(listener: UpdateProgressListener) {
        val downloading = isDownloading.get()
        val ready = !downloading && isReadyApkAvailable()
        val visible = downloading || ready
        val indeterminate = downloading && currentIsIndeterminate
        val progress = if (ready) 100 else if (downloading) maxOf(0, currentProgressPercent) else 0
        val vn = getPendingVersionName()
        val cleanVer = cleanVersion(vn)
        val beta = isPendingBeta()
        val mandatory = isPendingMandatory()
        val label = when {
            ready -> {
                if (mandatory) appContext.getString(R.string.update_pill_ready_mandatory_fmt, cleanVer)
                else appContext.getString(R.string.update_pill_ready_fmt, cleanVer)
            }
            downloading -> {
                if (currentStatusLabel.isNotEmpty()) {
                    currentStatusLabel
                } else if (currentProgressPercent >= 0) {
                    appContext.getString(
                        if (mandatory) R.string.update_pill_downloading_mandatory_pct_fmt else R.string.update_pill_downloading_pct_fmt,
                        cleanVer,
                        currentProgressPercent
                    )
                } else {
                    appContext.getString(
                        if (mandatory) R.string.update_pill_connecting_mandatory_fmt else R.string.update_pill_connecting_fmt,
                        cleanVer
                    )
                }
            }
            else -> ""
        }

        mainHandler.post {
            listener.onUpdateDownloadStateChanged(
                visible, indeterminate, progress, vn, beta, ready, label
            )
        }
    }

    fun getApkFile(): File = File(getDownloadDir(), UPDATE_FILE_NAME)

    fun getTmpApkFile(): File = File(getDownloadDir(), UPDATE_TMP_FILE_NAME)

    private fun getCachedPackageArchiveInfo(apkFile: File?): PackageInfo? {
        if (apkFile == null || !apkFile.exists() || apkFile.length() == 0L) {
            cachedApkMetaKey = null
            cachedApkArchiveInfo = null
            return null
        }
        val key = "${apkFile.absolutePath}:${apkFile.length()}:${apkFile.lastModified()}"
        if (key == cachedApkMetaKey && cachedApkArchiveInfo != null) {
            return cachedApkArchiveInfo
        }
        return try {
            val info = appContext.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            cachedApkMetaKey = key
            cachedApkArchiveInfo = info
            info
        } catch (_: Exception) {
            null
        }
    }

    fun getPendingVersionName(): String {
        if (currentUpdateVersionName.isNotEmpty()) return currentUpdateVersionName
        return getPrefs().getString(PREF_DOWNLOADED_APK_VN, "").orEmpty()
    }

    fun getPendingChangelog(): String {
        if (currentUpdateChangelog.isNotEmpty()) return currentUpdateChangelog
        return getPrefs().getString(PREF_DOWNLOADED_APK_CHANGELOG, "").orEmpty()
    }

    fun isPendingBeta(): Boolean {
        if (isDownloading.get()) return currentUpdateIsBeta
        return getPrefs().getBoolean(PREF_DOWNLOADED_APK_IS_BETA, currentUpdateIsBeta)
    }

    fun isPendingMandatory(): Boolean {
        if (isDownloading.get()) return currentUpdateIsMandatory
        return getPrefs().getBoolean(PREF_DOWNLOADED_APK_IS_MANDATORY, currentUpdateIsMandatory)
    }

    /**
     * Chiamato quando l'app torna in foreground dal background:
     * azzera il flag di sessione per mostrare subito il pop-up di installazione di sistema se pronto.
     */
    fun onAppEnteredForeground() {
        promptedInCurrentForegroundSession = false
    }

    /**
     * Chiamato quando l'utente attiva o disattiva il programma Beta:
     * pulisce i metadati pendenti ed esegue subito un controllo sul nuovo canale.
     */
    fun onBetaStatusChanged() {
        currentDownloadCall?.cancel()
        isDownloading.set(false)
        val prefs = getPrefs()
        val apkFile = getApkFile()
        deleteApkAndClearMetadata(apkFile, syncLastSeenId = false)
        notifyProgressListeners()
        prefs.edit().remove(PREF_LAST_BG_UPDATE_CHECK_MS).apply()
        checkAndDownloadInBackground(forceCheck = true, isInteractive = false)
    }

    /**
     * Rimuove l'APK scaricato se l'app è stata appena aggiornata o se l'APK risulta obsoleto/corrotto.
     */
    @Synchronized
    fun cleanupIfUpdatedOrObsolete(): Boolean {
        val nowMs = System.currentTimeMillis()
        // Se non risultano APK scaricati in locale e abbiamo già verificato negli ultimi 10 minuti,
        // evita chiamate FUSE/MediaProvider e decifrature SecurePrefs ripetitive in background.
        if (!hasPendingLocalApkFiles && !isDownloading.get() && (nowMs - lastCleanupCheckMs) < 10 * 60 * 1000L) {
            return cachedCleanupResult
        }
        val apkFile = getApkFile()
        val tmpFile = getTmpApkFile()
        val anyLocalFileExists = apkFile.exists() || tmpFile.exists()
        if (!anyLocalFileExists && (nowMs - lastCleanupCheckMs) < 10 * 60 * 1000L && lastCleanupCheckMs > 0L) {
            hasPendingLocalApkFiles = false
            return cachedCleanupResult
        }
        lastCleanupCheckMs = nowMs

        val prefs = getPrefs()
        val installedVC = cachedInstalledVersionCode
        val installedVN = cachedInstalledVersionName
        val lastInstallUpdateTime = cachedInstalledLastUpdateTime

        val prevInstalledVC = prefs.getLong(PREF_LAST_KNOWN_INSTALLED_VC, 0L)
        val prevInstalledVN = prefs.getString(PREF_LAST_KNOWN_INSTALLED_VN, "").orEmpty()

        if (!isDownloading.get() && tmpFile.exists()) {
            tmpFile.delete()
        }

        val versionChangedSinceLastLaunch = prevInstalledVC > 0L &&
            (installedVC != prevInstalledVC || installedVN != prevInstalledVN)

        var deleted = false

        if (apkFile.exists()) {
            val archiveInfo = getCachedPackageArchiveInfo(apkFile)

            if (archiveInfo == null || appContext.packageName != archiveInfo.packageName) {
                Log.i(TAG, "APK scaricato non valido o corrotto: rimozione immediata.")
                deleteApkAndClearMetadata(apkFile, syncLastSeenId = false)
                deleted = true
            } else {
                val apkVC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    archiveInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    archiveInfo.versionCode.toLong()
                }
                val apkVN = archiveInfo.versionName ?: prefs.getString(PREF_DOWNLOADED_APK_VN, "").orEmpty()
                val apkUpdateId = prefs.getLong(PREF_DOWNLOADED_APK_UPDATE_ID, 0L)
                val apkIsBeta = prefs.getBoolean(
                    PREF_DOWNLOADED_APK_IS_BETA,
                    apkVN.contains("beta", ignoreCase = true)
                )

                val lastSeenId = if (apkIsBeta) {
                    prefs.getLong(PREF_LAST_BETA_UPDATE_ID, 0L)
                } else {
                    prefs.getLong(PREF_LAST_STABLE_UPDATE_ID, 0L)
                }

                val installedAfterApkDownload = lastInstallUpdateTime > 0L &&
                    apkFile.lastModified() > 0L &&
                    lastInstallUpdateTime >= apkFile.lastModified()

                val shouldDelete = installedAfterApkDownload || AutoUpdateEvaluator.shouldDeleteDownloadedApk(
                    installedVC = installedVC,
                    installedVN = installedVN,
                    lastSeenUpdateId = lastSeenId,
                    apkVC = apkVC,
                    apkVN = apkVN,
                    apkUpdateId = apkUpdateId,
                    previousInstalledVC = prevInstalledVC,
                    previousInstalledVN = prevInstalledVN
                )

                if (shouldDelete) {
                    Log.i(TAG, "Aggiornamento già applicato o APK obsoleto ($installedVN). Rimozione ${apkFile.name}")
                    deleteApkAndClearMetadata(apkFile, syncLastSeenId = true)
                    deleted = true
                }
            }
        } else if (versionChangedSinceLastLaunch) {
            clearDownloadedMetadata(syncLastSeenId = true)
            cancelDownloadNotification()
        }

        if (installedVC != prevInstalledVC || installedVN != prevInstalledVN) {
            prefs.edit()
                .putLong(PREF_LAST_KNOWN_INSTALLED_VC, installedVC)
                .putString(PREF_LAST_KNOWN_INSTALLED_VN, installedVN)
                .apply()
        }

        hasPendingLocalApkFiles = apkFile.exists() || tmpFile.exists()
        cachedCleanupResult = deleted
        if (deleted) {
            notifyProgressListeners()
        }
        return deleted
    }

    /**
     * Pulizia immediata alla ricezione di ACTION_MY_PACKAGE_REPLACED.
     */
    @Synchronized
    fun onPackageReplaced() {
        val apkFile = getApkFile()
        val tmpFile = getTmpApkFile()
        if (tmpFile.exists()) {
            tmpFile.delete()
        }
        if (apkFile.exists()) {
            Log.i(TAG, "ACTION_MY_PACKAGE_REPLACED: rimozione immediata di ${apkFile.absolutePath}")
            deleteApkAndClearMetadata(apkFile, syncLastSeenId = true)
        } else {
            clearDownloadedMetadata(syncLastSeenId = true)
            cancelDownloadNotification()
        }
        val installedVC = cachedInstalledVersionCode
        val installedVN = cachedInstalledVersionName
        hasPendingLocalApkFiles = false
        lastCleanupCheckMs = 0L
        getPrefs().edit()
            .putLong(PREF_LAST_KNOWN_INSTALLED_VC, installedVC)
            .putString(PREF_LAST_KNOWN_INSTALLED_VN, installedVN)
            .apply()
        notifyProgressListeners()
    }

    private fun deleteApkAndClearMetadata(apkFile: File?, syncLastSeenId: Boolean) {
        if (apkFile != null && apkFile.exists()) {
            apkFile.delete()
        }
        cachedApkMetaKey = null
        cachedApkArchiveInfo = null
        val tmpFile = getTmpApkFile()
        if (!isDownloading.get() && tmpFile.exists()) {
            tmpFile.delete()
        }
        clearDownloadedMetadata(syncLastSeenId)
        cancelDownloadNotification()
    }

    private fun clearDownloadedMetadata(syncLastSeenId: Boolean) {
        val prefs = getPrefs()
        val editor = prefs.edit()
        if (syncLastSeenId) {
            val downloadedId = prefs.getLong(PREF_DOWNLOADED_APK_UPDATE_ID, 0L)
            val isBeta = prefs.getBoolean(PREF_DOWNLOADED_APK_IS_BETA, false)
            if (downloadedId > 0L) {
                if (isBeta) {
                    editor.putLong(PREF_LAST_BETA_UPDATE_ID, downloadedId)
                } else {
                    editor.putLong(PREF_LAST_STABLE_UPDATE_ID, downloadedId)
                }
            }
        }
        if (!isDownloading.get()) {
            currentUpdateVersionName = ""
            currentUpdateChangelog = ""
            currentUpdateIsMandatory = false
            currentIsIndeterminate = false
            currentProgressPercent = -1
            currentStatusLabel = ""
        }
        editor.remove(PREF_DOWNLOADED_APK_VC)
            .remove(PREF_DOWNLOADED_APK_VN)
            .remove(PREF_DOWNLOADED_APK_UPDATE_ID)
            .remove(PREF_DOWNLOADED_APK_IS_BETA)
            .remove(PREF_DOWNLOADED_APK_IS_MANDATORY)
            .remove(PREF_DOWNLOADED_APK_CHANGELOG)
            .apply()
        dismissMandatoryDialog()
    }

    fun dismissMandatoryDialog() {
        mainHandler.post {
            try {
                val dlg = mandatoryDialogRef?.get()
                if (dlg != null && dlg.isShowing) {
                    dlg.dismiss()
                }
            } catch (_: Exception) {
            }
            mandatoryDialogRef = null
            mandatoryDialogActivityRef = null
        }
    }

    /**
     * Chiamato quando un'Activity principale diventa visibile all'utente (onResume).
     */
    fun onActivityReady(activity: Activity?) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return

        val nowReady = System.currentTimeMillis()
        val prevActivity = lastReadyActivityRef?.get()
        if (prevActivity === activity && (nowReady - lastReadyCheckMs < 1500L) && !waitingForInstallPermission) {
            return
        }
        lastReadyActivityRef = WeakReference(activity)
        lastReadyCheckMs = nowReady

        cleanupIfUpdatedOrObsolete()

        if (waitingForInstallPermission) {
            waitingForInstallPermission = false
            promptedInCurrentForegroundSession = false
        }

        if (isReadyApkAvailable()) {
            if (isPendingMandatory()) {
                enforceMandatoryInstallBlock(activity)
            }
            promptSystemInstallIfReady(activity, forcePrompt = false)
            syncRemoteMandatoryStateWhenApkReady()
        } else {
            dismissMandatoryDialog()
            checkAndDownloadInBackground(forceCheck = true, isInteractive = false)
        }
    }

    fun isReadyApkAvailable(): Boolean {
        if (!hasPendingLocalApkFiles && !isDownloading.get()) {
            return false
        }
        val apkFile = getApkFile()
        if (!apkFile.exists() || apkFile.length() == 0L) {
            if (!isDownloading.get() && !getTmpApkFile().exists()) {
                hasPendingLocalApkFiles = false
            }
            return false
        }

        val archiveInfo = getCachedPackageArchiveInfo(apkFile)
        if (archiveInfo == null || appContext.packageName != archiveInfo.packageName) {
            apkFile.delete()
            cachedApkMetaKey = null
            cachedApkArchiveInfo = null
            return false
        }

        val apkVC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode.toLong()
        }
        val prefs = getPrefs()
        val apkVN = archiveInfo.versionName ?: prefs.getString(PREF_DOWNLOADED_APK_VN, "").orEmpty()
        val apkUpdateId = prefs.getLong(PREF_DOWNLOADED_APK_UPDATE_ID, 0L)
        val isBeta = prefs.getBoolean(
            PREF_DOWNLOADED_APK_IS_BETA,
            apkVN.contains("beta", ignoreCase = true)
        )

        val installedVC = cachedInstalledVersionCode
        val installedVN = cachedInstalledVersionName
        val lastSeenId = if (isBeta) {
            prefs.getLong(PREF_LAST_BETA_UPDATE_ID, 0L)
        } else {
            prefs.getLong(PREF_LAST_STABLE_UPDATE_ID, 0L)
        }

        return AutoUpdateEvaluator.isDownloadedApkReadyForInstall(
            installedVC = installedVC,
            installedVN = installedVN,
            lastSeenUpdateId = lastSeenId,
            apkVC = apkVC,
            apkVN = apkVN,
            apkUpdateId = apkUpdateId,
            isBeta = isBeta
        )
    }

    /**
     * Se un aggiornamento è segnato come obbligatorio (isForced o sotto minimumVersionCode) ed è scaricato al 100%,
     * blocca l'utilizzo finché l'utente non installa tramite l'installer di sistema.
     */
    fun enforceMandatoryInstallBlock(activity: Activity?) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return

        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            if (!isReadyApkAvailable() || !isPendingMandatory()) {
                dismissMandatoryDialog()
                return@post
            }

            val existingDialog = mandatoryDialogRef?.get()
            val existingActivity = mandatoryDialogActivityRef?.get()
            if (existingDialog != null && existingDialog.isShowing && existingActivity === activity) {
                return@post
            }
            if (existingDialog != null && existingDialog.isShowing) {
                try {
                    existingDialog.dismiss()
                } catch (_: Exception) {
                }
            }

            val versionName = getPendingVersionName()
            val changelog = getPendingChangelog()

            val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null)
            val titleText = view.findViewById<TextView>(R.id.update_title)
            val versionText = view.findViewById<TextView>(R.id.update_version)
            val versionCard = view.findViewById<View>(R.id.version_badge_card)
            val changelogText = view.findViewById<TextView>(R.id.update_changelog)
            val btnPositive = view.findViewById<MaterialButton>(R.id.btn_update_positive)
            val btnNegative = view.findViewById<MaterialButton>(R.id.btn_update_negative)

            titleText.text = activity.getString(R.string.update_dialog_required_title)
            if (versionName.isNotBlank()) {
                versionText.text = activity.getString(R.string.update_dialog_version, versionName)
                versionCard?.visibility = View.VISIBLE
            } else {
                versionCard?.visibility = View.GONE
            }

            val prefix = activity.getString(R.string.update_mandatory_ready_prefix)
            val rawNotes = if (changelog.isNotBlank()) "$prefix$changelog" else prefix
            changelogText.text = Html.fromHtml(formatChangelogForHtml(rawNotes), Html.FROM_HTML_MODE_COMPACT)

            btnPositive.text = activity.getString(R.string.update_install_now_btn)
            btnNegative.visibility = View.GONE

            val dialog = MaterialAlertDialogBuilder(activity)
                .setView(view)
                .setCancelable(false)
                .create()

            dialog.setCanceledOnTouchOutside(false)
            dialog.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }

            btnPositive.setOnClickListener {
                triggerPackageInstallOrPermission(activity, getApkFile())
            }

            try {
                dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
                dialog.show()
                mandatoryDialogRef = WeakReference(dialog)
                mandatoryDialogActivityRef = WeakReference(activity)
            } catch (e: Exception) {
                Log.w(TAG, "Impossibile mostrare dialogo obbligatorio: ${e.message}")
            }
        }
    }

    /**
     * Apre subito il pop-up di installazione nativo Android se l'APK scaricato è pronto su disco.
     */
    fun promptSystemInstallIfReady(activity: Activity?, forcePrompt: Boolean) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return

        cleanupIfUpdatedOrObsolete()

        if (!isReadyApkAvailable()) {
            dismissMandatoryDialog()
            return
        }

        if (isPendingMandatory()) {
            enforceMandatoryInstallBlock(activity)
        }

        if (promptedInCurrentForegroundSession && !forcePrompt) {
            return
        }

        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            val apkFile = getApkFile()
            if (!apkFile.exists() || apkFile.length() == 0L) return@post

            promptedInCurrentForegroundSession = true
            triggerPackageInstallOrPermission(activity, apkFile)
        }
    }

    private fun triggerPackageInstallOrPermission(activity: Activity, apkFile: File) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!apkFile.exists() || apkFile.length() == 0L) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                waitingForInstallPermission = true
                promptedInCurrentForegroundSession = true
                try {
                    val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${activity.packageName}"))
                    activity.startActivity(permIntent)
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.update_allow_install_toast),
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Errore apertura impostazioni installazione: ${e.message}")
                }
                return
            }
        }

        launchSystemPackageInstaller(activity, apkFile)
    }

    fun launchSystemPackageInstaller(activity: Activity, apkFile: File) {
        if (!verifyApkSignature(activity, apkFile)) {
            PanicHelper.secureDelete(apkFile)
            clearDownloadedMetadata(syncLastSeenId = false)
            cancelDownloadNotification()
            notifyProgressListeners()
            Toast.makeText(
                activity,
                activity.getString(R.string.update_sig_error_toast),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
            Log.i(TAG, "Package Installer di sistema avviato con successo.")
        } catch (ex: Exception) {
            Log.e(TAG, "Errore avvio pop-up di installazione di sistema: ${ex.message}", ex)
            Toast.makeText(
                activity,
                activity.getString(R.string.update_install_start_failed),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Se un APK è già pronto su disco, sincronizza dal server l'eventuale stato obbligatorio remoto.
     */
    private fun syncRemoteMandatoryStateWhenApkReady() {
        if (isDownloading.get() || !isReadyApkAvailable()) return
        val nowMs = System.currentTimeMillis()
        if (lastMandatorySyncMs > 0L && (nowMs - lastMandatorySyncMs) < 15 * 60 * 1000L) {
            return
        }
        lastMandatorySyncMs = nowMs
        val isBetaChannel = getPrefs().getBoolean("is_beta_tester", false)
        val cacheBuster = nowMs
        val installedVC = cachedInstalledVersionCode

        if (isBetaChannel) {
            ApiClient.instance.checkForBetaUpdates(cacheBuster).enqueue(object : RetrofitCallback<BetaUpdateResponse> {
                override fun onResponse(
                    call: RetrofitCall<BetaUpdateResponse>,
                    response: RetrofitResponse<BetaUpdateResponse>
                ) {
                    val body = response.body() ?: return
                    val remoteMandatory = AutoUpdateEvaluator.isMandatoryUpdate(
                        body.isForced ?: false,
                        (body.minimumVersionCode ?: 0).toLong(),
                        installedVC
                    ) || (body.isStrict == true)
                    applyRemoteMandatoryFlag(remoteMandatory)
                }

                override fun onFailure(call: RetrofitCall<BetaUpdateResponse>, t: Throwable) {}
            })
        } else {
            ApiClient.instance.checkForUpdates(cacheBuster).enqueue(object : RetrofitCallback<UpdateInfoResponse> {
                override fun onResponse(
                    call: RetrofitCall<UpdateInfoResponse>,
                    response: RetrofitResponse<UpdateInfoResponse>
                ) {
                    val body = response.body() ?: return
                    val remoteMandatory = AutoUpdateEvaluator.isMandatoryUpdate(
                        body.isForced,
                        body.minimumVersionCode.toLong(),
                        installedVC
                    ) || (body.isStrict == true)
                    applyRemoteMandatoryFlag(remoteMandatory)
                }

                override fun onFailure(call: RetrofitCall<UpdateInfoResponse>, t: Throwable) {}
            })
        }
    }

    private fun applyRemoteMandatoryFlag(remoteMandatory: Boolean) {
        val prevMandatory = isPendingMandatory()
        if (remoteMandatory == prevMandatory) return

        currentUpdateIsMandatory = remoteMandatory
        getPrefs().edit().putBoolean(PREF_DOWNLOADED_APK_IS_MANDATORY, remoteMandatory).apply()
        notifyProgressListeners()

        val current = AppLifecycleManager.getCurrentResumedActivity()
        if (remoteMandatory && current != null && !current.isFinishing && !current.isDestroyed) {
            enforceMandatoryInstallBlock(current)
            promptSystemInstallIfReady(current, forcePrompt = true)
        } else if (!remoteMandatory) {
            dismissMandatoryDialog()
        }
    }

    /**
     * Verifica in background se è disponibile un aggiornamento (Beta o Stabile) e avvia il download silenzioso.
     */
    fun checkAndDownloadInBackground(forceCheck: Boolean, isInteractive: Boolean) {
        cleanupIfUpdatedOrObsolete()

        if (isReadyApkAvailable()) {
            val current = AppLifecycleManager.getCurrentResumedActivity()
            if (current != null && !current.isFinishing && !current.isDestroyed) {
                if (isPendingMandatory()) {
                    enforceMandatoryInstallBlock(current)
                }
                promptSystemInstallIfReady(current, forcePrompt = isInteractive)
            }
            syncRemoteMandatoryStateWhenApkReady()
            return
        }

        if (isDownloading.get()) {
            if (isInteractive) {
                showToastOnUi(appContext.getString(R.string.update_already_downloading_bg))
            }
            return
        }

        val now = System.currentTimeMillis()
        val prefs = getPrefs()
        val lastCheck = prefs.getLong(PREF_LAST_BG_UPDATE_CHECK_MS, 0L)
        if (!forceCheck && !isInteractive && !AutoUpdateEvaluator.shouldTriggerBackgroundCheck(
                now, lastCheck, AutoUpdateEvaluator.DEFAULT_BG_CHECK_INTERVAL_MS
            )
        ) {
            return
        }
        prefs.edit().putLong(PREF_LAST_BG_UPDATE_CHECK_MS, now).apply()

        val isBetaChannel = prefs.getBoolean("is_beta_tester", false)
        if (isBetaChannel) {
            checkBetaChannelInBackground(isInteractive)
        } else {
            checkStableChannelInBackground(isInteractive, isBetaChannelFallback = false)
        }
    }

    private fun checkBetaChannelInBackground(isInteractive: Boolean) {
        val cacheBuster = System.currentTimeMillis()
        ApiClient.instance.checkForBetaUpdates(cacheBuster).enqueue(object : RetrofitCallback<BetaUpdateResponse> {
            override fun onResponse(
                call: RetrofitCall<BetaUpdateResponse>,
                response: RetrofitResponse<BetaUpdateResponse>
            ) {
                val betaInfo = response.body()
                if (!response.isSuccessful || betaInfo == null) {
                    checkStableChannelInBackground(isInteractive, isBetaChannelFallback = true)
                    return
                }

                val installedVC = getAppVersionCode(appContext)
                val installedVN = getInstalledVersionName()
                val prefs = getPrefs()
                val lastSeenBetaId = prefs.getLong(PREF_LAST_BETA_UPDATE_ID, 0L)

                val isUpdateAvailable = AutoUpdateEvaluator.isBetaUpdateAvailable(
                    installedVC = installedVC,
                    installedVN = installedVN,
                    remoteVC = betaInfo.versionCode.toLong(),
                    remoteVN = betaInfo.versionName,
                    lastSeenBetaId = lastSeenBetaId,
                    remoteBetaId = betaInfo.updateId
                )

                Log.i(
                    TAG,
                    "Check BETA: installed=$installedVC/$installedVN, remote=${betaInfo.versionCode}/${betaInfo.versionName}, updateId=${betaInfo.updateId}, lastSeen=$lastSeenBetaId, available=$isUpdateAvailable"
                )

                val isMandatory = AutoUpdateEvaluator.isMandatoryUpdate(
                    betaInfo.isForced ?: false,
                    (betaInfo.minimumVersionCode ?: 0).toLong(),
                    installedVC
                ) || (betaInfo.isStrict == true)

                if (!isUpdateAvailable && betaInfo.updateId > 0L && (lastSeenBetaId == 0L || lastSeenBetaId < betaInfo.updateId)) {
                    prefs.edit().putLong(PREF_LAST_BETA_UPDATE_ID, betaInfo.updateId).apply()
                }

                if (isUpdateAvailable) {
                    val downloadUrl = buildApkDownloadUrl(isBeta = true)
                    if (downloadUrl == null) {
                        if (isInteractive) {
                            showToastOnUi(appContext.getString(R.string.update_incompatible_title))
                        }
                        return
                    }
                    Log.i(TAG, "Nuova BETA rilevata (${betaInfo.versionName}, mandatory=$isMandatory). Avvio download in background...")
                    if (isInteractive) {
                        showToastOnUi(appContext.getString(R.string.update_bg_download_started_beta))
                    }
                    downloadApkSilently(
                        url = downloadUrl,
                        remoteVC = betaInfo.versionCode.toLong(),
                        remoteVN = betaInfo.versionName.orEmpty(),
                        remoteChangelog = betaInfo.changelog,
                        remoteUpdateId = betaInfo.updateId,
                        isBeta = true,
                        isMandatory = isMandatory,
                        isInteractive = isInteractive
                    )
                } else {
                    // Come in precedenza: se non c'è una beta più recente, verifica se è uscita una stabile superiore
                    checkStableChannelInBackground(isInteractive, isBetaChannelFallback = true)
                }
            }

            override fun onFailure(call: RetrofitCall<BetaUpdateResponse>, t: Throwable) {
                Log.w(TAG, "Errore rete controllo beta in background: ${t.message}")
                checkStableChannelInBackground(isInteractive, isBetaChannelFallback = true)
            }
        })
    }

    private fun checkStableChannelInBackground(isInteractive: Boolean, isBetaChannelFallback: Boolean) {
        val cacheBuster = System.currentTimeMillis()
        ApiClient.instance.checkForUpdates(cacheBuster).enqueue(object : RetrofitCallback<UpdateInfoResponse> {
            override fun onResponse(
                call: RetrofitCall<UpdateInfoResponse>,
                response: RetrofitResponse<UpdateInfoResponse>
            ) {
                val updateInfo = response.body()
                if (!response.isSuccessful || updateInfo == null) {
                    if (isInteractive) {
                        showToastOnUi(appContext.getString(R.string.update_check_error))
                    }
                    return
                }

                val installedVC = getAppVersionCode(appContext)
                val installedVN = getInstalledVersionName()
                val prefs = getPrefs()
                val lastSeenStableId = prefs.getLong(PREF_LAST_STABLE_UPDATE_ID, 0L)

                val isUpdateAvailable = AutoUpdateEvaluator.isStableUpdateAvailable(
                    installedVC = installedVC,
                    installedVN = installedVN,
                    remoteVC = updateInfo.versionCode.toLong(),
                    remoteVN = updateInfo.versionName,
                    lastSeenUpdateId = lastSeenStableId,
                    remoteUpdateId = 0L,
                    isBetaChannel = isBetaChannelFallback
                )

                Log.i(
                    TAG,
                    "Check STABLE (fallback=$isBetaChannelFallback): installed=$installedVC/$installedVN, remote=${updateInfo.versionCode}/${updateInfo.versionName}, available=$isUpdateAvailable"
                )

                val isMandatory = AutoUpdateEvaluator.isMandatoryUpdate(
                    updateInfo.isForced,
                    updateInfo.minimumVersionCode.toLong(),
                    installedVC
                ) || (updateInfo.isStrict == true)

                if (isUpdateAvailable) {
                    val downloadUrl = buildApkDownloadUrl(isBeta = false)
                    if (downloadUrl == null) {
                        if (isInteractive) {
                            showToastOnUi(appContext.getString(R.string.update_incompatible_title))
                        }
                        return
                    }
                    Log.i(TAG, "Nuova STABILE rilevata (${updateInfo.versionName}, mandatory=$isMandatory). Avvio download in background...")
                    if (isInteractive) {
                        showToastOnUi(appContext.getString(R.string.update_bg_download_started))
                    }
                    downloadApkSilently(
                        url = downloadUrl,
                        remoteVC = updateInfo.versionCode.toLong(),
                        remoteVN = updateInfo.versionName.orEmpty(),
                        remoteChangelog = updateInfo.changelog,
                        remoteUpdateId = 0L,
                        isBeta = false,
                        isMandatory = isMandatory,
                        isInteractive = isInteractive
                    )
                } else if (isInteractive) {
                    showToastOnUi(appContext.getString(R.string.update_already_up_to_date))
                }
            }

            override fun onFailure(call: RetrofitCall<UpdateInfoResponse>, t: Throwable) {
                Log.w(TAG, "Errore rete controllo stabile in background: ${t.message}")
                if (isInteractive) {
                    showToastOnUi(appContext.getString(R.string.update_check_network_error))
                }
            }
        })
    }

    private fun scheduleMandatoryRetryIfNeeded(isMandatory: Boolean) {
        if (!isMandatory) return
        mainHandler.postDelayed({
            if (!isDownloading.get() && !isReadyApkAvailable()) {
                checkAndDownloadInBackground(forceCheck = true, isInteractive = false)
            }
        }, 5000L)
    }

    private fun downloadApkSilently(
        url: String,
        remoteVC: Long,
        remoteVN: String,
        remoteChangelog: String,
        remoteUpdateId: Long,
        isBeta: Boolean,
        isMandatory: Boolean,
        isInteractive: Boolean
    ) {
        if (!isDownloading.compareAndSet(false, true)) return
        hasPendingLocalApkFiles = true
        lastCleanupCheckMs = 0L

        currentUpdateVersionName = remoteVN
        currentUpdateChangelog = remoteChangelog
        currentUpdateIsBeta = isBeta
        currentUpdateIsMandatory = isMandatory
        currentIsIndeterminate = true
        currentProgressPercent = 0
        val cleanRemoteVer = cleanVersion(remoteVN)
        currentStatusLabel = appContext.getString(
            if (isMandatory) R.string.update_pill_connecting_mandatory_fmt else R.string.update_pill_connecting_fmt,
            cleanRemoteVer
        )

        val prefs = getPrefs()
        val tmpFile = getTmpApkFile()
        var existingBytes = 0L
        val prevDownloadedVN = prefs.getString(PREF_DOWNLOADED_APK_VN, "").orEmpty()
        val prevDownloadedVC = prefs.getLong(PREF_DOWNLOADED_APK_VC, 0L)
        if (tmpFile.exists()) {
            if (currentUpdateVersionName == prevDownloadedVN && remoteVC == prevDownloadedVC) {
                existingBytes = tmpFile.length()
            } else {
                tmpFile.delete()
            }
        }

        prefs.edit()
            .putLong(PREF_DOWNLOADED_APK_VC, remoteVC)
            .putString(PREF_DOWNLOADED_APK_VN, currentUpdateVersionName)
            .putString(PREF_DOWNLOADED_APK_CHANGELOG, currentUpdateChangelog)
            .putBoolean(PREF_DOWNLOADED_APK_IS_BETA, isBeta)
            .putBoolean(PREF_DOWNLOADED_APK_IS_MANDATORY, isMandatory)
            .apply()

        updateDownloadNotification(
            versionName = currentUpdateVersionName,
            isBeta = isBeta,
            progressPercent = 0,
            indeterminate = true,
            detailText = appContext.getString(R.string.update_connecting_server),
            completed = false
        )
        notifyProgressListeners()

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Connection", "close")
            .header("Cache-Control", "no-cache")
            .header("Accept-Encoding", "identity")

        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }
        val startBytes = existingBytes

        val call = httpClient.newCall(requestBuilder.build())
        currentDownloadCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                isDownloading.set(false)
                currentIsIndeterminate = false
                currentProgressPercent = -1
                currentStatusLabel = ""
                cancelDownloadNotification()
                notifyProgressListeners()
                Log.w(TAG, "Download silenzioso fallito (${if (tmpFile.exists()) tmpFile.length() else 0} bytes): ${e.message}")
                if (isInteractive) {
                    showToastOnUi(appContext.getString(R.string.update_failed_reason, e.message ?: ""))
                }
                scheduleMandatoryRetryIfNeeded(isMandatory)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (resp.code == 416) {
                        if (tmpFile.exists()) tmpFile.delete()
                        isDownloading.set(false)
                        currentIsIndeterminate = false
                        currentProgressPercent = -1
                        currentStatusLabel = ""
                        cancelDownloadNotification()
                        notifyProgressListeners()
                        scheduleMandatoryRetryIfNeeded(isMandatory)
                        return
                    }

                    val body = resp.body
                    if (!resp.isSuccessful || body == null) {
                        isDownloading.set(false)
                        currentIsIndeterminate = false
                        currentProgressPercent = -1
                        currentStatusLabel = ""
                        cancelDownloadNotification()
                        notifyProgressListeners()
                        Log.w(TAG, "Errore HTTP durante download silenzioso APK: ${resp.code}")
                        if (isInteractive) {
                            showToastOnUi(appContext.getString(R.string.update_server_http_error, resp.code))
                        }
                        scheduleMandatoryRetryIfNeeded(isMandatory)
                        return
                    }

                    val isRangeAppend = (resp.code == 206 && startBytes > 0L)
                    val responseContentLength = body.contentLength()
                    val totalBytes = if (isRangeAppend) {
                        if (responseContentLength > 0L) startBytes + responseContentLength else -1L
                    } else {
                        responseContentLength
                    }
                    var totalRead = if (isRangeAppend) startBytes else 0L

                    try {
                        BufferedInputStream(body.byteStream(), BUFFER_SIZE).use { inputStream ->
                            FileOutputStream(tmpFile, isRangeAppend).use { fos ->
                                BufferedOutputStream(fos, BUFFER_SIZE).use { outputStream ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    var read: Int
                                    var lastEmitTime = 0L
                                    var lastEmittedPercent = -2
                                    var lastSpeedTime = System.currentTimeMillis()
                                    var bytesSinceLastSpeed = 0L
                                    var currentSpeedMbps = 0.0

                                    while (inputStream.read(buffer).also { read = it } != -1) {
                                        if (call.isCanceled()) throw IOException("Download cancelled")
                                        outputStream.write(buffer, 0, read)
                                        totalRead += read
                                        bytesSinceLastSpeed += read

                                        val now = System.currentTimeMillis()
                                        val speedDelta = now - lastSpeedTime
                                        if (speedDelta >= 400L) {
                                            currentSpeedMbps = (bytesSinceLastSpeed / 1048576.0) / (speedDelta / 1000.0)
                                            lastSpeedTime = now
                                            bytesSinceLastSpeed = 0L
                                        }

                                        val percent = AutoUpdateEvaluator.calculateProgressPercent(totalRead, totalBytes)
                                        if (AutoUpdateEvaluator.shouldEmitProgressUpdate(
                                                percent, lastEmittedPercent, now, lastEmitTime, PROGRESS_EMIT_INTERVAL_MS
                                            )
                                        ) {
                                            lastEmittedPercent = percent
                                            lastEmitTime = now
                                            currentIsIndeterminate = (percent < 0)
                                            currentProgressPercent = maxOf(0, percent)
                                            currentStatusLabel = if (percent >= 0) {
                                                appContext.getString(
                                                    if (isMandatory) R.string.update_pill_downloading_mandatory_pct_fmt else R.string.update_pill_downloading_pct_fmt,
                                                    cleanRemoteVer,
                                                    percent
                                                )
                                            } else {
                                                val readMb = String.format(Locale.getDefault(), "%.1f", totalRead / 1048576.0)
                                                appContext.getString(
                                                    if (isMandatory) R.string.update_pill_downloading_mandatory_mb_fmt else R.string.update_pill_downloading_mb_fmt,
                                                    cleanRemoteVer,
                                                    readMb
                                                )
                                            }
                                            val notifDetail = formatDownloadNotificationText(
                                                percent, totalRead, totalBytes, currentSpeedMbps
                                            )
                                            updateDownloadNotification(
                                                versionName = remoteVN,
                                                isBeta = isBeta,
                                                progressPercent = maxOf(0, percent),
                                                indeterminate = (percent < 0),
                                                detailText = notifDetail,
                                                completed = false
                                            )
                                            notifyProgressListeners()
                                        }
                                    }
                                    outputStream.flush()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        isDownloading.set(false)
                        currentIsIndeterminate = false
                        currentProgressPercent = -1
                        currentStatusLabel = ""
                        cancelDownloadNotification()
                        notifyProgressListeners()
                        Log.w(TAG, "Download silenzioso interrotto a ${if (tmpFile.exists()) tmpFile.length() else 0} bytes: ${e.message}")
                        if (isInteractive) {
                            showToastOnUi(appContext.getString(R.string.update_bg_download_interrupted))
                        }
                        scheduleMandatoryRetryIfNeeded(isMandatory)
                        return
                    }

                    if (totalBytes > 0L && totalRead != totalBytes) {
                        isDownloading.set(false)
                        currentIsIndeterminate = false
                        currentProgressPercent = -1
                        currentStatusLabel = ""
                        if (tmpFile.exists()) tmpFile.delete()
                        cancelDownloadNotification()
                        notifyProgressListeners()
                        scheduleMandatoryRetryIfNeeded(isMandatory)
                        return
                    }

                    try {
                        val pm = appContext.packageManager
                        val archiveInfo = pm.getPackageArchiveInfo(tmpFile.absolutePath, 0)
                        if (archiveInfo == null || appContext.packageName != archiveInfo.packageName) {
                            Log.e(TAG, "File APK scaricato in background non valido o corrotto. Eliminazione.")
                            tmpFile.delete()
                            isDownloading.set(false)
                            currentIsIndeterminate = false
                            currentProgressPercent = -1
                            currentStatusLabel = ""
                            cancelDownloadNotification()
                            notifyProgressListeners()
                            return
                        }

                        if (!verifyApkSignature(appContext, tmpFile)) {
                            Log.e(TAG, "Verifica firma APK fallita dopo download in background. Eliminazione sicura.")
                            PanicHelper.secureDelete(tmpFile)
                            isDownloading.set(false)
                            currentIsIndeterminate = false
                            currentProgressPercent = -1
                            currentStatusLabel = ""
                            cancelDownloadNotification()
                            notifyProgressListeners()
                            if (isInteractive) {
                                showToastOnUi(appContext.getString(R.string.update_sig_error_toast))
                            }
                            return
                        }

                        val actualApkVC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            archiveInfo.longVersionCode
                        } else {
                            @Suppress("DEPRECATION")
                            archiveInfo.versionCode.toLong()
                        }
                        val actualApkVN = archiveInfo.versionName ?: remoteVN

                        val finalApkFile = getApkFile()
                        if (finalApkFile.exists()) finalApkFile.delete()

                        if (!tmpFile.renameTo(finalApkFile)) {
                            Log.e(TAG, "Impossibile rinominare ${tmpFile.name} in ${finalApkFile.name}")
                            tmpFile.delete()
                            isDownloading.set(false)
                            currentIsIndeterminate = false
                            currentProgressPercent = -1
                            currentStatusLabel = ""
                            cancelDownloadNotification()
                            notifyProgressListeners()
                            return
                        }

                        currentUpdateVersionName = actualApkVN
                        currentIsIndeterminate = false
                        currentProgressPercent = 100
                        val cleanActualVer = cleanVersion(actualApkVN)
                        currentStatusLabel = appContext.getString(
                            if (isMandatory) R.string.update_pill_ready_mandatory_fmt else R.string.update_pill_ready_fmt,
                            cleanActualVer
                        )
                        prefs.edit()
                            .putLong(PREF_DOWNLOADED_APK_VC, if (actualApkVC > 0L) actualApkVC else remoteVC)
                            .putString(PREF_DOWNLOADED_APK_VN, actualApkVN)
                            .putString(PREF_DOWNLOADED_APK_CHANGELOG, currentUpdateChangelog)
                            .putLong(PREF_DOWNLOADED_APK_UPDATE_ID, remoteUpdateId)
                            .putBoolean(PREF_DOWNLOADED_APK_IS_BETA, isBeta)
                            .putBoolean(PREF_DOWNLOADED_APK_IS_MANDATORY, isMandatory)
                            .apply()

                        isDownloading.set(false)
                        Log.i(TAG, "Download silenzioso completato (100%): ${finalApkFile.absolutePath} ($actualApkVN, mandatory=$isMandatory)")

                        updateDownloadNotification(
                            versionName = actualApkVN,
                            isBeta = isBeta,
                            progressPercent = 100,
                            indeterminate = false,
                            detailText = appContext.getString(
                                if (isMandatory) R.string.update_notif_ready_mandatory else R.string.update_notif_ready_tap
                            ),
                            completed = true
                        )
                        notifyProgressListeners()

                        // Se una schermata principale dell'app è aperta in primo piano, mostra subito il pop-up di sistema
                        val currentActivity = AppLifecycleManager.getCurrentResumedActivity()
                        if (currentActivity != null && !currentActivity.isFinishing && !currentActivity.isDestroyed &&
                            isEligiblePromptActivity(currentActivity)
                        ) {
                            if (isMandatory) {
                                enforceMandatoryInstallBlock(currentActivity)
                            }
                            promptSystemInstallIfReady(currentActivity, forcePrompt = true)
                        }
                    } catch (e: Exception) {
                        isDownloading.set(false)
                        currentIsIndeterminate = false
                        currentProgressPercent = -1
                        currentStatusLabel = ""
                        if (tmpFile.exists()) tmpFile.delete()
                        cancelDownloadNotification()
                        notifyProgressListeners()
                        Log.e(TAG, "Errore finalizzazione APK in background", e)
                    }
                }
            }
        })
    }

    private fun formatDownloadNotificationText(
        percent: Int,
        bytesRead: Long,
        totalBytes: Long,
        speedMbps: Double
    ): String {
        val speedFormatted = AutoUpdateEvaluator.formatSpeedText(speedMbps)
        val readMb = String.format(Locale.getDefault(), "%.1f", maxOf(0L, bytesRead) / 1048576.0)
        return if (totalBytes > 0L && percent >= 0) {
            val totalMb = String.format(Locale.getDefault(), "%.1f", totalBytes / 1048576.0)
            if (speedFormatted.isNotEmpty()) {
                appContext.getString(R.string.update_notif_progress_speed_fmt, percent, readMb, totalMb, speedFormatted)
            } else {
                appContext.getString(R.string.update_notif_progress_fmt, percent, readMb, totalMb)
            }
        } else {
            if (speedFormatted.isNotEmpty()) {
                appContext.getString(R.string.update_notif_indet_speed_fmt, readMb, speedFormatted)
            } else {
                appContext.getString(R.string.update_notif_indet_fmt, readMb)
            }
        }
    }

    private fun updateDownloadNotification(
        versionName: String,
        isBeta: Boolean,
        progressPercent: Int,
        indeterminate: Boolean,
        detailText: String,
        completed: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        refreshNotificationChannel()
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openAppIntent = Intent(appContext, AuthActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentPendingIntent = PendingIntent.getActivity(appContext, 4041, openAppIntent, flags)

        val cleanVer = cleanVersion(versionName)
        val title = if (completed) {
            if (cleanVer.isNotEmpty()) {
                appContext.getString(R.string.update_notif_title_ready_ver, cleanVer)
            } else {
                appContext.getString(R.string.update_notif_title_ready)
            }
        } else {
            if (isBeta) {
                if (cleanVer.isNotEmpty()) {
                    appContext.getString(R.string.update_notif_title_downloading_beta_ver, cleanVer)
                } else {
                    appContext.getString(R.string.update_notif_title_downloading_beta)
                }
            } else {
                if (cleanVer.isNotEmpty()) {
                    appContext.getString(R.string.update_notif_title_downloading_ver, cleanVer)
                } else {
                    appContext.getString(R.string.update_notif_title_downloading)
                }
            }
        }

        val builder = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(if (completed) R.drawable.ic_check_circle else R.drawable.ic_file_download)
            .setContentTitle(title)
            .setContentText(detailText)
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (completed) {
            builder.setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
        } else {
            builder.setProgress(100, progressPercent.coerceIn(0, 100), indeterminate)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
        }

        try {
            nm.notify(NOTIFICATION_ID_UPDATE_DOWNLOAD, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Impossibile pubblicare notifica download: ${e.message}")
        }
    }

    fun cancelDownloadNotification() {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID_UPDATE_DOWNLOAD)
        } catch (_: Exception) {
        }
    }

    fun verifyApkSignature(context: Context, apkFile: File): Boolean {
        return try {
            val pm = context.packageManager

            val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = packageInfo.signingInfo ?: return false
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.map { it.toByteArray() }
                } else {
                    signingInfo.signingCertificateHistory.map { it.toByteArray() }
                }
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                packageInfo.signatures?.map { it.toByteArray() }
            }

            if (installedSignatures.isNullOrEmpty()) return false

            val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = archiveInfo?.signingInfo ?: return false
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.map { it.toByteArray() }
                } else {
                    signingInfo.signingCertificateHistory.map { it.toByteArray() }
                }
            } else {
                @Suppress("DEPRECATION")
                val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                archiveInfo?.signatures?.map { it.toByteArray() }
            }

            if (archiveSignatures.isNullOrEmpty()) return false

            archiveSignatures.any { archiveSig ->
                installedSignatures.any { installedSig -> archiveSig.contentEquals(installedSig) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore verifica firma APK", e)
            false
        }
    }

    fun isEligiblePromptActivity(activity: Activity): Boolean {
        return activity !is AuthActivity &&
            activity !is LoginActivity &&
            activity !is RegisterActivity &&
            activity !is ForgotPasswordActivity &&
            activity !is ResetPasswordActivity &&
            activity !is VerifyCodeActivity &&
            activity !is BannedActivity &&
            activity !is MaintenanceActivity &&
            activity !is CallActivity &&
            activity !is UpdateProgressActivity &&
            activity !is PortraitCaptureActivity &&
            activity.javaClass.name != "com.yalantis.ucrop.UCropActivity"
    }

    private fun buildApkDownloadUrl(isBeta: Boolean): String? {
        val abi = getDeviceArchitecture() ?: return null
        val cacheBuster = System.currentTimeMillis()
        return if (isBeta) {
            "https://your-server.example.com/CryptionChat/app-$abi-pre_release.apk?t=$cacheBuster"
        } else {
            "https://your-server.example.com/CryptionChat/app-$abi-release.apk?t=$cacheBuster"
        }
    }

    private fun getDeviceArchitecture(): String? {
        val abis = Build.SUPPORTED_ABIS
        if (abis.contains("arm64-v8a")) return "arm64-v8a"
        if (abis.contains("armeabi-v7a") || abis.contains("armeabi")) return "armeabi-v7a"
        return null
    }

    private fun cleanVersion(vn: String?): String {
        val t = vn?.trim().orEmpty()
        return if (t.startsWith("v", ignoreCase = true)) t.substring(1) else t
    }

    private fun formatChangelogForHtml(rawText: String): String {
        return rawText
            .replace(Regex("(?m)^([^•\n].*?)$"), "<h4><b>$1</b></h4>")
            .replace(Regex("(?m)^•\\s?(.*)"), "<li>$1</li>")
            .replace(Regex("(<li>.*?</li>)"), "<ul>$1</ul>")
            .replace("</ul>\n<ul>", "")
            .replace("\n", "<br/>")
    }

    private fun getInstalledVersionName(): String {
        return try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun getInstalledLastUpdateTime(): Long {
        return try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime
        } catch (_: Exception) {
            0L
        }
    }

    private fun showToastOnUi(message: String) {
        mainHandler.post {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
