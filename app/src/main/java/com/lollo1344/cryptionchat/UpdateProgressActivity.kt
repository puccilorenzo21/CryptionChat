package com.lollo1344.cryptionchat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lollo1344.cryptionchat.databinding.ActivityUpdateProgressBinding
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class UpdateProgressActivity : BaseActivity() {

    private lateinit var binding: ActivityUpdateProgressBinding
    private var downloadUrl: String? = null
    private var downloadJob: Job? = null

    // Client HTTP con proxy SOCKS5/Tor globale ereditato da ApiClient
    private val downloadClient by lazy {
        ApiClient.okHttpClient.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private const val TAG = "UpdateProgressActivity"
        private const val FINAL_APK_NAME = "cryptionchat_update.apk"
        private const val TEMP_FILE_NAME = "update_downloading.tmp"
    }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, getString(R.string.update_perm_granted), Toast.LENGTH_SHORT).show()
                installApk(this)
            } else {
                Toast.makeText(this, getString(R.string.update_perm_denied), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isBlocked || isFinishing) return
        binding = ActivityUpdateProgressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Blocco tasto indietro durante il download per evitare interruzioni accidentali
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@UpdateProgressActivity, getString(R.string.update_in_progress_wait), Toast.LENGTH_SHORT).show()
            }
        })

        downloadUrl = intent.getStringExtra("DOWNLOAD_URL")

        if (downloadUrl.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.update_invalid_url), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Se il download è già in corso (es. rotazione schermo), non riavviarlo
        if (downloadJob?.isActive != true) {
            startDownload(downloadUrl!!)
        }
    }

    private fun startDownload(url: String) {
        binding.updateIcon.setImageResource(R.drawable.ic_file_download)
        binding.updateTitleText.text = getString(R.string.update_title)
        binding.updateStatusText.text = getString(R.string.update_wait_message)
        binding.progressContainer.visibility = View.VISIBLE
        binding.installButton.visibility = View.GONE
        binding.retryButton.visibility = View.GONE
        binding.updateProgressBar.isIndeterminate = true
        binding.progressStatsText.text = getString(R.string.update_connecting_server)

        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val finalApkFile = File(downloadsDir, FINAL_APK_NAME)
        val tempFile = File(downloadsDir, TEMP_FILE_NAME)

        // Pulizia preliminare
        if (finalApkFile.exists()) finalApkFile.delete()
        if (tempFile.exists()) tempFile.delete()

        // Avvio download in coroutine isolata sul thread di I/O
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            var downloadedBytes = 0L
            var totalBytes = -1L
            var success = false
            var attempts = 0
            val maxAttempts = 3
            var lastException: Exception? = null

            while (attempts < maxAttempts && !success && isActive) {
                attempts++
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("Connection", "close")
                        .header("Cache-Control", "no-cache") // FIX: Evita cache lato rete del download APK
                        .build()

                    val response = downloadClient.newCall(request).execute()

                    if (!response.isSuccessful) throw IOException(getString(R.string.update_server_http_error, response.code))

                    val body = response.body ?: throw IOException(getString(R.string.update_empty_body))
                    totalBytes = body.contentLength()
                    downloadedBytes = 0

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead = input.read(buffer)
                            var lastUpdate = System.currentTimeMillis()

                            while (bytesRead >= 0 && isActive) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 200 || downloadedBytes == totalBytes) {
                                    lastUpdate = now
                                    val d = downloadedBytes
                                    val t = totalBytes
                                    withContext(Dispatchers.Main) { updateProgressUI(d, t) }
                                }
                                bytesRead = input.read(buffer)
                            }
                        }
                    }

                    if (totalBytes > 0 && downloadedBytes == totalBytes) {
                        val renamed = tempFile.renameTo(finalApkFile)
                        if (renamed) {
                            success = true
                        } else {
                            throw IOException(getString(R.string.update_rename_failed))
                        }
                    } else {
                        throw IOException(getString(R.string.update_download_interrupted, downloadedBytes, totalBytes))
                    }

                } catch (e: Exception) {
                    lastException = e
                    Log.e("UpdateProgress", "Tentativo $attempts fallito: ${e.message}")
                    if (tempFile.exists()) tempFile.delete()

                    if (attempts >= maxAttempts) {
                        BugReporter.sendAutoReport(
                            context = this@UpdateProgressActivity,
                            errorTag = "DOWNLOAD_APK_FAILED",
                            message = "Fallito download APK dopo $maxAttempts tentativi. URL: $url | Scaricati: $downloadedBytes / $totalBytes",
                            throwable = lastException
                        )

                        withContext(Dispatchers.Main) { updateUiForFailed(e.message ?: getString(R.string.update_unstable_connection)) }
                        return@launch
                    }
                    delay(1000)
                }
            }

            // SE SCARICATO CON SUCCESSO
            if (success && isActive) {
                withContext(Dispatchers.Main) {

                    // FIX: Salviamo la notifica della Beta SOLO ORA, a download effettivamente concluso e non cancellato
                    val betaUpdateId = intent.getLongExtra("BETA_UPDATE_ID", 0L)
                    if (betaUpdateId > 0L) {
                        SecurePrefs.get(this@UpdateProgressActivity).edit().putLong("last_beta_update_id", betaUpdateId).apply()
                    }

                    updateUiForCompleted()

                    // Lancia automaticamente il popup di installazione
                    checkPermissionAndInstall()
                }
            }
        }
    }

    private fun updateProgressUI(downloadedBytes: Long, totalBytes: Long) {
        if (totalBytes > 0) {
            val progress = (downloadedBytes * 100 / totalBytes).toInt()
            binding.updateProgressBar.isIndeterminate = false
            binding.updateProgressBar.progress = progress
            val downloadedMb = String.format(Locale.getDefault(), "%.1f", downloadedBytes / 1048576.0)
            val totalMb = String.format(Locale.getDefault(), "%.1f", totalBytes / 1048576.0)
            binding.progressStatsText.text = getString(R.string.update_progress_stats, downloadedMb, totalMb, progress)
        } else {
            binding.updateProgressBar.isIndeterminate = true
            val downloadedMb = String.format(Locale.getDefault(), "%.1f", downloadedBytes / 1048576.0)
            binding.progressStatsText.text = getString(R.string.update_downloaded_mb, downloadedMb)
        }
    }

    private fun updateUiForCompleted() {
        binding.updateIcon.setImageResource(R.drawable.ic_check_circle)
        binding.updateProgressBar.progress = 100
        binding.updateProgressBar.isIndeterminate = false
        binding.updateStatusText.text = getString(R.string.update_completed)
        binding.progressStatsText.visibility = View.GONE

        binding.installButton.visibility = View.VISIBLE
        binding.installButton.text = getString(R.string.update_install_btn)
        binding.installButton.isEnabled = true

        binding.installButton.setOnClickListener {
            checkPermissionAndInstall()
        }
    }

    private fun updateUiForFailed(errorMessage: String) {
        binding.updateIcon.setImageResource(R.drawable.ic_error)
        binding.progressContainer.visibility = View.GONE
        binding.updateStatusText.text = getString(R.string.update_failed_reason, errorMessage)
        binding.retryButton.visibility = View.VISIBLE
        binding.retryButton.setOnClickListener {
            downloadUrl?.let { url -> startDownload(url) }
        }
    }

    private fun checkPermissionAndInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.install_permission_title))
                    .setMessage(getString(R.string.install_permission_message))
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.settings_go_to)) { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            installPermissionLauncher.launch(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            installPermissionLauncher.launch(intent)
                        }
                    }
                    .setNegativeButton(getString(R.string.call_dialog_btn_cancel), null)
                    .show()
                return
            }
        }
        installApk(this)
    }

    private fun verifyApkSignature(context: Context, apkFile: File): Boolean {
        return try {
            val pm = context.packageManager

            // Firme dell'app attualmente installata
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
                packageInfo.signatures?.map { it.toByteArray() }
            }

            if (installedSignatures.isNullOrEmpty()) {
                Log.e(TAG, "Firma dell'app installata non trovata.")
                return false
            }

            // Firme dell'APK scaricato
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
                archiveInfo?.signatures?.map { it.toByteArray() }
            }

            if (archiveSignatures.isNullOrEmpty()) {
                Log.e(TAG, "Firma dell'APK scaricato non trovata o archivio non valido.")
                return false
            }

            // Confronto di uguaglianza delle firme crittografiche
            val isMatched = archiveSignatures.any { archiveSig ->
                installedSignatures.any { installedSig ->
                    archiveSig.contentEquals(installedSig)
                }
            }

            if (!isMatched) {
                Log.e(TAG, "ALLARME SICUREZZA: La firma dell'aggiornamento APK non corrisponde all'app installata!")
            }
            isMatched
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante la verifica della firma APK", e)
            false
        }
    }

    private fun installApk(context: Context) {
        val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FINAL_APK_NAME)
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Toast.makeText(context, context.getString(R.string.update_apk_missing_toast), Toast.LENGTH_LONG).show()
            updateUiForFailed(context.getString(R.string.update_apk_missing))
            return
        }

        // Verifica crittografica anti-manomissione della firma APK
        if (!verifyApkSignature(context, apkFile)) {
            PanicHelper.secureDelete(apkFile)
            Toast.makeText(context, context.getString(R.string.update_sig_error_toast), Toast.LENGTH_LONG).show()
            updateUiForFailed(context.getString(R.string.update_sig_error_status))
            return
        }

        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.update_install_start_failed), Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadJob?.cancel()
    }
}