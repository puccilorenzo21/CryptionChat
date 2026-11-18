package com.lollo1344.cryptionchat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VaultDao {

    @Query("SELECT plaintext FROM dr_vault WHERE ciphertextHash = :hash LIMIT 1")
    fun getPlaintextSync(hash: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertEntrySync(entry: VaultEntryEntity)

    @Query("DELETE FROM dr_vault WHERE sessionId = :sessionId")
    fun deleteBySessionSync(sessionId: String)

    @Query("DELETE FROM dr_vault")
    fun clearAllSync()
}
