package org.qosp.notes.data.sync.fs.vault.history

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.qosp.notes.data.sync.core.BackendProvider

/**
 * This device's "Keep folder history" period, read on every flush; null keeps every month.
 * An interface rather than a lambda so Koin's `verify` can inspect the constructor parameter type.
 */
fun interface HistoryRetentionPeriod {
    suspend fun keepMonths(): Int?
}

/**
 * Records user actions for the folder history and moves them into the vault when it is available.
 *
 * [record] only touches the app-private outbox, so an action is durable even when the folder is locked, and
 * never throws into the user action. Without a folder backend there is no log to grow: nothing is recorded.
 * [flush] needs the vault lock and never throws: a failed write leaves the outbox intact for the next sync
 * and cannot change a sync result.
 */
class FolderHistoryService(
    private val identity: DeviceIdentity,
    private val outbox: HistoryOutbox,
    private val appVersion: String,
    private val backendProvider: BackendProvider,
    /** Absent in tests and tools that never prune: every month is kept. */
    private val retention: HistoryRetentionPeriod? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun record(
        op: String,
        id: String? = null,
        path: String? = null,
        to: String? = null,
        name: String? = null,
    ) {
        // A note that has no allnotes-id is not in the folder yet: there is nothing another device could match.
        if (op in HistoryOps.NOTE_OPS && id.isNullOrBlank()) return
        val access = backendProvider.syncProvider.value as? VaultFileAccess ?: return
        try {
            outbox.append(
                PendingHistoryEvent(
                    op = op,
                    id = id,
                    path = path,
                    to = to,
                    name = name,
                    t = now(),
                    scope = access.historyScope,
                ),
            )
        } catch (e: Exception) {
            // A full disk must not fail the bin/rename the user just made. No note text or path in the log.
            Log.w(TAG, "Folder history record failed (${e.javaClass.simpleName}); action not logged")
        }
    }

    /** Caller must hold the vault lock. A failure is logged without any note text and retried on the next sync. */
    suspend fun flush() {
        val access = backendProvider.syncProvider.value as? VaultFileAccess ?: return
        when (val result = HistoryWriter(access, identity, outbox, appVersion, now).flush()) {
            is FlushResult.Failed -> {
                Log.w(TAG, "Folder history flush failed; will retry: ${result.error.message}")
                return
            }
            else -> Unit
        }
        // HST-13: only after a flush that did not fail, so a folder in trouble is not asked to delete as well.
        pruneOldHistory(access)
    }

    /** Removes this device's own expired months at most once a UTC day; a failure only delays it to the next flush. */
    private suspend fun pruneOldHistory(access: VaultFileAccess) {
        try {
            val months = retention?.keepMonths() ?: return
            val scope = access.historyScope
            val nowMs = now()
            val day = utcDay(nowMs)
            if (identity.lastPrunedDay(scope) == day) return
            val deviceId = identity.deviceId(scope)
            // The logs tell a wrong wall clock (for example five years ahead) apart from a long silence.
            val events = try {
                HistoryReader(access).read().events
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Folder history prune could not read the logs (${e.javaClass.simpleName}); will retry")
                return
            }
            val outcome = HistoryPruner().pruneOutcome(
                access,
                deviceId,
                months,
                nowMs,
                newestEventT = HistoryPruner.referenceTime(events, deviceId),
                ownNewestEventT = events.asSequence().filter { it.dev == deviceId }.maxOfOrNull { it.t },
            )
            // A failed listing or delete is retried on the next flush instead of waiting for tomorrow.
            if (outcome.finished) identity.setLastPrunedDay(day, scope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Folder history prune failed (${e.javaClass.simpleName}); will retry")
        }
    }

    private fun utcDay(t: Long): String =
        java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()

    private companion object {
        const val TAG = "FolderHistoryService"
    }
}
