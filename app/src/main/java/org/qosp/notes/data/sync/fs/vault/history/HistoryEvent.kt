package org.qosp.notes.data.sync.fs.vault.history

import kotlinx.serialization.Serializable

/**
 * One recorded user action. `seq` is gap-free per device, `lc` is the Lamport clock and `t` is wall time for
 * display only: merged order is `(lc, dev, seq)` and never `t`.
 */
@Serializable
data class HistoryEvent(
    /** Format version of the line; see [HistoryOps.VERSION] for what this build understands. */
    val v: Int = HistoryOps.VERSION,
    /** `<deviceId>:<seq>`, unique across every log copy so a Syncthing conflict copy reads as one event. */
    val eid: String,
    val dev: String,
    val seq: Long,
    val lc: Long,
    val t: Long,
    val op: String,
    val id: String? = null,
    val path: String? = null,
    val to: String? = null,
    val name: String? = null,
)

/**
 * The `op` values this build records and applies. [ALL_V1] is what a v1 device advertises in its manifest.
 * A line with any other op is still shown (it came from a newer version) but is never applied.
 */
object HistoryOps {
    const val VERSION = 1

    const val NOTE_BIN = "note.bin"
    const val NOTE_RESTORE = "note.restore"
    const val NOTE_PURGE = "note.purge"
    const val NOTE_MOVE = "note.move"
    const val NOTEBOOK_CREATE = "notebook.create"
    const val NOTEBOOK_RENAME = "notebook.rename"
    const val NOTEBOOK_DELETE = "notebook.delete"
    const val TAG_CREATE = "tag.create"
    const val TAG_RENAME = "tag.rename"
    const val TAG_DELETE = "tag.delete"
    const val DEVICE_REID = "device.reid"

    val ALL_V1: List<String> = listOf(
        NOTE_BIN,
        NOTE_RESTORE,
        NOTE_PURGE,
        NOTE_MOVE,
        NOTEBOOK_CREATE,
        NOTEBOOK_RENAME,
        NOTEBOOK_DELETE,
        TAG_CREATE,
        TAG_RENAME,
        TAG_DELETE,
        DEVICE_REID,
    )

    /** Ops that are about one note and therefore carry its `allnotes-id`. */
    val NOTE_OPS: Set<String> = setOf(NOTE_BIN, NOTE_RESTORE, NOTE_PURGE, NOTE_MOVE)

    /** The ops that decide whether a note is live or binned here; the latest one per id wins. */
    val NOTE_STATE_OPS: Set<String> = setOf(NOTE_BIN, NOTE_RESTORE, NOTE_PURGE)
}
