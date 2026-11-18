package com.lollo1344.cryptionchat

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_messages")
data class LocalMessageEntity(
    @PrimaryKey val id: Int,
    val groupId: Int,
    val userId: Int,
    val username: String,
    val message: String,
    val timestamp: String,
    val isEdited: Boolean,
    val status: Int,
    val replyToMessageId: Int?,
    val replyToUsername: String?,
    val replyToMessageContent: String?,
    val replyToMessageType: String?,
    val replyToIsViewOnce: Boolean?,
    val profilePictureUrl: String?,
    val messageType: String,
    val fileId: Int?,
    val fileUrl: String?,
    val fileName: String?,
    val fileMimeType: String?,
    val fileSize: Long?,
    val audioDuration: Int?,
    val reactionsJson: String?,
    val isViewOnce: Boolean,
    val isViewedByMe: Boolean
)