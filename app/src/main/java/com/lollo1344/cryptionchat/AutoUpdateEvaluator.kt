package com.lollo1344.cryptionchat

import java.util.Locale

object AutoUpdateEvaluator {

    const val DEFAULT_BG_CHECK_INTERVAL_MS = 10 * 60 * 1000L // 10 minuti

    fun isStableUpdateAvailable(
        installedVC: Long,
        installedVN: String?,
        remoteVC: Long,
        remoteVN: String?,
        lastSeenUpdateId: Long,
        remoteUpdateId: Long,
        isBetaChannel: Boolean = false
    ): Boolean {
        if (remoteVC > installedVC) {
            return true
        }
        val installedIsBeta = installedVN?.contains("beta", ignoreCase = true) == true
        // Se l'utente è uscito dal programma beta ed è sulla stessa major versionCode, consente il passaggio alla stabile
        if (!isBetaChannel && installedIsBeta && remoteVC >= installedVC) {
            return true
        }
        if (remoteVC == installedVC) {
            if (!installedIsBeta && compareVersionNames(remoteVN, installedVN) > 0) {
                return true
            }
            if (remoteUpdateId > 0L && lastSeenUpdateId > 0L && remoteUpdateId > lastSeenUpdateId) {
                return true
            }
        }
        return false
    }

    fun isBetaUpdateAvailable(
        installedVC: Long,
        installedVN: String?,
        remoteVC: Long,
        remoteVN: String?,
        lastSeenBetaId: Long,
        remoteBetaId: Long
    ): Boolean {
        val vnComparison = compareVersionNames(remoteVN, installedVN)
        val isNewerVersion = (remoteVC > installedVC) ||
            (remoteVC == installedVC && vnComparison > 0)
        val isNewerBuildSameVersion = (remoteVC == installedVC &&
            vnComparison == 0 &&
            remoteBetaId > 0L &&
            lastSeenBetaId > 0L &&
            remoteBetaId > lastSeenBetaId)
        return isNewerVersion || isNewerBuildSameVersion
    }

    fun isMandatoryUpdate(isForced: Boolean, minimumVersionCode: Long, installedVC: Long): Boolean {
        return isForced || (minimumVersionCode > 0L && installedVC < minimumVersionCode)
    }

    /**
     * Determina se il file APK scaricato in locale deve essere rimosso alla riapertura dell'app
     * perché l'aggiornamento è stato appena installato o perché l'app installata è già pari/superiore.
     */
    fun shouldDeleteDownloadedApk(
        installedVC: Long,
        installedVN: String?,
        lastSeenUpdateId: Long,
        apkVC: Long,
        apkVN: String?,
        apkUpdateId: Long,
        previousInstalledVC: Long,
        previousInstalledVN: String?
    ): Boolean {
        val safeInstalledVN = installedVN?.trim().orEmpty()
        val safePrevVN = previousInstalledVN?.trim().orEmpty()

        // 1. La versione dell'app installata è appena cambiata rispetto all'avvio precedente -> aggiornamento applicato
        if (previousInstalledVC > 0L && (installedVC != previousInstalledVC || safeInstalledVN != safePrevVN)) {
            return true
        }

        // 2. APK scaricato privo di versionCode valido
        if (apkVC <= 0L) {
            return true
        }

        // 3. Versione installata maggiore di quella dell'APK scaricato
        if (installedVC > apkVC) {
            return true
        }

        val vnComp = compareVersionNames(safeInstalledVN, apkVN)
        val installedIsBeta = safeInstalledVN.contains("beta", ignoreCase = true)
        val apkIsBeta = apkVN?.contains("beta", ignoreCase = true) == true
        if (installedIsBeta && !apkIsBeta && apkVC >= installedVC) {
            // Transizione beta -> stabile: mantieni l'APK stabile finché non viene installato
        } else if (installedVC == apkVC && vnComp > 0) {
            return true
        }

        // 4. Versione coincidente con quella già installata senza un updateId successivo da applicare
        if (installedVC == apkVC && vnComp == 0) {
            if (apkUpdateId <= 0L || (lastSeenUpdateId > 0L && lastSeenUpdateId >= apkUpdateId)) {
                return true
            }
        }

        return false
    }

    /**
     * Determina se un APK già presente su disco è effettivamente più recente dell'app installata
     * ed è quindi pronto per mostrare il pop-up di installazione di sistema.
     */
    fun isDownloadedApkReadyForInstall(
        installedVC: Long,
        installedVN: String?,
        lastSeenUpdateId: Long,
        apkVC: Long,
        apkVN: String?,
        apkUpdateId: Long,
        isBeta: Boolean
    ): Boolean {
        if (apkVC <= 0L) return false
        return if (isBeta) {
            isBetaUpdateAvailable(installedVC, installedVN, apkVC, apkVN, lastSeenUpdateId, apkUpdateId)
        } else {
            val installedIsBeta = installedVN?.contains("beta", ignoreCase = true) == true
            if (installedIsBeta && apkVC >= installedVC) {
                return true
            }
            val vnComp = compareVersionNames(apkVN, installedVN)
            if (apkVC > installedVC || (apkVC == installedVC && vnComp > 0)) {
                return true
            }
            apkVC == installedVC && apkUpdateId > 0L && lastSeenUpdateId > 0L && apkUpdateId > lastSeenUpdateId
        }
    }

    fun shouldTriggerBackgroundCheck(nowMs: Long, lastCheckTimeMs: Long, minIntervalMs: Long): Boolean {
        if (lastCheckTimeMs <= 0L) return true
        return (nowMs - lastCheckTimeMs) >= minIntervalMs
    }

    fun compareVersionNames(v1: String?, v2: String?): Int {
        val safe1 = v1.orEmpty()
        val safe2 = v2.orEmpty()
        try {
            var s1 = safe1.replace(Regex("[^0-9.]"), " ").trim()
            if (s1.contains(" ")) s1 = s1.split(Regex("\\s+"))[0]
            var s2 = safe2.replace(Regex("[^0-9.]"), " ").trim()
            if (s2.contains(" ")) s2 = s2.split(Regex("\\s+"))[0]

            val parts1 = s1.split(".")
            val parts2 = s2.split(".")
            val len = maxOf(parts1.size, parts2.size)
            for (i in 0 until len) {
                val num1 = if (i < parts1.size && parts1[i].isNotEmpty()) parts1[i].toInt() else 0
                val num2 = if (i < parts2.size && parts2[i].isNotEmpty()) parts2[i].toInt() else 0
                if (num1 != num2) {
                    return num1.compareTo(num2)
                }
            }
        } catch (_: Exception) {
        }
        return 0
    }

    fun calculateProgressPercent(bytesRead: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) return -1
        if (bytesRead <= 0L) return 0
        if (bytesRead >= totalBytes) return 100
        return ((bytesRead * 100L) / totalBytes).toInt()
    }

    fun shouldEmitProgressUpdate(
        currentPercent: Int,
        lastEmittedPercent: Int,
        nowMs: Long,
        lastEmitMs: Long,
        minIntervalMs: Long
    ): Boolean {
        if (currentPercent >= 100 && lastEmittedPercent != 100) return true
        if (lastEmitMs <= 0L) return true
        if (currentPercent == lastEmittedPercent && currentPercent >= 0) return false
        return (nowMs - lastEmitMs) >= minIntervalMs
    }

    fun formatSpeedText(speedMbps: Double): String {
        return when {
            speedMbps >= 1.0 -> String.format(Locale.US, "%.1f MB/s", speedMbps)
            speedMbps > 0.0 -> String.format(Locale.US, "%d KB/s", maxOf(1, Math.round(speedMbps * 1024.0).toInt()))
            else -> ""
        }
    }
}
