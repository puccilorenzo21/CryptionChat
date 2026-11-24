package com.lollo1344.cryptionchat

import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object TranscriptionTracker {

    data class TranscriptionState(
        val progress: Int = 0,
        val status: String = "",
        val isCompleted: Boolean = false,
        val text: String? = null,
        val error: String? = null
    )

    private val states = ConcurrentHashMap<Int, TranscriptionState>()
    private val listeners = ConcurrentHashMap<Int, MutableSet<(TranscriptionState) -> Unit>>()

    fun getState(messageId: Int): TranscriptionState? = states[messageId]

    fun isTranscribing(messageId: Int): Boolean {
        val state = states[messageId]
        return state != null && !state.isCompleted && state.error == null
    }

    fun registerListener(messageId: Int, listener: (TranscriptionState) -> Unit) {
        listeners.getOrPut(messageId) { Collections.synchronizedSet(mutableSetOf()) }.add(listener)
        states[messageId]?.let { listener(it) }
    }

    fun unregisterListener(messageId: Int, listener: (TranscriptionState) -> Unit) {
        listeners[messageId]?.remove(listener)
    }

    fun start(messageId: Int) {
        val initText = if (java.util.Locale.getDefault().language.equals("it", ignoreCase = true)) "Inizializzazione..." else "Initializing..."
        val state = TranscriptionState(progress = 0, status = initText)
        states[messageId] = state
        notifyListeners(messageId, state)
    }

    fun updateProgress(messageId: Int, progress: Int, status: String) {
        val state = TranscriptionState(progress = progress, status = status)
        states[messageId] = state
        notifyListeners(messageId, state)
    }

    fun finish(messageId: Int, text: String) {
        val doneText = if (java.util.Locale.getDefault().language.equals("it", ignoreCase = true)) "Completato" else "Completed"
        val state = TranscriptionState(progress = 100, status = doneText, isCompleted = true, text = text)
        states[messageId] = state
        notifyListeners(messageId, state)
    }

    fun error(messageId: Int, errorMsg: String) {
        val state = TranscriptionState(progress = 0, status = "", isCompleted = false, error = errorMsg)
        states[messageId] = state
        notifyListeners(messageId, state)
    }

    fun clear(messageId: Int) {
        states.remove(messageId)
        val resetState = TranscriptionState(progress = 0, status = "", isCompleted = false)
        notifyListeners(messageId, resetState)
    }

    private fun notifyListeners(messageId: Int, state: TranscriptionState) {
        val list = listeners[messageId]?.toList() ?: return
        Handler(Looper.getMainLooper()).post {
            list.forEach { it.invoke(state) }
        }
    }
}