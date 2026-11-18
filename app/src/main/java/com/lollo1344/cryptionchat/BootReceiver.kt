package com.lollo1344.cryptionchat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.i("BootReceiver", "Evento di avvio ricevuto ($action). Programmo l'avvio del servizio.")

            val prefs = SecurePrefs.get(context)
            val userId = prefs.getInt("user_id", -1)

            if (userId != -1) {
                ServiceScheduler.scheduleWatchdog(context)

                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val serviceIntent = Intent(context, NotificationService::class.java).apply {
                    putExtra("USER_ID", userId)
                }

                val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

                val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntent.getForegroundService(context, 100, serviceIntent, pendingIntentFlags)
                } else {
                    PendingIntent.getService(context, 100, serviceIntent, pendingIntentFlags)
                }

                try {
                    // FIXED: Sostituito setExactAndAllowWhileIdle con setAndAllowWhileIdle per non richiedere permessi speciali
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 5000,
                        pendingIntent
                    )
                    Log.i("BootReceiver", "Sveglia per l'avvio del servizio post-boot impostata.")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Errore impostazione allarme, provo avvio diretto.", e)
                    ServiceScheduler.startService(context)
                }

            } else {
                Log.i("BootReceiver", "Nessun utente loggato, servizio non avviato.")
            }
        }
    }
}