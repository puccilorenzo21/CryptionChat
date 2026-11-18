package com.lollo1344.cryptionchat

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Voce del vault locale cifrato con SQLCipher per conservare i messaggi decifrati
 * dal Double Ratchet dopo che le relative message key effimere sono state distrutte.
 */
@Entity(tableName = "dr_vault")
data class VaultEntryEntity(
    @PrimaryKey val ciphertextHash: String,
    val sessionId: String,
    val plaintext: String,
    val createdAt: Long
)
