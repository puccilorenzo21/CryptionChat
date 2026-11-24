package com.lollo1344.cryptionchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoUpdateEvaluatorUnitTest {

    @Test
    fun betaUpdateAvailable_detectsNewerBetaVersionNameOnSameVersionCode() {
        assertTrue(
            AutoUpdateEvaluator.isBetaUpdateAvailable(
                installedVC = 78L,
                installedVN = "78.3 (beta)",
                remoteVC = 78L,
                remoteVN = "78.4 (beta)",
                lastSeenBetaId = 0L,
                remoteBetaId = 100L
            )
        )
    }

    @Test
    fun betaUpdateAvailable_doesNotReTriggerSameVersionWithUnseenInitialId() {
        assertFalse(
            AutoUpdateEvaluator.isBetaUpdateAvailable(
                installedVC = 78L,
                installedVN = "78.4 (beta)",
                remoteVC = 78L,
                remoteVN = "78.4 (beta)",
                lastSeenBetaId = 0L,
                remoteBetaId = 100L
            )
        )
    }

    @Test
    fun betaUpdateAvailable_detectsRebuildWithHigherUpdateId() {
        assertTrue(
            AutoUpdateEvaluator.isBetaUpdateAvailable(
                installedVC = 78L,
                installedVN = "78.4 (beta)",
                remoteVC = 78L,
                remoteVN = "78.4 (beta)",
                lastSeenBetaId = 100L,
                remoteBetaId = 101L
            )
        )
    }

    @Test
    fun stableUpdateAvailable_respectsBetaFallbackAndBetaOptOut() {
        // Beta tester checking stable fallback should NOT downgrade/overwrite 78.4 (beta) with 78.0 (stabile)
        assertFalse(
            AutoUpdateEvaluator.isStableUpdateAvailable(
                installedVC = 78L,
                installedVN = "78.4 (beta)",
                remoteVC = 78L,
                remoteVN = "78.0 (stabile)",
                lastSeenUpdateId = 0L,
                remoteUpdateId = 0L,
                isBetaChannel = true
            )
        )

        // Beta tester checking stable fallback DOES upgrade to 79.0 (stabile) when released
        assertTrue(
            AutoUpdateEvaluator.isStableUpdateAvailable(
                installedVC = 78L,
                installedVN = "78.4 (beta)",
                remoteVC = 79L,
                remoteVN = "79.0 (stabile)",
                lastSeenUpdateId = 0L,
                remoteUpdateId = 0L,
                isBetaChannel = true
            )
        )

        // User who opted OUT of beta switches back to stable on same versionCode
        assertTrue(
            AutoUpdateEvaluator.isStableUpdateAvailable(
                installedVC = 78L,
                installedVN = "78.4 (beta)",
                remoteVC = 78L,
                remoteVN = "78.0 (stabile)",
                lastSeenUpdateId = 0L,
                remoteUpdateId = 0L,
                isBetaChannel = false
            )
        )
    }

    @Test
    fun shouldDeleteDownloadedApk_deletesAfterUpgradeAndOnSameVersion() {
        // After installing downloaded beta update (78.3 -> 78.4), remove APK immediately
        assertTrue(
            AutoUpdateEvaluator.shouldDeleteDownloadedApk(
                installedVC = 78L,
                installedVN = "78.4 (beta)",
                lastSeenUpdateId = 100L,
                apkVC = 78L,
                apkVN = "78.4 (beta)",
                apkUpdateId = 101L,
                previousInstalledVC = 78L,
                previousInstalledVN = "78.3 (beta)"
            )
        )

        // While still on 78.3, keep ready 78.4 APK until installed
        assertFalse(
            AutoUpdateEvaluator.shouldDeleteDownloadedApk(
                installedVC = 78L,
                installedVN = "78.3 (beta)",
                lastSeenUpdateId = 100L,
                apkVC = 78L,
                apkVN = "78.4 (beta)",
                apkUpdateId = 101L,
                previousInstalledVC = 78L,
                previousInstalledVN = "78.3 (beta)"
            )
        )

        // Same version already installed -> remove APK
        assertTrue(
            AutoUpdateEvaluator.shouldDeleteDownloadedApk(
                installedVC = 79L,
                installedVN = "79.0 (stabile)",
                lastSeenUpdateId = 0L,
                apkVC = 78L,
                apkVN = "78.4 (beta)",
                apkUpdateId = 101L,
                previousInstalledVC = 79L,
                previousInstalledVN = "79.0 (stabile)"
            )
        )
    }

    @Test
    fun downloadedApkReadyForInstall_validatesBetaAndStableTargets() {
        assertTrue(
            AutoUpdateEvaluator.isDownloadedApkReadyForInstall(
                installedVC = 78L,
                installedVN = "78.3 (beta)",
                lastSeenUpdateId = 0L,
                apkVC = 78L,
                apkVN = "78.4 (beta)",
                apkUpdateId = 101L,
                isBeta = true
            )
        )

        assertFalse(
            AutoUpdateEvaluator.isDownloadedApkReadyForInstall(
                installedVC = 78L,
                installedVN = "78.4 (beta)",
                lastSeenUpdateId = 101L,
                apkVC = 78L,
                apkVN = "78.4 (beta)",
                apkUpdateId = 101L,
                isBeta = true
            )
        )
    }

    @Test
    fun progressAndThrottlingHelpers_behaveCorrectly() {
        assertEquals(50, AutoUpdateEvaluator.calculateProgressPercent(500L, 1000L))
        assertEquals(-1, AutoUpdateEvaluator.calculateProgressPercent(500L, -1L))
        assertTrue(AutoUpdateEvaluator.shouldTriggerBackgroundCheck(1000L, 0L, 600_000L))
        assertFalse(AutoUpdateEvaluator.shouldTriggerBackgroundCheck(500_000L, 100_000L, 600_000L))
        assertTrue(AutoUpdateEvaluator.shouldTriggerBackgroundCheck(700_000L, 100_000L, 600_000L))
    }
}
