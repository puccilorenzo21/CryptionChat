package com.lollo1344.cryptionchat

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLifecycleUnitTest {

    private class FakeSharedPreferences : SharedPreferences {
        val map = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val ops = mutableListOf<(MutableMap<String, Any?>) -> Unit>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) ops.add { if (value == null) it.remove(key) else it[key] = value }
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) ops.add { if (values == null) it.remove(key) else it[key] = values.toSet() }
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) ops.add { it[key] = value }
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) ops.add { it[key] = value }
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) ops.add { it[key] = value }
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) ops.add { it[key] = value }
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) ops.add { it.remove(key) }
            }

            override fun clear(): SharedPreferences.Editor = apply {
                ops.add { it.clear() }
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                ops.forEach { it(map) }
                ops.clear()
            }
        }
    }

    @Test
    fun passphraseValidation_rejectsNullShortAndNonHex() {
        assertFalse(SessionLifecycleHelper.isValidHexPassphrase(null))
        assertFalse(SessionLifecycleHelper.isValidHexPassphrase(""))
        assertFalse(SessionLifecycleHelper.isValidHexPassphrase("a".repeat(63)))
        assertFalse(SessionLifecycleHelper.isValidHexPassphrase("z".repeat(64)))
        assertTrue(SessionLifecycleHelper.isValidHexPassphrase("0123456789abcdef".repeat(4)))
    }

    @Test
    fun shouldResetBeforeOpen_preventsOpeningOldEncryptedDbWithNewPassphrase() {
        assertTrue(SessionLifecycleHelper.shouldResetBeforeOpen(hasExistingDb = true, hasPlaintextHeader = true, passphraseNewlyGenerated = false))
        assertTrue(SessionLifecycleHelper.shouldResetBeforeOpen(hasExistingDb = true, hasPlaintextHeader = false, passphraseNewlyGenerated = true))
        assertFalse(SessionLifecycleHelper.shouldResetBeforeOpen(hasExistingDb = true, hasPlaintextHeader = false, passphraseNewlyGenerated = false))
        assertFalse(SessionLifecycleHelper.shouldResetBeforeOpen(hasExistingDb = false, hasPlaintextHeader = false, passphraseNewlyGenerated = true))
    }

    @Test
    fun clearSessionCredentialsOnly_preservesDbPassphraseE2eeKeysAndSettings() {
        val prefs = FakeSharedPreferences()
        val dbKey = "a".repeat(64)
        prefs.edit()
            .putInt("user_id", 7)
            .putString("auth_token", "tok_123")
            .putString("username", "lollo")
            .putString("profile_picture_url", "pic.jpg")
            .putString("local_db_passphrase", dbKey)
            .putString("ikm_identity_7", "identity_secret_json")
            .putBoolean("oled_theme", true)
            .putBoolean("privacy_hide_online_status", false)
            .commit()

        var resetCalled = false
        SessionLifecycleHelper.clearSessionCredentialsOnly(prefs, preservedUserId = 7) {
            resetCalled = true
        }

        assertTrue(resetCalled)
        assertEquals(-1, prefs.getInt("user_id", -1))
        assertNull(prefs.getString("auth_token", null))
        assertNull(prefs.getString("username", null))
        assertNull(prefs.getString("profile_picture_url", null))
        assertEquals(7, prefs.getInt("last_session_user_id", -1))
        assertEquals(dbKey, prefs.getString("local_db_passphrase", null))
        assertEquals("identity_secret_json", prefs.getString("ikm_identity_7", null))
        assertTrue(prefs.getBoolean("oled_theme", false))
        assertFalse(prefs.getBoolean("privacy_hide_online_status", true))
    }

    @Test
    fun prepareForAuthenticatedUser_preservesSameAccountAndWipesDifferentAccount() {
        val prefs = FakeSharedPreferences()
        val dbKey = "b".repeat(64)
        prefs.edit()
            .putInt("last_session_user_id", 7)
            .putString("local_db_passphrase", dbKey)
            .putString("ikm_identity_7", "identity_7")
            .putString("key_12", "group_key_12")
            .putBoolean("oled_theme", true)
            .commit()

        var dbWipes = 0
        // Stesso utente dopo un 401: nessuna formattazione o wipe DB
        SessionLifecycleHelper.prepareForAuthenticatedUser(prefs, newUserId = 7) { dbWipes++ }
        assertEquals(0, dbWipes)
        assertEquals(dbKey, prefs.getString("local_db_passphrase", null))
        assertEquals("group_key_12", prefs.getString("key_12", null))

        // Cambio account sullo stesso dispositivo: wipe DB e chiavi chat del vecchio utente
        SessionLifecycleHelper.prepareForAuthenticatedUser(prefs, newUserId = 9) { dbWipes++ }
        assertEquals(1, dbWipes)
        assertNull(prefs.getString("local_db_passphrase", null))
        assertNull(prefs.getString("key_12", null))
        assertEquals(9, prefs.getInt("last_session_user_id", -1))
        assertTrue(prefs.getBoolean("oled_theme", false))
    }

    @Test
    fun privacyDefaults_onlineAndTypingAreEnabledByDefault() {
        val prefs = FakeSharedPreferences()
        assertFalse(SessionLifecycleHelper.isOnlineStatusHidden(prefs))
        assertFalse(SessionLifecycleHelper.isTypingIndicatorDisabled(prefs))

        prefs.edit()
            .putBoolean("privacy_hide_online_status", true)
            .putBoolean("privacy_disable_typing", true)
            .commit()

        assertTrue(SessionLifecycleHelper.isOnlineStatusHidden(prefs))
        assertTrue(SessionLifecycleHelper.isTypingIndicatorDisabled(prefs))
    }
}
