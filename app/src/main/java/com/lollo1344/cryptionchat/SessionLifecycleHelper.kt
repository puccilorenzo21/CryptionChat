package com.lollo1344.cryptionchat

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralizza la pulizia sicura di sessione, preferenze cifrate e database locale SQLCipher
 * per evitare disallineamenti tra `local_db_passphrase` e `cryptionchat_local_db`.
 */
object SessionLifecycleHelper {

    private const val KEY_LAST_SESSION_USER_ID = "last_session_user_id"

    private val SESSION_ONLY_KEYS = listOf(
        "auth_token",
        "user_id",
        "username",
        "profile_picture_url"
    )

    private val ACCOUNT_SCOPED_PREFIXES = listOf(
        "key_",
        "iv_",
        "zk_key_",
        "zk_iv_",
        "is_zk_",
        "e2ee_version_",
        "membership_epoch_",
        "private_peer_uid_",
        "last_processed_sys_msg_id_",
        "session_postponed_"
    )

    private val ACCOUNT_SCOPED_EXACT_KEYS = listOf(
        "local_db_passphrase",
        "last_notification_timestamp",
        "last_seen_broadcast_id",
        "last_beta_update_id"
    )

    internal fun isValidHexPassphrase(passHex: String?): Boolean {
        return !passHex.isNullOrEmpty() &&
            passHex.length == 64 &&
            passHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    internal fun shouldResetBeforeOpen(
        hasExistingDb: Boolean,
        hasPlaintextHeader: Boolean,
        passphraseNewlyGenerated: Boolean
    ): Boolean {
        return hasExistingDb && (hasPlaintextHeader || passphraseNewlyGenerated)
    }

    /**
     * Stato online e indicatore "sta scrivendo" attivi di default finché l'utente non li disattiva.
     */
    fun isOnlineStatusHidden(prefs: SharedPreferences): Boolean =
        prefs.getBoolean("privacy_hide_online_status", false)

    fun isTypingIndicatorDisabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean("privacy_disable_typing", false)

    /**
     * Rimuove esclusivamente le credenziali di sessione (es. su HTTP 401), preservando
     * chiavi E2EE (`ikm_*`), impostazioni locali e `local_db_passphrase` per lo stesso utente.
     */
    fun clearSessionCredentialsOnly(
        prefs: SharedPreferences,
        preservedUserId: Int = -1,
        resetUnlock: () -> Unit = { AppLifecycleManager.isAppUnlocked = false }
    ) {
        val currentUserId = if (preservedUserId > 0) preservedUserId else prefs.getInt("user_id", -1)
        val editor = prefs.edit()
        if (currentUserId > 0) {
            editor.putInt(KEY_LAST_SESSION_USER_ID, currentUserId)
        }
        SESSION_ONLY_KEYS.forEach { editor.remove(it) }
        editor.commit()
        resetUnlock()
    }

    /**
     * Verifica se l'utente che sta effettuando il login è diverso dal precedente proprietario
     * del database locale: in quel caso elimina DB e chiavi chat del vecchio account prima di proseguire.
     */
    fun prepareForAuthenticatedUser(
        prefs: SharedPreferences,
        newUserId: Int,
        deleteDatabase: () -> Unit
    ) {
        val lastSessionUserId = prefs.getInt(KEY_LAST_SESSION_USER_ID, -1)
        val activeIkmUserId = prefs.getInt("ikm_active_user_id", -1)
        val previousUserId = when {
            lastSessionUserId > 0 -> lastSessionUserId
            activeIkmUserId > 0 -> activeIkmUserId
            else -> -1
        }

        if (previousUserId > 0 && newUserId > 0 && previousUserId != newUserId) {
            deleteDatabase()
            val editor = prefs.edit()
            ACCOUNT_SCOPED_EXACT_KEYS.forEach { editor.remove(it) }
            val allKeys = prefs.all.keys.toList()
            allKeys.filter { key -> ACCOUNT_SCOPED_PREFIXES.any { prefix -> key.startsWith(prefix) } }
                .forEach { editor.remove(it) }
            editor.putInt(KEY_LAST_SESSION_USER_ID, newUserId)
            editor.commit()
        } else if (newUserId > 0 && lastSessionUserId != newUserId) {
            prefs.edit().putInt(KEY_LAST_SESSION_USER_ID, newUserId).commit()
        }
    }

    /**
     * Wipe locale completo per logout manuale, eliminazione account o ban dispositivo:
     * ferma i servizi, chiude ed elimina il database SQLCipher e svuota le SharedPreferences cifrate.
     */
    fun clearAllLocalSessionAndDatabase(context: Context) {
        val appContext = context.applicationContext
        try {
            ServiceScheduler.stopServiceAndWatchdog(appContext)
        } catch (_: Exception) {
        }
        AppDatabase.deleteLocalDatabase(appContext)
        try {
            SecurePrefs.get(appContext).edit().clear().commit()
        } catch (_: Exception) {
        }
        AppLifecycleManager.isAppUnlocked = false
    }
}
