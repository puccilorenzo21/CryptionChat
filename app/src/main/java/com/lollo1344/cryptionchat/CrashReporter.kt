package com.lollo1344.cryptionchat

import android.content.Context
import android.util.Log

object CrashReporter {

    private const val TAG = "CrashReporter"

    /**
     * Inizializzazione CrashReporter in modalità locale/anti-forensics.
     * Nessun logcat estratto e nessun upload di rete verso log_uploader.php.
     */
    fun init(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception local dump (no upload): ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "CrashReporter inizializzato in modalità sicura offline.")
    }

    /**
     * Segnalazione errore non fatale solo su log locale.
     */
    fun reportError(context: Context, throwable: Throwable) {
        Log.w(TAG, "Local non-fatal error: ${throwable.message}", throwable)
    }
}
