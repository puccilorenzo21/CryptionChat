package com.lollo1344.cryptionchat

import android.content.ComponentName
import android.content.pm.PackageManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

class HideAppTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val isHidden = SecurePrefs.get(this).getBoolean("app_hidden", false)
        updateTileState(isHidden)
    }

    override fun onClick() {
        super.onClick()
        val prefs = SecurePrefs.get(this)
        val isCurrentlyHidden = prefs.getBoolean("app_hidden", false)
        val newState = !isCurrentlyHidden

        prefs.edit().putBoolean("app_hidden", newState).apply()

        // 1. Aggiorna SUBITO la grafica del Tile (il lucchetto)
        // prima che il sistema possa killare temporaneamente l'app per nasconderla
        updateTileState(newState)

        val packageManager = packageManager

        // 2. Assicuriamoci che l'Activity reale sia SEMPRE abilitata
        val realActivity = ComponentName(this, AuthActivity::class.java)
        packageManager.setComponentEnabledSetting(
            realActivity,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )

        // 3. Agiamo sull'Alias (la vera "icona" nel launcher che hai aggiunto nel Manifest)
        val aliasName = ComponentName(this, "$packageName.LauncherAlias")

        try {
            // Usiamo 0 invece di DONT_KILL_APP. Questo forza il sistema operativo
            // a notificare immediatamente al Launcher Samsung che l'app è "sparita",
            // forzando un refresh istantaneo dell'interfaccia.
            if (newState) {
                // NASCONDI L'ICONA
                packageManager.setComponentEnabledSetting(
                    aliasName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    0
                )
            } else {
                // MOSTRA L'ICONA
                packageManager.setComponentEnabledSetting(
                    aliasName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    0
                )
            }
        } catch (e: Exception) {
            Log.e("HideAppTile", "Errore nel nascondere/mostrare l'app", e)
        }
    }

    private fun updateTileState(isHidden: Boolean) {
        val tile = qsTile ?: return

        if (isHidden) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "App Nascosta"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Nascondi App"
        }
        tile.updateTile()
    }
}