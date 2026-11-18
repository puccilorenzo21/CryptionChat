package com.lollo1344.cryptionchat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object LocalMessageManager {

    private fun getDao(context: Context): MessageDao {
        return AppDatabase.getInstance(context).messageDao()
    }

    suspend fun getMessagesForGroup(context: Context, groupId: Int): List<Message> {
        val entities = getDao(context).getMessagesForGroup(groupId)
        val gson = Gson()
        val reactionType = object : TypeToken<List<Reaction>>() {}.type

        return entities.map { entity ->
            Message(
                id = entity.id,
                username = entity.username,
                message = entity.message,
                timestamp = entity.timestamp,
                isEdited = entity.isEdited,
                status = entity.status,
                userId = entity.userId,
                replyToMessageId = entity.replyToMessageId,
                replyToUsername = entity.replyToUsername,
                replyToMessageContent = entity.replyToMessageContent,
                replyToMessageType = entity.replyToMessageType,
                replyToIsViewOnce = entity.replyToIsViewOnce,
                profilePictureUrl = entity.profilePictureUrl,
                messageType = entity.messageType,
                fileId = entity.fileId,
                fileUrl = entity.fileUrl,
                fileName = entity.fileName,
                fileMimeType = entity.fileMimeType,
                fileSize = entity.fileSize,
                audioDuration = entity.audioDuration,
                reactions = if (!entity.reactionsJson.isNullOrEmpty()) gson.fromJson(entity.reactionsJson, reactionType) else null,
                isViewOnce = entity.isViewOnce,
                isViewedByMe = entity.isViewedByMe
            )
        }
    }

    suspend fun saveMessages(context: Context, groupId: Int, messages: List<Message>) {
        val dao = getDao(context)
        if (messages.isEmpty()) {
            dao.deleteAllMessagesForGroup(groupId)
            return
        }
        val gson = Gson()
        val entities = messages.map { msg ->
            LocalMessageEntity(
                id = msg.id,
                groupId = groupId,
                userId = msg.userId,
                username = msg.username,
                message = msg.message,
                timestamp = msg.timestamp,
                isEdited = msg.isEdited,
                status = msg.status,
                replyToMessageId = msg.replyToMessageId,
                replyToUsername = msg.replyToUsername,
                replyToMessageContent = msg.replyToMessageContent,
                replyToMessageType = msg.replyToMessageType,
                replyToIsViewOnce = msg.replyToIsViewOnce,
                profilePictureUrl = msg.profilePictureUrl,
                messageType = msg.messageType,
                fileId = msg.fileId,
                fileUrl = msg.fileUrl,
                fileName = msg.fileName,
                fileMimeType = msg.fileMimeType,
                fileSize = msg.fileSize,
                audioDuration = msg.audioDuration,
                reactionsJson = if (msg.reactions != null) gson.toJson(msg.reactions) else null,
                isViewOnce = msg.isViewOnce,
                isViewedByMe = msg.isViewedByMe
            )
        }
        dao.deleteMessagesNotIn(groupId, messages.map { it.id })
        dao.insertOrUpdateMessages(entities)
    }

    suspend fun deleteMessage(context: Context, messageId: Int) {
        getDao(context).deleteMessageById(messageId)
    }

    suspend fun deleteAllMessagesForGroup(context: Context, groupId: Int) {
        getDao(context).deleteAllMessagesForGroup(groupId)
    }
}