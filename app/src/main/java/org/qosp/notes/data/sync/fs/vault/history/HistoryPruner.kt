package org.qosp.notes.data.sync.fs.vault.history

import android.util.Log
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * Deletes the months of this device's own log that ended longer ago than the "Keep folder history" period.
 *
 * Only `.allnotes/devices/<ownDeviceId>/` is read, and only `events-YYYY-MM…jsonl` files there are deleted, so
 * another device's log, the manifest and the README can never be touched. A delete that fails stays in place
 * for the next flush: retention is best effort and never fails a sync.
 *
 * The wall clock can be wrong, for example years ahead after a clock change. The cutoff is therefore measured
 * from the newest event any log holds plus a month of slack, and the month of this device's own newest event is
 * always kept.
 */
class HistoryPruner {

    /** [deleted] files removed; [finished] false when a listing or delete failed and the next flush should retry. */
    data class Outcome(val deleted: Int, val finished: Boolean)

    /**
     * Direct callers that trust [now]: the cutoff is measured from it and no own month is protected.
     *
     * @param keepMonths months to keep, or null for Forever (no listing, no delete).
     */
    suspend fun prune(access: VaultFileAccess, ownDeviceId: String, keepMonths: Int?, now: Long): Int =
        pruneOutcome(access, ownDeviceId, keepMonths, now).deleted

    /**
     * @param keepMonths months to keep, or null for Forever (no listing, no delete).
     * @param now the wall clock, in UTC.
     * @param newestEventT newest event time seen in any log, when the caller already read them.
     * @param ownNewestEventT newest event time in this device's own log, when the caller already read it.
     */
    suspend fun pruneOutcome(
        access: VaultFileAccess,
        ownDeviceId: String,
        keepMonths: Int?,
        now: Long,
        newestEventT: Long? = null,
        ownNewestEventT: Long? = null,
    ): Outcome {
        if (keepMonths == null) return Outcome(0, finished = true)
        val effectiveNow = if (newestEventT != null) {
            minOf(now, newestEventT + TimeUnit.DAYS.toMillis(CLOCK_SLACK_DAYS))
        } else {
            now
        }
        val cutoff = YearMonth.from(Instant.ofEpochMilli(effectiveNow).atZone(ZoneOffset.UTC))
            .minusMonths(keepMonths.toLong())
        val ownNewestMonth = ownNewestEventT?.let {
            YearMonth.from(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC))
        }
        val ownDir = HistoryPaths.deviceDir(ownDeviceId)
        val entries = try {
            access.listVaultDir(ownDir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cannot list old history in $ownDir; will retry: ${e.javaClass.simpleName}")
            return Outcome(0, finished = false)
        }
        var deleted = 0
        var finished = true
        for (entry in entries) {
            // A directory is never a month log: letting a provider delete one would take its contents too.
            if (entry.isDirectory) continue
            val month = HistoryPaths.eventsMonth(entry.name) ?: continue
            if (month >= cutoff) continue
            // Never remove the last month this device wrote: it is the only proof of what that install did.
            if (month == ownNewestMonth) continue
            val path = entry.relativePath.ifBlank { "$ownDir/${entry.name}" }
            val removed = try {
                access.deleteVaultFile(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (removed) {
                deleted++
            } else {
                finished = false
                Log.w(TAG, "Cannot delete old history $path; will retry")
            }
        }
        return Outcome(deleted, finished)
    }

    companion object {
        private const val TAG = "HistoryPruner"

        /** A clock this far ahead of the newest event is more likely wrong than five years of silence. */
        private const val CLOCK_SLACK_DAYS = 31L

        /**
         * The time retention is measured from: the newest event another device wrote, because this device's own
         * events carry its own clock, which may be the wrong one. Only a device alone in the folder uses its own.
         */
        fun referenceTime(events: List<HistoryEvent>, ownDeviceId: String): Long? =
            events.asSequence().filter { it.dev != ownDeviceId }.maxOfOrNull { it.t }
                ?: events.asSequence().filter { it.dev == ownDeviceId }.maxOfOrNull { it.t }
    }
}
