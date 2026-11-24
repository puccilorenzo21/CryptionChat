package com.lollo1344.cryptionchat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

object PowerSaverHelper {

    fun checkAndRequestPermissions(context: Context) {
        // Se siamo su versioni recenti di Android, controlliamo prima l'ottimizzazione batteria standard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                showStandardBatteryOptimizationDialog(context)
                return // Priorità all'impostazione standard
            }
        }

        // Se l'app è già whitelisted su Android stock, controlliamo le ROM proprietarie
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.getDefault())
        when {
            manufacturer.contains("xiaomi") -> showManufacturerDialog(context, getXiaomiIntent(context), "Xiaomi")
            manufacturer.contains("oppo") -> showManufacturerDialog(context, getOppoIntent(context), "Oppo")
            manufacturer.contains("vivo") -> showManufacturerDialog(context, getVivoIntent(context), "Vivo")
            manufacturer.contains("realme") -> showManufacturerDialog(context, getRealmeIntent(context), "Realme")
            manufacturer.contains("huawei") -> showManufacturerDialog(context, getHuaweiIntent(context), "Huawei")
            manufacturer.contains("oneplus") -> showManufacturerDialog(context, getOnePlusIntent(context), "OnePlus")
        }
    }

    private fun showStandardBatteryOptimizationDialog(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.battery_perm_title))
            .setMessage(context.getString(R.string.battery_perm_message))
            .setCancelable(false) // Rende il dialogo obbligatorio, non ignorabile toccando fuori
            .setPositiveButton(context.getString(R.string.battery_perm_fix)) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    context.startActivity(intent)
                }
            }
            .show()
    }

    private fun showManufacturerDialog(context: Context, intent: Intent?, manufacturer: String) {
        // Mostriamo il dialogo solo se l'intent è risolvibile e se non l'abbiamo già chiesto (usando una preferenza)
        if (intent == null || !isCallable(context, intent)) return

        val prefs = SecurePrefs.get(context)
        if (prefs.getBoolean("manufacturer_permission_asked_$manufacturer", false)) return

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.manufacturer_settings_title, manufacturer))
            .setMessage(context.getString(R.string.manufacturer_settings_msg, manufacturer))
            .setCancelable(false) // Rende il dialogo obbligatorio
            .setPositiveButton(context.getString(R.string.settings_go_to)) { _, _ ->
                try {
                    prefs.edit().putBoolean("manufacturer_permission_asked_$manufacturer", true).apply()
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Fallback alle impostazioni app
                    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(fallback)
                }
            }
            .show()
    }

    private fun isCallable(context: Context, intent: Intent): Boolean {
        return context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }

    // --- Intent specifici per produttore ---

    private fun getXiaomiIntent(context: Context): Intent {
        return Intent().apply {
            component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        }
    }

    private fun getOppoIntent(context: Context): Intent {
        return Intent().apply {
            component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        }
    }

    private fun getRealmeIntent(context: Context): Intent {
        // Realme spesso usa le stesse activity di Oppo
        return Intent().apply {
            component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        }
    }

    private fun getVivoIntent(context: Context): Intent {
        return Intent().apply {
            component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        }
    }

    private fun getHuaweiIntent(context: Context): Intent {
        return Intent().apply {
            component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
        }
    }

    private fun getOnePlusIntent(context: Context): Intent {
        return Intent().apply {
            component = ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
        }
    }
}