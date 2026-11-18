package com.lollo1344.cryptionchat

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("WatchdogReceiver", "Watchdog attivato. Controllo lo stato di sicurezza e servizi.")

        checkDeadMansSwitch(context)

        if (!isServiceRunning(context, NotificationService::class.java)) {
            Log.w("WatchdogReceiver", "Servizio di notifica non trovato in esecuzione. Tento di riavviarlo tramite AlarmManager.")
            scheduleImmediateServiceRestart(context)
        }
    }

    private fun checkDeadMansSwitch(context: Context) {
        val prefs = SecurePrefs.get(context)
        val deadManDays = prefs.getInt("dead_man_days", 0)

        if (deadManDays <= 0) return

        val lastActive = prefs.getLong("last_active_timestamp", 0L)
        if (lastActive == 0L) return

        val diffMillis = System.currentTimeMillis() - lastActive
        val thresholdMillis = deadManDays * 24L * 60L * 60L * 1000L

        if (diffMillis > thresholdMillis) {
            Log.e("WatchdogReceiver", "!!! DEAD MAN'S SWITCH ATTIVATO !!! Inattività per oltre $deadManDays giorni.")
            PanicHelper.executePanicProtocol(context)
        }
    }

    private fun scheduleImmediateServiceRestart(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val serviceIntent = Intent(context, NotificationService::class.java)

        val prefs = SecurePrefs.get(context)
        val userId = prefs.getInt("user_id", -1)
        if (userId != -1) {
            serviceIntent.putExtra("USER_ID", userId)
        } else {
            return
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, 1, serviceIntent, pendingIntentFlags)
        } else {
            PendingIntent.getService(context, 1, serviceIntent, pendingIntentFlags)
        }

        try {
            // FIXED: Sostituito setExactAndAllowWhileIdle con setAndAllowWhileIdle per non richiedere permessi speciali
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1000,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.e("WatchdogReceiver", "Errore durante il restart del servizio.", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    return service.foreground
                }
                return true
            }
        }
        return false
    }
}