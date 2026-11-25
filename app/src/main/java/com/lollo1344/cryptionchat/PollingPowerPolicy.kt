package com.lollo1344.cryptionchat

/**
 * Politica energetica pura e deterministica per il polling in background di [NotificationService].
 *
 * Obiettivi:
 * - Zero perdita di chiamate in entrata (finestra server `offering` = 45s -> pausa massima idle <= 20s).
 * - Latenza immediata durante chiamate attive, schermo acceso, finestra calda (<2 min) o Wi-Fi.
 * - Riduzione drastica dei pacchetti radio cellulari e dei cicli TLS in idle profondo a schermo spento.
 */
object PollingPowerPolicy {

    const val CALL_POLL_DELAY_MS = 1_000L
    const val FOREGROUND_POLL_DELAY_MS = 2_500L
    const val SCREEN_ON_BG_POLL_DELAY_MS = 4_000L
    const val SCREEN_OFF_REARM_DELAY_MS = 250L

    // Finestra calda dopo spegnimento schermo o ricezione messaggio/chiamata
    const val HOT_WINDOW_MS = 2 * 60 * 1000L

    // Pause in deep idle a schermo spento su rete mobile / Risparmio Energetico (sempre < 45s call window)
    const val DEEP_IDLE_CELLULAR_PAUSE_MS = 15_000L
    const val DEEP_IDLE_POWER_SAVE_PAUSE_MS = 20_000L

    // Backoff esponenziale su errori di rete (5s -> 10s -> 20s -> 40s -> 60s max)
    const val ERROR_BACKOFF_BASE_MS = 5_000L
    const val ERROR_BACKOFF_MAX_MS = 60_000L

    // Fallback periodico per E2eeSessionOrchestrator.syncDistributions()
    const val DIST_SYNC_INTERVAL_SCREEN_ON_MS = 5 * 60 * 1000L
    const val DIST_SYNC_INTERVAL_SCREEN_OFF_MS = 15 * 60 * 1000L

    // Throttle controlli aggiornamenti dal servizio background
    const val BG_UPDATE_CHECK_SCREEN_ON_MS = 60 * 60 * 1000L
    const val BG_UPDATE_CHECK_SCREEN_OFF_MS = 3 * 60 * 60 * 1000L

    data class WaitPlan(
        val delayMs: Long,
        val useWakeAlarm: Boolean
    )

    fun computeNextWait(
        hasActiveCall: Boolean,
        isScreenInteractive: Boolean,
        isAppInForeground: Boolean,
        isMeteredOrCellular: Boolean,
        isPowerSaveMode: Boolean,
        isDozeWhitelisted: Boolean,
        msSinceLastActivity: Long,
        msSinceScreenOff: Long,
        consecutiveErrors: Int
    ): WaitPlan {
        if (hasActiveCall) {
            return WaitPlan(delayMs = CALL_POLL_DELAY_MS, useWakeAlarm = false)
        }

        if (consecutiveErrors > 0) {
            val shift = (consecutiveErrors - 1).coerceAtMost(4)
            val backoff = (ERROR_BACKOFF_BASE_MS shl shift).coerceAtMost(ERROR_BACKOFF_MAX_MS)
            return WaitPlan(
                delayMs = backoff,
                useWakeAlarm = !isScreenInteractive && isDozeWhitelisted
            )
        }

        if (isScreenInteractive) {
            val delay = if (isAppInForeground) FOREGROUND_POLL_DELAY_MS else SCREEN_ON_BG_POLL_DELAY_MS
            return WaitPlan(delayMs = delay, useWakeAlarm = false)
        }

        // Schermo spento: se siamo in finestra calda (<2 min da spegnimento o ultimo evento) o su Wi-Fi
        // normale o non in Doze whitelist, riapriamo subito il socket long-poll (250ms) senza WakeLock
        // durante i 25s di attesa: la CPU dorme sul socket e si sveglia via IRQ TCP.
        val isDeepIdle = msSinceScreenOff >= HOT_WINDOW_MS && msSinceLastActivity >= HOT_WINDOW_MS
        if (isDeepIdle && isDozeWhitelisted && (isMeteredOrCellular || isPowerSaveMode)) {
            val pauseMs = if (isPowerSaveMode) DEEP_IDLE_POWER_SAVE_PAUSE_MS else DEEP_IDLE_CELLULAR_PAUSE_MS
            return WaitPlan(delayMs = pauseMs, useWakeAlarm = true)
        }

        return WaitPlan(delayMs = SCREEN_OFF_REARM_DELAY_MS, useWakeAlarm = false)
    }

    fun shouldSyncKeyDistributions(
        hasPendingDistHint: Boolean,
        hasMessages: Boolean,
        nowMs: Long,
        lastSyncMs: Long,
        isScreenInteractive: Boolean
    ): Boolean {
        if (hasPendingDistHint || hasMessages) return true
        if (lastSyncMs <= 0L) return true
        val intervalMs = if (isScreenInteractive) {
            DIST_SYNC_INTERVAL_SCREEN_ON_MS
        } else {
            DIST_SYNC_INTERVAL_SCREEN_OFF_MS
        }
        return (nowMs - lastSyncMs) >= intervalMs
    }

    fun shouldCheckUpdatesInBackground(
        nowMs: Long,
        lastAttemptMs: Long,
        isScreenInteractive: Boolean
    ): Boolean {
        if (lastAttemptMs <= 0L) return true
        val intervalMs = if (isScreenInteractive) {
            BG_UPDATE_CHECK_SCREEN_ON_MS
        } else {
            BG_UPDATE_CHECK_SCREEN_OFF_MS
        }
        return (nowMs - lastAttemptMs) >= intervalMs
    }
}
