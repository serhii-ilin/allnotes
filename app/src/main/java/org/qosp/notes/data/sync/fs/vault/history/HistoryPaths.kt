package org.qosp.notes.data.sync.fs.vault.history

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/** Where the history lives inside the vault. A dot-folder, so the note scan already skips it. */
object HistoryPaths {
    const val ROOT = ".allnotes"
    const val DEVICES = "$ROOT/devices"
    const val README = "$ROOT/README.md"
    const val MANIFEST = "device.json"

    private const val EVENTS_PREFIX = "events-"
    private const val EVENTS_SUFFIX = ".jsonl"

    fun deviceDir(deviceId: String): String = "$DEVICES/$deviceId"

    fun manifestPath(deviceId: String): String = "${deviceDir(deviceId)}/$MANIFEST"

    /** `events-2026-11.jsonl` for [t], in UTC so every device agrees on the month a line lands in. */
    fun monthlyEventsFile(t: Long): String {
        val date = Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate()
        val year = date.year.toString().padStart(4, '0')
        val month = date.monthValue.toString().padStart(2, '0')
        return "$EVENTS_PREFIX$year-$month$EVENTS_SUFFIX"
    }

    /** True for `events-*.jsonl` and for Syncthing's `events-….sync-conflict-….jsonl` copies of it. */
    fun isEventsFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith(EVENTS_PREFIX) && lower.endsWith(EVENTS_SUFFIX)
    }

    /** The UTC month a `events-YYYY-MM[.sync-conflict-…].jsonl` name stands for; null for any other name. */
    fun eventsMonth(name: String): YearMonth? {
        val match = EVENTS_MONTH.matchEntire(name.lowercase()) ?: return null
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        return runCatching { YearMonth.of(year, month) }.getOrNull()
    }

    private val EVENTS_MONTH = Regex("""^events-(\d{4})-(\d{2})(?:\.sync-conflict-[^/]*)?\.jsonl$""")
}
