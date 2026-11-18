package com.lollo1344.cryptionchat

import android.content.Context
import android.content.Intent

object BanHelper {
    /**
     * Pulisce i dati dell'utente e lo sbatte fuori nell'Activity dei Bannati.
     */
    fun executeBan(context: Context) {
        // Pulizia sicura di sessione, preferenze e database SQLCipher senza lasciare DB orfani
        SessionLifecycleHelper.clearAllLocalSessionAndDatabase(context)

        // Lancia la schermata di blocco
        val intent = Intent(context, BannedActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
    }
}