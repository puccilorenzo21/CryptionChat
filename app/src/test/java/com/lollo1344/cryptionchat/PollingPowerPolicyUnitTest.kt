package com.lollo1344.cryptionchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollingPowerPolicyUnitTest {

    @Test
    fun activeCall_alwaysUsesFastPollWithoutWakeAlarm() {
        val plan = PollingPowerPolicy.computeNextWait(
            hasActiveCall = true,
            isScreenInteractive = false,
            isAppInForeground = false,
            isMeteredOrCellular = true,
            isPowerSaveMode = true,
            isDozeWhitelisted = true,
            msSinceLastActivity = 60 * 60 * 1000L,
            msSinceScreenOff = 60 * 60 * 1000L,
            consecutiveErrors = 5
        )
        assertEquals(PollingPowerPolicy.CALL_POLL_DELAY_MS, plan.delayMs)
        assertFalse(plan.useWakeAlarm)
    }

    @Test
    fun screenOn_distinguishesForegroundAndBackgroundWithoutAlarm() {
        val fgPlan = PollingPowerPolicy.computeNextWait(
            hasActiveCall = false,
            isScreenInteractive = true,
            isAppInForeground = true,
            isMeteredOrCellular = true,
            isPowerSaveMode = false,
            isDozeWhitelisted = true,
            msSinceLastActivity = 10 * 60 * 1000L,
            msSinceScreenOff = 0L,
            consecutiveErrors = 0
        )
        assertEquals(PollingPowerPolicy.FOREGROUND_POLL_DELAY_MS, fgPlan.delayMs)
        assertFalse(fgPlan.useWakeAlarm)

        val bgPlan = PollingPowerPolicy.computeNextWait(
            hasActiveCall = false,
            isScreenInteractive = true,
            isAppInForeground = false,
            isMeteredOrCellular = true,
            isPowerSaveMode = false,
            isDozeWhitelisted = true,
            msSinceLastActivity = 10 * 60 * 1000L,
            msSinceScreenOff = 0L,
            consecutiveErrors = 0
        )
        assertEquals(PollingPowerPolicy.SCREEN_ON_BG_POLL_DELAY_MS, bgPlan.delayMs)
        assertFalse(bgPlan.useWakeAlarm)
    }

    @Test
    fun screenOff_hotWindowOrWifi_rearmsQuicklyForDeepSleepSocketWait() {
        val hotPlan = PollingPowerPolicy.computeNextWait(
            hasActiveCall = false,
            isScreenInteractive = false,
            isAppInForeground = false,
            isMeteredOrCellular = true,
            isPowerSaveMode = true,
            isDozeWhitelisted = true,
            msSinceLastActivity = 30_000L,
            msSinceScreenOff = 5 * 60 * 1000L,
            consecutiveErrors = 0
        )
        assertEquals(PollingPowerPolicy.SCREEN_OFF_REARM_DELAY_MS, hotPlan.delayMs)
        assertFalse(hotPlan.useWakeAlarm)

        val wifiIdlePlan = PollingPowerPolicy.computeNextWait(
            hasActiveCall = false,
            isScreenInteractive = false,
            isAppInForeground = false,
            isMeteredOrCellular = false,
            isPowerSaveMode = false,
            isDozeWhitelisted = true,
            msSinceLastActivity = 10 * 60 * 1000L,
            msSinceScreenOff = 10 * 60 * 1000L,
            consecutiveErrors = 0
        )
        assertEquals(PollingPowerPolicy.SCREEN_OFF_REARM_DELAY_MS, wifiIdlePlan.delayMs)
        assertFalse(wifiIdlePlan.useWakeAlarm)
    }

    @Test
    fun screenOff_deepIdleCellularOrPowerSave_pausesSafelyUnderIncomingCallWindow() {
        // Su rete mobile fuori da Deep Doze: pausa 15s senza wakeup alarm hardware
        val cellularNonDoze = PollingPowerPolicy.computeNextWait(
            hasActiveCall = false,
            isScreenInteractive = false,
            isAppInForeground = false,
            isMeteredOrCellular = true,
            isPowerSaveMode = false,
            isDozeWhitelisted = true,
            msSinceLastActivity = 10 * 60 * 1000L,
            msSinceScreenOff = 10 * 60 * 1000L,
            consecutiveErrors = 0,
            isDeviceIdleMode = false
        )
        assertEquals(PollingPowerPolicy.DEEP_IDLE_CELLULAR_PAUSE_MS, cellularNonDoze.delayMs)
        assertTrue(cellularNonDoze.delayMs < 45_000L)
        assertFalse(cellularNonDoze.useWakeAlarm)

        // Su rete mobile IN Deep Doze: usa wakeup alarm per risvegliare il poll entro < 45s
        val cellularDoze = PollingPowerPolicy.computeNextWait(
            hasActiveCall = false,
            isScreenInteractive = false,
            isAppInForeground = false,
            isMeteredOrCellular = true,
            isPowerSaveMode = true,
            isDozeWhitelisted = true,
            msSinceLastActivity = 10 * 60 * 1000L,
            msSinceScreenOff = 10 * 60 * 1000L,
            consecutiveErrors = 0,
            isDeviceIdleMode = true
        )
        assertEquals(PollingPowerPolicy.DEEP_IDLE_POWER_SAVE_PAUSE_MS, cellularDoze.delayMs)
        assertTrue(cellularDoze.delayMs < 45_000L)
        assertTrue(cellularDoze.useWakeAlarm)

        // Su Wi-Fi con Risparmio Energetico (anche in Deep Doze): MAI wakeup alarm hardware
        val wifiPowerSave = PollingPowerPolicy.computeNextWait(
            hasActiveCall = false,
            isScreenInteractive = false,
            isAppInForeground = false,
            isMeteredOrCellular = false,
            isPowerSaveMode = true,
            isDozeWhitelisted = true,
            msSinceLastActivity = 10 * 60 * 1000L,
            msSinceScreenOff = 10 * 60 * 1000L,
            consecutiveErrors = 0,
            isDeviceIdleMode = true
        )
        assertEquals(PollingPowerPolicy.DEEP_IDLE_WIFI_POWER_SAVE_PAUSE_MS, wifiPowerSave.delayMs)
        assertTrue(wifiPowerSave.delayMs < 45_000L)
        assertFalse(wifiPowerSave.useWakeAlarm)
    }

    @Test
    fun screenOff_notDozeWhitelisted_doesNotRelyOnWakeAlarm() {
        val plan = PollingPowerPolicy.computeNextWait(
            hasActiveCall = false,
            isScreenInteractive = false,
            isAppInForeground = false,
            isMeteredOrCellular = true,
            isPowerSaveMode = true,
            isDozeWhitelisted = false,
            msSinceLastActivity = 10 * 60 * 1000L,
            msSinceScreenOff = 10 * 60 * 1000L,
            consecutiveErrors = 0,
            isDeviceIdleMode = true
        )
        assertEquals(PollingPowerPolicy.SCREEN_OFF_REARM_DELAY_MS, plan.delayMs)
        assertFalse(plan.useWakeAlarm)
    }

    @Test
    fun errorBackoff_scalesExponentiallyAndCapsAt60s() {
        val expected = listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L)
        expected.forEachIndexed { idx, expectedMs ->
            val planDoze = PollingPowerPolicy.computeNextWait(
                hasActiveCall = false,
                isScreenInteractive = false,
                isAppInForeground = false,
                isMeteredOrCellular = true,
                isPowerSaveMode = false,
                isDozeWhitelisted = true,
                msSinceLastActivity = 0L,
                msSinceScreenOff = 0L,
                consecutiveErrors = idx + 1,
                isDeviceIdleMode = true
            )
            assertEquals(expectedMs, planDoze.delayMs)
            assertTrue(planDoze.useWakeAlarm)

            val planNonDoze = PollingPowerPolicy.computeNextWait(
                hasActiveCall = false,
                isScreenInteractive = false,
                isAppInForeground = false,
                isMeteredOrCellular = true,
                isPowerSaveMode = false,
                isDozeWhitelisted = true,
                msSinceLastActivity = 0L,
                msSinceScreenOff = 0L,
                consecutiveErrors = idx + 1,
                isDeviceIdleMode = false
            )
            assertEquals(expectedMs, planNonDoze.delayMs)
            assertFalse(planNonDoze.useWakeAlarm)
        }
    }

    @Test
    fun keyDistributionAndUpdateThrottles_adaptToScreenState() {
        assertTrue(PollingPowerPolicy.shouldSyncKeyDistributions(true, false, 1000L, 900L, false))
        assertTrue(PollingPowerPolicy.shouldSyncKeyDistributions(false, true, 1000L, 900L, false))
        assertFalse(PollingPowerPolicy.shouldSyncKeyDistributions(false, false, 60_000L, 10_000L, true))
        assertTrue(PollingPowerPolicy.shouldSyncKeyDistributions(false, false, 310_000L, 10_000L, true))
        assertFalse(PollingPowerPolicy.shouldSyncKeyDistributions(false, false, 310_000L, 10_000L, false))
        assertTrue(PollingPowerPolicy.shouldSyncKeyDistributions(false, false, 910_000L, 10_000L, false))

        assertTrue(PollingPowerPolicy.shouldCheckUpdatesInBackground(1000L, 0L, false))
        assertFalse(PollingPowerPolicy.shouldCheckUpdatesInBackground(30 * 60 * 1000L, 1000L, true))
        assertTrue(PollingPowerPolicy.shouldCheckUpdatesInBackground(61 * 60 * 1000L, 1000L, true))
        assertFalse(PollingPowerPolicy.shouldCheckUpdatesInBackground(120 * 60 * 1000L, 1000L, false))
        assertTrue(PollingPowerPolicy.shouldCheckUpdatesInBackground(181 * 60 * 1000L, 1000L, false))
    }
}
