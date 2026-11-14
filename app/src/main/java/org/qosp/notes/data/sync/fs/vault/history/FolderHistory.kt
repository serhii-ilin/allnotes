package org.qosp.notes.data.sync.fs.vault.history

/** What the Folder history screen shows. Implemented over the per-device logs in `.allnotes/`. */
interface FolderHistory {
    suspend fun load(): FolderHistorySnapshot
}

data class FolderHistorySnapshot(
    /** Newest first. */
    val entries: List<HistoryEntry> = emptyList(),
    /** Lines that could not be read. */
    val skippedLines: Int = 0,
    /** Events this device did not apply, with the reason (filled in Release B). */
    val mismatches: List<HistoryMismatch> = emptyList(),
)

data class HistoryEntry(
    val timeMs: Long,
    val deviceLabel: String,
    val op: String,
    /** False for ops this version does not know: shown as "From a newer version". */
    val knownOp: Boolean,
    val noteTitle: String? = null,
    val path: String? = null,
    val to: String? = null,
    val name: String? = null,
)

data class HistoryMismatch(
    val timeMs: Long,
    val deviceLabel: String,
    val op: String,
    val reason: String,
    val noteTitle: String? = null,
    val path: String? = null,
)

/** Used until the real implementation is wired in. */
object EmptyFolderHistory : FolderHistory {
    override suspend fun load() = FolderHistorySnapshot()
}
