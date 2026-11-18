package com.lollo1344.cryptionchat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageDao {

    @Query("SELECT * FROM local_messages WHERE groupId = :groupId ORDER BY id ASC")
    suspend fun getMessagesForGroup(groupId: Int): List<LocalMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMessages(messages: List<LocalMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMessage(message: LocalMessageEntity)

    @Query("DELETE FROM local_messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: Int)

    @Query("DELETE FROM local_messages WHERE groupId = :groupId")
    suspend fun deleteAllMessagesForGroup(groupId: Int)

    @Query("DELETE FROM local_messages WHERE groupId = :groupId AND id NOT IN (:keepIds)")
    suspend fun deleteMessagesNotIn(groupId: Int, keepIds: List<Int>)

    @Query("SELECT MAX(id) FROM local_messages WHERE groupId = :groupId")
    suspend fun getLastMessageId(groupId: Int): Int?
}