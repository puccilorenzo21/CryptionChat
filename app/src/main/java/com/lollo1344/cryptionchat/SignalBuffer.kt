package com.lollo1344.cryptionchat

import java.util.concurrent.CopyOnWriteArrayList

object SignalBuffer {
    private val buffer = CopyOnWriteArrayList<SignalData>()

    @Synchronized
    fun add(signal: SignalData) {
        // Garantisce l'atomicità del controllo di unicità
        if (buffer.none { it.id == signal.id }) {
            buffer.add(signal)
        }
    }

    @Synchronized
    fun addAll(signals: List<SignalData>) {
        signals.forEach { add(it) }
    }

    @Synchronized
    fun popAll(): List<SignalData> {
        val list = buffer.toList()
        buffer.clear()
        return list
    }

    @Synchronized
    fun clear() {
        buffer.clear()
    }
}