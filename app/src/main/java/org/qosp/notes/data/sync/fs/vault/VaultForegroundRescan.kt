package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.minutes

/**
 * Throttles vault rescans while the app is in the foreground. SAF tree observers often miss
 * direct file writes (e.g. Syncthing), so resume and visible list screens trigger a best-effort scan.
 */
class VaultForegroundRescan {
    private val mutex = Mutex()
    private var lastScanEpochMs: Long = 0L
    private val minIntervalMs = 2.minutes.inWholeMilliseconds

    suspend fun shouldRunRescan(): Boolean = mutex.withLock {
        val now = System.currentTimeMillis()
        if (now - lastScanEpochMs < minIntervalMs) return@withLock false
        lastScanEpochMs = now
        true
    }

    fun resetThrottle() {
        lastScanEpochMs = 0L
    }
}
