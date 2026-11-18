package com.lollo1344.cryptionchat

import java.util.concurrent.TimeUnit

/**
 * NUOVO OGGETTO SINGLETON:
 * Gestisce la logica per decidere quando effettuare un controllo dello stato di manutenzione.
 * Questo evita di fare una chiamata di rete ogni singola volta che onResume viene chiamato.
 */
object MaintenanceChecker {

    private var lastCheckTimestamp: Long = 0
    private val CHECK_INTERVAL = TimeUnit.MINUTES.toMillis(1) // Controlla al massimo una volta al minuto

    /**
     * Determina se è necessario un nuovo controllo.
     * @return true se è passato abbastanza tempo dall'ultimo controllo, altrimenti false.
     */
    fun shouldCheck(): Boolean {
        return System.currentTimeMillis() - lastCheckTimestamp > CHECK_INTERVAL
    }

    /**
     * Aggiorna il timestamp dell'ultimo controllo all'ora corrente.
     */
    fun updateLastCheckTimestamp() {
        lastCheckTimestamp = System.currentTimeMillis()
    }
}