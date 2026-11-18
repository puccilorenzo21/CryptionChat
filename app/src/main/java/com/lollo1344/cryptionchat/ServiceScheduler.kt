package com.lollo1344.cryptionchat

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

object ServiceScheduler {

    private const val WATCHDOG_INTERVAL = AlarmManager.INTERVAL_FIFTEEN_MINUTES
    private const val WATCHDOG_REQUEST_CODE = 1001

    /**
     * Avvia il servizio di notifica in background e programma il watchdog.
     * Questa funzione dovrebbe essere chiamata solo quando l'app è in primo piano.
     */
    fun startServiceAndWatchdog(context: Context) {
        startService(context)
        scheduleWatchdog(context)
    }

    /**
     * Avvia il servizio di notifica in background.
     */
    fun startService(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(NotificationService.SERVICE_CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                Log.e("ServiceScheduler", "Impossibile avviare NotificationService: il canale è disabilitato.")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context.applicationContext, "Le notifiche non funzioneranno. Riattiva il canale 'Stato del Servizio'.", Toast.LENGTH_LONG).show()
                }
                return
            }
        }

        val prefs = SecurePrefs.get(context)
        val userId = prefs.getInt("user_id", -1)

        if (userId != -1) {
            val serviceIntent = Intent(context, NotificationService::class.java)
            serviceIntent.putExtra("USER_ID", userId)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                    Log.i("ServiceScheduler", "Avvio di NotificationService in foreground.")
                } else {
                    context.startService(serviceIntent)
                    Log.i("ServiceScheduler", "Avvio di NotificationService.")
                }
            } catch (e: Exception) {
                // --- FIX CRASH ANDROID 12+: Cattura ForegroundServiceStartNotAllowedException ---
                if (Build.VERSION.SDK_INT >= 31 && e is android.app.ForegroundServiceStartNotAllowedException) {
                    Log.e("ServiceScheduler", "Impossibile avviare il servizio: l'app è in background.", e)
                } else {
                    Log.e("ServiceScheduler", "Errore durante l'avvio del servizio.", e)
                }
                // -------------------------------------------------------------------------------
            }
        } else {
            Log.w("ServiceScheduler", "Impossibile avviare il servizio: utente non loggato.")
        }
    }

    /**
     * Ferma il servizio di notifica e annulla il watchdog.
     */
    fun stopServiceAndWatchdog(context: Context) {
        stopService(context)
        cancelWatchdog(context)
    }

    /**
     * Ferma il servizio di notifica in background.
     */
    fun stopService(context: Context) {
        try {
            val serviceIntent = Intent(context, NotificationService::class.java)
            context.stopService(serviceIntent)
            Log.i("ServiceScheduler", "Servizio di notifica fermato.")
        } catch (e: Exception) {
            Log.e("ServiceScheduler", "Errore durante l'arresto del servizio.", e)
        }
    }

    /**
     * Programma un allarme periodico che attiva il WatchdogReceiver.
     */
    fun scheduleWatchdog(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, WatchdogReceiver::class.java)

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, WATCHDOG_REQUEST_CODE, intent, pendingIntentFlags)

        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            WATCHDOG_INTERVAL,
            WATCHDOG_INTERVAL,
            pendingIntent
        )
        Log.i("ServiceScheduler", "Watchdog periodico programmato.")
    }

    /**
     * Annulla l'allarme del watchdog.
     */
    private fun cancelWatchdog(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, WatchdogReceiver::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        } else {
            PendingIntent.FLAG_NO_CREATE
        }
        val pendingIntent = PendingIntent.getBroadcast(context, WATCHDOG_REQUEST_CODE, intent, pendingIntentFlags)

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.i("ServiceScheduler", "Watchdog periodico annullato.")
        }
    }
}