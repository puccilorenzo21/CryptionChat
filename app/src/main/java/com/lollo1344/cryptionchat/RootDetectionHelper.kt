package com.lollo1344.cryptionchat

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

object RootDetectionHelper {

    fun isDeviceRooted(context: Context): Boolean {
        if (BuildConfig.DEBUG && isEmulator()) return false
        return checkRootMethod1() || checkRootMethod2() || checkRootMethod3(context) || checkRootMethod4()
    }

    fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk_gphone")
                || Build.BOARD.lowercase().contains("goldfish")
                || Build.DEVICE.lowercase().contains("emulator"))
    }

    /**
     * Esegue un comando come SuperUser e restituisce un Pair(Successo: Boolean, Output: String)
     */
    fun executeCommandAsRoot(command: String): Pair<Boolean, String> {
        var process: Process? = null
        var os: DataOutputStream? = null
        var reader: BufferedReader? = null
        var errorReader: BufferedReader? = null
        val output = StringBuilder()

        try {
            process = Runtime.getRuntime().exec("su")
            os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()

            reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            errorReader = BufferedReader(InputStreamReader(process.errorStream))
            while (errorReader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            return Pair(exitCode == 0, output.toString().trim())
        } catch (e: Exception) {
            Log.e("RootDetectionHelper", "Errore esecuzione comando root", e)
            return Pair(false, e.message ?: "Errore sconosciuto")
        } finally {
            try {
                os?.close()
                reader?.close()
                errorReader?.close()
                process?.destroy()
            } catch (e: Exception) { }
        }
    }

    fun forceUninstall(context: Context) {
        val packageName = context.packageName
        if (packageName.isNullOrEmpty()) return
        executeCommandAsRoot("pm uninstall -k $packageName")
    }

    private fun checkRootMethod1(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
            "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su"
        )
        for (path in paths) {
            if (File(path).exists()) return true
        }
        return false
    }

    private fun checkRootMethod2(): Boolean {
        val buildTags = Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }

    private fun checkRootMethod3(context: Context): Boolean {
        val packages = arrayOf(
            "com.noshufou.android.su", "com.thirdparty.superuser", "eu.chainfire.supersu",
            "com.koushikdutta.superuser", "com.zachspong.temprootremovejb", "com.ramdroid.appquarantine",
            "com.topjohnwu.magisk"
        )
        val pm = context.packageManager
        for (packageName in packages) {
            try {
                pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
                return true
            } catch (e: PackageManager.NameNotFoundException) {}
        }
        return false
    }

    // Rileva tracce di Magisk/Zygisk nei mount points
    private fun checkRootMethod4(): Boolean {
        return try {
            val lines = File("/proc/mounts").readLines()
            for (line in lines) {
                if (line.contains("magisk") || line.contains("core/img") || line.contains("mirror/bin")) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}