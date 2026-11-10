package org.qosp.notes.data.sync.fs.vault

/**
 * Shared wall-clock stamp of the last vault write we performed, so the folder observer can ignore
 * self-triggered change events. [now] is injectable for tests.
 */
class VaultWriteClock(private val now: () -> Long = System::currentTimeMillis) {
    @Volatile
    private var lastWriteEpochMs: Long? = null

    fun markWrite() {
        lastWriteEpochMs = now()
    }

    /** Elapsed ms since the last write, or [Long.MAX_VALUE] if we never wrote. */
    fun millisSinceLastWrite(): Long {
        val last = lastWriteEpochMs ?: return Long.MAX_VALUE
        return now() - last
    }
}
