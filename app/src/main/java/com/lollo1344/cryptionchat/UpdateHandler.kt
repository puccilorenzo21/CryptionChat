package com.lollo1344.cryptionchat

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.text.Html
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class UpdateHandler(
    private val activity: Activity,
    private val installPermissionLauncher: ActivityResultLauncher<Intent>
) {

    companion object {
        // Traccia le versioni posticipate nella sessione corrente.
        // Si azzera solo quando l'applicazione viene chiusa/killata.
        private val sessionPostponedVersions = HashSet<Int>()
    }

    fun checkForUpdates() {
        val prefs = SecurePrefs.get(activity)
        val isBetaTester = prefs.getBoolean("is_beta_tester", false)

        if (isBetaTester) {
            checkBetaUpdates()
        } else {
            checkStableUpdates()
        }
    }

    fun forceManualUpdate() {
        val prefs = SecurePrefs.get(activity)
        val isBetaTester = prefs.getBoolean("is_beta_tester", false)

        if (isBetaTester) {
            val cacheBuster = System.currentTimeMillis()
            ApiClient.instance.checkForBetaUpdates(cacheBuster).enqueue(object : Callback<BetaUpdateResponse> {
                override fun onResponse(call: Call<BetaUpdateResponse>, response: Response<BetaUpdateResponse>) {
                    val updateId = response.body()?.updateId ?: 0L
                    startUpdateProgressActivity(isBeta = true, updateId = updateId)
                }

                override fun onFailure(call: Call<BetaUpdateResponse>, t: Throwable) {
                    startUpdateProgressActivity(isBeta = true)
                }
            })
        } else {
            startUpdateProgressActivity(isBeta = false)
        }
    }

    private fun checkBetaUpdates() {
        val cacheBuster = System.currentTimeMillis()
        ApiClient.instance.checkForBetaUpdates(cacheBuster).enqueue(object : Callback<BetaUpdateResponse> {
            override fun onResponse(call: Call<BetaUpdateResponse>, response: Response<BetaUpdateResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val betaInfo = response.body()!!
                    val prefs = SecurePrefs.get(activity)
                    val lastSeenUpdateId = prefs.getLong("last_beta_update_id", 0L)

                    try {
                        val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                        val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            packageInfo.longVersionCode
                        } else {
                            packageInfo.versionCode.toLong()
                        }

                        if (betaInfo.updateId > lastSeenUpdateId || betaInfo.versionCode > currentVersionCode) {
                            showBetaUpdateDialog(betaInfo, currentVersionCode)
                        } else {
                            checkStableUpdates()
                        }
                    } catch (e: Exception) {
                        Log.e("UpdateHandler", "Errore controllo versione", e)
                        checkStableUpdates()
                    }
                } else {
                    checkStableUpdates()
                }
            }

            override fun onFailure(call: Call<BetaUpdateResponse>, t: Throwable) {
                checkStableUpdates()
            }
        })
    }

    private fun showBetaUpdateDialog(betaInfo: BetaUpdateResponse, currentVersionCode: Long) {
        val isBelowMinimum = ((betaInfo.minimumVersionCode ?: 0) > 0 && currentVersionCode < (betaInfo.minimumVersionCode ?: 0)) || (betaInfo.isStrict ?: false)
        val isForced = betaInfo.isForced ?: false

        val title: String
        val prefix: String

        // Il messaggio di versione non supportata compare SOLO se l'app è sotto la versione minima
        if (isBelowMinimum) {
            title = activity.getString(R.string.update_dialog_required_title)
            prefix = activity.getString(R.string.update_dialog_required_message)
        } else {
            // Rimosso il testo introduttivo "Nuova versione di test disponibile..." impostandolo vuoto
            title = activity.getString(R.string.update_dialog_beta_title)
            prefix = ""
        }

        showUpdateDialog(
            versionName = betaInfo.versionName,
            changelog = betaInfo.changelog,
            isStrict = isBelowMinimum,
            isForced = isBelowMinimum || isForced, // Bloccante se sotto il minimo o se forzato dal pannello
            targetVersionCode = betaInfo.versionCode,
            title = title,
            changelogPrefix = prefix,
            isBeta = true,
            updateId = betaInfo.updateId
        )
    }

    private fun checkStableUpdates() {
        val cacheBuster = System.currentTimeMillis()
        ApiClient.instance.checkForUpdates(cacheBuster).enqueue(object : Callback<UpdateInfoResponse> {
            override fun onResponse(call: Call<UpdateInfoResponse>, response: Response<UpdateInfoResponse>) {
                if (!response.isSuccessful || response.body() == null) {
                    return
                }

                val updateInfo = response.body()!!
                try {
                    val packageInfo: PackageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                    val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode
                    } else {
                        packageInfo.versionCode.toLong()
                    }

                    if (updateInfo.versionCode > currentVersionCode) {
                        val isBelowMinimum = (updateInfo.minimumVersionCode > 0 && currentVersionCode < updateInfo.minimumVersionCode) || (updateInfo.isStrict ?: false)
                        val isForced = updateInfo.isForced

                        val title: String
                        val changelogPrefix: String

                        // Il messaggio di versione non supportata compare SOLO se l'app è sotto la versione minima
                        if (isBelowMinimum) {
                            title = activity.getString(R.string.update_dialog_required_title)
                            changelogPrefix = activity.getString(R.string.update_dialog_required_message)
                        } else {
                            title = activity.getString(R.string.update_dialog_title)
                            changelogPrefix = ""
                        }

                        showUpdateDialog(
                            versionName = updateInfo.versionName,
                            changelog = updateInfo.changelog,
                            isStrict = isBelowMinimum,
                            isForced = isBelowMinimum || isForced, // Bloccante se sotto il minimo o se forzato dal pannello
                            targetVersionCode = updateInfo.versionCode,
                            title = title,
                            changelogPrefix = changelogPrefix,
                            isBeta = false,
                            updateId = 0L
                        )
                    }

                } catch (e: Exception) {
                    Log.e("UpdateCheck", "Errore durante il controllo della versione stabile", e)
                }
            }

            override fun onFailure(call: Call<UpdateInfoResponse>, t: Throwable) {
                Log.e("UpdateCheck", "Impossibile verificare aggiornamenti stabili: ${t.message}")
            }
        })
    }

    private fun showUpdateDialog(
        versionName: String?,
        changelog: String,
        isStrict: Boolean,
        isForced: Boolean, // TRUE = Blocco immediato | FALSE = Rimandabile 5 volte
        targetVersionCode: Int,
        title: String,
        changelogPrefix: String,
        isBeta: Boolean,
        updateId: Long
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val prefs = SecurePrefs.get(activity)
        val maxDeferrals = 5

        // --- CHIAVE DI TRACCIAMENTO UNICA (RESET AUTOMATICO AD OGNI NUOVA BUILD) ---
        val postponeId = if (isBeta) updateId.toString() else targetVersionCode.toString()
        val postponeCountKey = "postpone_count_$postponeId"
        val sessionPostponedKey = "session_postponed_$postponeId"

        val currentPostponeCount = prefs.getInt(postponeCountKey, 0)

        // L'aggiornamento è bloccante e non posticipabile se:
        // 1. isStrict è attivo (es. versione minima o blocco assoluto)
        // 2. isForced è TRUE (Forza Aggiornamento attivo sul server -> installazione immediata)
        // 3. I 5 rinvii locali della build corrente sono esauriti
        val isStrictlyBlocking = isStrict || isForced || (currentPostponeCount >= maxDeferrals)

        // --- VERIFICA BYPASS DI SESSIONE PERSISTENTE ---
        val isSessionPostponed = prefs.getBoolean(sessionPostponedKey, false)
        if (!isStrictlyBlocking && isSessionPostponed) {
            Log.d("UpdateHandler", "Aggiornamento già posticipato in questa sessione. Salto la visualizzazione.")
            return
        }

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null)

        val titleText = dialogView.findViewById<TextView>(R.id.update_title)
        val versionText = dialogView.findViewById<TextView>(R.id.update_version)
        val versionCard = dialogView.findViewById<View>(R.id.version_badge_card)
        val changelogText = dialogView.findViewById<TextView>(R.id.update_changelog)

        val btnPositive = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_update_positive)
        val btnNegative = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_update_negative)

        titleText.text = title

        if (!versionName.isNullOrBlank()) {
            versionText.text = activity.getString(R.string.update_dialog_version, versionName)
            versionCard?.visibility = View.VISIBLE
        } else {
            versionCard?.visibility = View.GONE
        }

        val fullChangelog = changelogPrefix + changelog
        val formattedChangelog = formatChangelogForHtml(fullChangelog)
        changelogText.text = Html.fromHtml(formattedChangelog, Html.FROM_HTML_MODE_COMPACT)

        // Creazione del dialogo con blocco del tasto Indietro del telefono
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.setCanceledOnTouchOutside(false)

        btnPositive.setOnClickListener {
            dialog.dismiss()
            startUpdateProgressActivity(isBeta, updateId)
        }

        if (isStrictlyBlocking) {
            btnNegative.visibility = View.GONE
        } else {
            val remainingPostpones = maxOf(0, maxDeferrals - currentPostponeCount)
            btnNegative.text = if (remainingPostpones == 1) {
                "Rimanda (Ultimo rinvio)"
            } else {
                "Rimanda ($remainingPostpones rinvii rimasti)"
            }
            btnNegative.visibility = View.VISIBLE
            btnNegative.setOnClickListener {
                prefs.edit().putInt(postponeCountKey, currentPostponeCount + 1).apply()
                dialog.dismiss()
            }
        }

        // Al momento della chiusura, salviamo il rinvio di sessione temporaneo
        dialog.setOnDismissListener {
            if (!isStrictlyBlocking) {
                prefs.edit().putBoolean(sessionPostponedKey, true).apply()
            }
        }

        dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
        dialog.show()
    }

    private fun formatChangelogForHtml(rawText: String): String {
        return rawText
            .replace(Regex("(?m)^([^•\n].*?)$"), "<h4><b>$1</b></h4>")
            .replace(Regex("(?m)^•\\s?(.*)"), "<li>$1</li>")
            .replace(Regex("(<li>.*?</li>)"), "<ul>$1</ul>")
            .replace("</ul>\n<ul>", "")
            .replace("\n", "<br/>")
    }

    private fun getDeviceArchitecture(): String? {
        val abis = Build.SUPPORTED_ABIS
        if (abis.contains("arm64-v8a")) return "arm64-v8a"
        if (abis.contains("armeabi-v7a") || abis.contains("armeabi")) return "armeabi-v7a"
        return null
    }

    private fun startUpdateProgressActivity(isBeta: Boolean, updateId: Long = 0L) {
        val abi = getDeviceArchitecture()

        if (abi == null) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.update_incompatible_title))
                .setMessage(activity.getString(R.string.update_incompatible_message))
                .setPositiveButton("Chiudi", null)
                .show()
            return
        }

        val cacheBuster = System.currentTimeMillis()
        val finalUrl = if (isBeta) {
            "https://your-server.example.com/CryptionChat/app-$abi-pre_release.apk?t=$cacheBuster"
        } else {
            "https://your-server.example.com/CryptionChat/app-$abi-release.apk?t=$cacheBuster"
        }

        Log.d("UpdateHandler", "Architettura rilevata: $abi, URL Finale: $finalUrl")

        val intent = Intent(activity, UpdateProgressActivity::class.java).apply {
            putExtra("DOWNLOAD_URL", finalUrl)
            putExtra("BETA_UPDATE_ID", updateId)
        }
        activity.startActivity(intent)
    }
}