package com.lollo1344.cryptionchat

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher

class UpdateHandler(
    private val activity: Activity,
    @Suppress("unused") private val installPermissionLauncher: ActivityResultLauncher<Intent>
) {

    fun checkForUpdates() {
        AutoUpdateManager.getInstance(activity).checkAndDownloadInBackground(
            forceCheck = false,
            isInteractive = false
        )
    }

    fun forceManualUpdate() {
        AutoUpdateManager.getInstance(activity).checkAndDownloadInBackground(
            forceCheck = true,
            isInteractive = true
        )
    }
}
