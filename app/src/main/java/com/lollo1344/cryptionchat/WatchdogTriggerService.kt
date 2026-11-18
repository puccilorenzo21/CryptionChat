package com.lollo1344.cryptionchat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Un servizio in foreground temporaneo con il solo scopo di attivare il WatchdogReceiver.
 * Questo servizio viene avviato manualmente tramite ADB per i test e si ferma da solo subito dopo.
 * Aggira le restrizioni di Android sull'avvio di BroadcastReceiver per le app in stato "force-stop".
 */
class WatchdogTriggerService : Service() {

    companion object {
        private const val CHANNEL_ID = "watchdog_trigger_channel"
        private const val NOTIFICATION_ID = 99
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("WatchdogTriggerService", "Servizio di trigger avviato.")

        // 1. Crea un canale di notifica (necessario per Android 8+)
        createNotificationChannel()

        // 2. Crea la notifica per avviare il servizio in foreground
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Test Watchdog")
            .setContentText("Attivazione del controllo di servizio...")
            .setSmallIcon(R.drawable.ic_settings) // Usa un'icona generica
            .build()

        // 3. Avvia il servizio in foreground
        startForeground(NOTIFICATION_ID, notification)

        // 4. Invia il broadcast al WatchdogReceiver dall'interno dell'app
        Log.i("WatchdogTriggerService", "Invio broadcast interno al WatchdogReceiver.")
        val watchdogIntent = Intent(this, WatchdogReceiver::class.java).apply {
            // Aggiungiamo un'azione per chiarezza, anche se non strettamente necessaria per un broadcast esplicito
            action = "com.lollo1344.cryptionchat.INTERNAL_TRIGGER"
        }
        sendBroadcast(watchdogIntent)

        // 5. Ferma il servizio immediatamente dopo aver fatto il suo lavoro
        Log.i("WatchdogTriggerService", "Trigger inviato. Arresto del servizio.")
        stopSelf()

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Test Watchdog Trigger",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}