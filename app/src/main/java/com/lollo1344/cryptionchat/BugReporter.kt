package com.lollo1344.cryptionchat

import android.content.Context
import android.util.Log

object BugReporter {

    private const val TAG = "BugReporter"

    /**
     * Segnalazione diagnostica in modalità locale/anti-forensics.
     * Nessun comando shell logcat e nessun upload di rete verso log_uploader.php.
     */
    fun sendAutoReport(context: Context, errorTag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.d(TAG, "[$errorTag] $message", throwable)
        } else {
            Log.d(TAG, "[$errorTag] $message")
        }
    }
}
