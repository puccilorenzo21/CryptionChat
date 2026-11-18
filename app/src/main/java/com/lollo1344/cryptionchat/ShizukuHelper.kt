package com.lollo1344.cryptionchat

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuHelper {

    private const val TAG = "ShizukuHelper"

    /**
     * Controlla se il servizio Shizuku è attivo e raggiungibile, riprovando per max 2 secondi
     */
    fun isShizukuAvailable(): Boolean {
        if (try { Shizuku.pingBinder() } catch (e: Exception) { false }) return true

        Log.w(TAG, "Binder non pronto, attendo Shizuku...")
        for (i in 1..10) {
            Thread.sleep(200)
            if (try { Shizuku.pingBinder() } catch (e: Exception) { false }) return true
        }
        return false
    }

    /**
     * Esegue il comando specifico richiesto tramite il Kill Code
     */
    fun executePanicCommand(suffix: String, packageName: String) {
        if (!isShizukuAvailable()) {
            Log.e(TAG, "Shizuku non è disponibile o non è in esecuzione.")
            return
        }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permesso Shizuku non concesso. Concedilo dall'app Shizuku Manager.")
            return
        }

        when (suffix) {
            "/off" -> {
                executeCommand("setprop sys.powerctl shutdown")
                executeCommand("reboot -p")
                executeCommand("svc power shutdown")
            }
            "/reboot" -> {
                executeCommand("setprop sys.powerctl reboot")
                executeCommand("reboot")
                executeCommand("svc power reboot")
            }
            "/uninstall" -> executeCommand("pm uninstall $packageName")
        }
    }

    /**
     * Esegue un comando shell bash tramite il binder di Shizuku aggirando la restrizione di visibilità
     * Rimosso il modificatore 'private' per consentire l'utilizzo da parte di altri helper dell'applicazione.
     */
    fun executeCommand(command: String): Pair<Boolean, String> {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true

            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            Log.i(TAG, "Comando eseguito: $command | ExitCode: $exitCode | Output: $output")
            Pair(exitCode == 0, output.toString().trim())

        } catch (e: Exception) {
            Log.e(TAG, "Errore esecuzione comando Shizuku: $command", e)
            Pair(false, e.message ?: "Errore sconosciuto")
        }
    }
}