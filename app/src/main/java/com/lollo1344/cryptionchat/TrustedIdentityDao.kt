package com.lollo1344.cryptionchat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TrustedIdentityDao {

    @Query("SELECT * FROM trusted_identities WHERE peerUserId = :peerUserId LIMIT 1")
    fun getByPeerIdSync(peerUserId: Int): TrustedIdentityEntity?

    @Query("SELECT * FROM trusted_identities ORDER BY peerUserId ASC")
    fun getAllSync(): List<TrustedIdentityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSync(entity: TrustedIdentityEntity)

    @Query("UPDATE trusted_identities SET trustState = :trustState, verifiedAt = :verifiedAt WHERE peerUserId = :peerUserId")
    fun updateTrustStateSync(peerUserId: Int, trustState: String, verifiedAt: Long?)

    @Query("DELETE FROM trusted_identities WHERE peerUserId = :peerUserId")
    fun deleteByPeerIdSync(peerUserId: Int)

    @Query("DELETE FROM trusted_identities")
    fun clearAllSync()
}
