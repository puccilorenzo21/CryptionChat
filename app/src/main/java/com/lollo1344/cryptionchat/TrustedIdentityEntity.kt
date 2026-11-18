package com.lollo1344.cryptionchat

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entità Room per il pinning TOFU (Trust On First Use) delle Identity Key dei peer.
 * Rileva cambiamenti inattesi di chiave (anti-MitM) e blocca l'invio finché l'utente non conferma.
 */
@Entity(tableName = "trusted_identities")
data class TrustedIdentityEntity(
    @PrimaryKey val peerUserId: Int,
    val ikSignPubB64: String,
    val ikDhPubB64: String,
    val fingerprintHex: String,
    val trustState: String,
    val firstSeenAt: Long,
    val verifiedAt: Long? = null
) {
    companion object {
        const val STATE_UNVERIFIED = "UNVERIFIED"
        const val STATE_VERIFIED = "VERIFIED"
        const val STATE_KEY_CHANGED = "KEY_CHANGED"
    }
}
