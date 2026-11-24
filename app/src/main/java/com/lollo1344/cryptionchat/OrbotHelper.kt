package com.lollo1344.cryptionchat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object OrbotHelper {

    private const val ORBOT_PACKAGE_NAME = "org.torproject.android"
    private const val ORBOT_ACTIVITY_NAME = "org.torproject.android.OrbotActivity"

    // Azioni specifiche per controllare Orbot
    private const val ACTION_START_TOR = "org.torproject.android.intent.action.START"
    private const val EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS"

    /**
     * Verifica effettivamente la raggiungibilità TCP e l'handshake SOCKS5 locale verso Orbot.
     */
    fun isTorProxyReachable(host: String = "127.0.0.1", port: Int = 9050, timeoutMs: Int = 1500): Boolean {
        val cleanHost = host.trim().ifEmpty { "127.0.0.1" }
        if (port !in 1..65535 || timeoutMs <= 0) return false

        val checkTask = Callable {
            try {
                Socket().use { socket ->
                    socket.soTimeout = timeoutMs
                    socket.connect(InetSocketAddress(cleanHost, port), timeoutMs)
                    val out = socket.getOutputStream()
                    // Greeting SOCKS5: VER=5, NMETHODS=1, METHOD=0 (No Auth)
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                    out.flush()

                    val response = ByteArray(2)
                    val input = socket.getInputStream()
                    var totalRead = 0
                    while (totalRead < 2) {
                        val read = input.read(response, totalRead, 2 - totalRead)
                        if (read == -1) return@Callable false
                        totalRead += read
                    }
                    response[0] == 0x05.toByte() && response[1] != 0xFF.toByte()
                }
            } catch (e: Exception) {
                Log.w("OrbotHelper", "Proxy Tor non raggiungibile su $cleanHost:$port (${e.javaClass.simpleName})")
                false
            }
        }

        return if (Looper.myLooper() == Looper.getMainLooper()) {
            val executor = Executors.newSingleThreadExecutor()
            try {
                executor.submit(checkTask).get(timeoutMs + 500L, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                false
            } finally {
                executor.shutdownNow()
            }
        } else {
            try {
                checkTask.call()
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Ritorna true se l'uso di Tor/Orbot è abilitato (`use_proxy == true`) ma il proxy SOCKS5 non risponde.
     */
    fun isTorEnforcedAndUnavailable(context: Context): Boolean {
        return try {
            val prefs = SecurePrefs.get(context)
            if (!prefs.getBoolean("use_proxy", false)) return false
            val host = (prefs.getString("proxy_host", "127.0.0.1") ?: "127.0.0.1")
                .trim()
                .ifEmpty { "127.0.0.1" }
            val port = prefs.getString("proxy_port", "9050")
                ?.trim()
                ?.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: 9050
            !isTorProxyReachable(host, port)
        } catch (e: Exception) {
            Log.e("OrbotHelper", "Errore controllo disponibilità Tor", e)
            true
        }
    }

    /**
     * Controlla se Orbot è installata sul dispositivo.
     */
    fun isOrbotInstalled(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            pm.getPackageInfo(ORBOT_PACKAGE_NAME, PackageManager.GET_ACTIVITIES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Tenta di avviare Orbot e forzare la connessione, aprendo la sua UI.
     */
    fun requestStartOrbot(context: Context) {
        if (!isOrbotInstalled(context)) return

        try {
            Log.i("OrbotHelper", "Invio richiesta di avvio a Orbot...")

            // 1. Invia il segnale di START al Receiver di Orbot in background
            val startIntent = Intent(ACTION_START_TOR)
            startIntent.setPackage(ORBOT_PACKAGE_NAME)
            startIntent.putExtra(EXTRA_STATUS, true) // Richiede esplicitamente ON
            startIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            context.sendBroadcast(startIntent)

            // 2. Apre l'app principale di Orbot in modo sicuro per mostrare la UI
            var launchIntent = context.packageManager.getLaunchIntentForPackage(ORBOT_PACKAGE_NAME)

            if (launchIntent == null) {
                // Fallback manuale se getLaunchIntent fallisce
                launchIntent = Intent()
                launchIntent.component = ComponentName(ORBOT_PACKAGE_NAME, ORBOT_ACTIVITY_NAME)
                launchIntent.action = Intent.ACTION_MAIN
                launchIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)

        } catch (e: Exception) {
            Log.e("OrbotHelper", "Errore avvio UI Orbot", e)
            // Orbot è stato probabilmente avviato in background dal broadcast, ma l'UI ha fallito
            Toast.makeText(context, context.getString(R.string.orbot_started_bg), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Apre l'app di Orbot e chiede all'utente di spegnerla manualmente.
     */
    fun requestStopOrbot(context: Context) {
        if (!isOrbotInstalled(context)) return

        try {
            Log.i("OrbotHelper", "Apro Orbot per richiesta arresto manuale...")

            // 1. Mostra istruzioni chiare all'utente
            Toast.makeText(
                context,
                context.getString(R.string.orbot_stop_manual),
                Toast.LENGTH_LONG
            ).show()

            // 2. Tenta di aprire l'app principale
            var launchIntent = context.packageManager.getLaunchIntentForPackage(ORBOT_PACKAGE_NAME)

            if (launchIntent == null) {
                // Fallback manuale se getLaunchIntent fallisce
                launchIntent = Intent()
                launchIntent.component = ComponentName(ORBOT_PACKAGE_NAME, ORBOT_ACTIVITY_NAME)
                launchIntent.action = Intent.ACTION_MAIN
                launchIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)

        } catch (e: Exception) {
            Log.e("OrbotHelper", "Errore durante l'apertura di Orbot", e)
            Toast.makeText(context, context.getString(R.string.orbot_open_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Apre il Play Store per scaricare Orbot.
     */
    fun promptInstallOrbot(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$ORBOT_PACKAGE_NAME"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$ORBOT_PACKAGE_NAME"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}