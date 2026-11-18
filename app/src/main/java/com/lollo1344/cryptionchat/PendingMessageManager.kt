package com.lollo1344.cryptionchat

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.Collections

data class PendingMessage(
    val tempId: String,
    val userId: Int,
    val groupId: Int,
    val encryptedMessage: String,
    val replyToMessageId: Int?,
    val isViewOnce: Boolean,
    val mentionedUsersJson: String?,
    // Dati extra per la visualizzazione immediata (UI Ottimistica)
    val plainText: String,
    val username: String,
    val timestamp: String,
    val replyToUsername: String? = null,
    val replyToContent: String? = null,
    // --- NUOVI CAMPI PER SUPPORTARE I FILE ---
    val isTextOnly: Boolean = true,
    val messageType: String = "text",
    val fileLocalPath: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val audioDuration: Int? = null,
    val fileMimeType: String? = null
)

object PendingMessageManager {
    private val pendingMessages = Collections.synchronizedList(mutableListOf<PendingMessage>())

    // Invece di bloccare tutto con un boolean, teniamo traccia degli ID in fase di invio
    private val sendingMessageIds = Collections.synchronizedSet(mutableSetOf<String>())

    fun addMessage(message: PendingMessage) {
        pendingMessages.add(message)
    }

    fun removeMessage(tempId: String) {
        synchronized(pendingMessages) {
            pendingMessages.removeAll { it.tempId == tempId }
        }
    }

    fun clearNonTextMessagesForGroup(groupId: Int) {
        synchronized(pendingMessages) {
            pendingMessages.removeAll { it.groupId == groupId && !it.isTextOnly }
        }
    }

    /**
     * Converte i messaggi pendenti in oggetti Message per l'Adapter.
     */
    fun getPendingMessagesAsMessages(currentGroupId: Int): List<Message> {
        synchronized(pendingMessages) {
            return pendingMessages
                .filter { it.groupId == currentGroupId }
                .map { pm ->
                    Message(
                        id = -1, // ID negativo per indicare "non sincronizzato"
                        tempId = pm.tempId,
                        username = pm.username,
                        message = pm.plainText,
                        timestamp = pm.timestamp,
                        isEdited = false,
                        status = -1, // -1 = Orologio (In attesa)
                        userId = pm.userId,
                        replyToMessageId = pm.replyToMessageId,
                        replyToUsername = pm.replyToUsername,
                        replyToMessageContent = pm.replyToContent,
                        replyToMessageType = "text",
                        replyToIsViewOnce = false,
                        profilePictureUrl = null,
                        messageType = pm.messageType,
                        fileId = null,
                        fileUrl = pm.fileLocalPath,
                        fileName = pm.fileName,
                        fileMimeType = pm.fileMimeType,
                        fileSize = pm.fileSize,
                        audioDuration = pm.audioDuration,
                        reactions = null,
                        isViewOnce = pm.isViewOnce
                    )
                }
        }
    }

    fun sendAllPendingMessages(onFinished: () -> Unit) {
        // Estraiamo i messaggi di TESTO che NON sono già in fase di invio
        val messagesToSend = synchronized(pendingMessages) {
            pendingMessages.filter { it.isTextOnly && !sendingMessageIds.contains(it.tempId) }.toList()
        }

        if (messagesToSend.isEmpty()) {
            onFinished()
            return
        }

        // Segnamo questi messaggi come "in corso"
        messagesToSend.forEach { sendingMessageIds.add(it.tempId) }
        Log.d("PendingMessageManager", "Avvio invio parallelo di ${messagesToSend.size} messaggi in sospeso.")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Inviamo tutti i messaggi contemporaneamente (PARALLELO) anziché uno alla volta
                val jobs = messagesToSend.map { msg ->
                    async {
                        try {
                            val call = ApiClient.instance.sendMessage(
                                userId = msg.userId,
                                groupId = msg.groupId,
                                encryptedMessage = msg.encryptedMessage,
                                replyToMessageId = msg.replyToMessageId,
                                isViewOnce = msg.isViewOnce,
                                mentionedUsersJson = msg.mentionedUsersJson
                            )
                            val response = call.execute()
                            if (response.isSuccessful && response.body()?.status == "success") {
                                removeMessage(msg.tempId)
                                Log.d("PendingMessageManager", "Messaggio ${msg.tempId} inviato con successo.")
                            } else {
                                Log.e("PendingMessageManager", "Fallito invio messaggio ${msg.tempId}: ${response.body()?.message}")
                            }
                        } catch (e: Exception) {
                            Log.e("PendingMessageManager", "Eccezione durante invio messaggio ${msg.tempId}", e)
                        } finally {
                            // Rimuoviamo il lock per questo specifico messaggio
                            sendingMessageIds.remove(msg.tempId)
                        }
                    }
                }
                // Attendiamo che tutte le chiamate di rete in parallelo finiscano
                jobs.awaitAll()
            } finally {
                // Aggiorniamo la UI solo quando il batch è completato
                withContext(Dispatchers.Main) {
                    onFinished()
                }
            }
        }
    }
}