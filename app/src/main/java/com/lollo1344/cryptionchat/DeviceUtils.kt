package com.lollo1344.cryptionchat

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

object DeviceUtils {
    /**
     * Recupera l'ID offuscato e deterministico del dispositivo.
     * Non cambia dopo il logout e non espone mai ANDROID_ID in chiaro al server.
     */
    fun getDeviceId(context: Context): String {
        return try {
            val rawId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "FALLBACK_ID"
            val input = "${rawId}_YOUR_STATIC_HARDWARE_SALT"
            val md = MessageDigest.getInstance("SHA-256")
            val hashBytes = md.digest(input.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "ANONYMOUS_DEVICE_FALLBACK"
        }
    }
}
