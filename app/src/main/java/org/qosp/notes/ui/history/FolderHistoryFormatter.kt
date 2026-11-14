package org.qosp.notes.ui.history

import android.content.Context
import android.text.format.DateUtils
import org.qosp.notes.R
import org.qosp.notes.data.sync.fs.vault.history.HistoryEntry
import org.qosp.notes.data.sync.fs.vault.history.HistoryMismatch

/**
 * Turns a history event into the sentence and metadata shown on one row.
 *
 * Kept out of the adapter so the wording can be checked against the real string resources.
 */
class FolderHistoryFormatter(
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) {

    data class Row(
        val action: String,
        val device: String,
        val time: String,
        val unknownOp: Boolean,
        val reason: String? = null,
    )

    fun format(entry: HistoryEntry): Row {
        val known = entry.knownOp && entry.op in KNOWN_OPS
        return Row(
            action = action(entry.op, entry.noteTitle, entry.path, entry.to, entry.name, known),
            device = entry.deviceLabel,
            time = time(entry.timeMs),
            unknownOp = !known,
        )
    }

    fun format(mismatch: HistoryMismatch): Row {
        val known = mismatch.op in KNOWN_OPS
        return Row(
            action = action(mismatch.op, mismatch.noteTitle, mismatch.path, null, null, known),
            device = mismatch.deviceLabel,
            time = time(mismatch.timeMs),
            unknownOp = !known,
            reason = mismatch.reason,
        )
    }

    private fun action(
        op: String,
        title: String?,
        path: String?,
        to: String?,
        name: String?,
        known: Boolean,
    ): String {
        if (!known) return context.getString(R.string.history_from_newer_version, op)

        val subject = title ?: path ?: name ?: context.getString(R.string.indicator_untitled)
        val target = to ?: context.getString(R.string.indicator_untitled)

        return when (op) {
            "note.bin" -> context.getString(R.string.history_action_note_bin, subject)
            "note.restore" -> context.getString(R.string.history_action_note_restore, subject)
            "note.purge" -> context.getString(R.string.history_action_note_purge, subject)
            "note.move" -> context.getString(R.string.history_action_note_move, subject, target)
            "notebook.create" -> context.getString(R.string.history_action_notebook_create, subject)
            "notebook.rename" -> context.getString(R.string.history_action_notebook_rename, subject, target)
            "notebook.delete" -> context.getString(R.string.history_action_notebook_delete, subject)
            "tag.create" -> context.getString(R.string.history_action_tag_create, subject)
            "tag.rename" -> context.getString(R.string.history_action_tag_rename, subject, target)
            "tag.delete" -> context.getString(R.string.history_action_tag_delete, subject)
            "device.reid" -> context.getString(R.string.history_action_device_reid)
            else -> context.getString(R.string.history_from_newer_version, op)
        }
    }

    private fun time(timeMs: Long): String =
        DateUtils.getRelativeTimeSpanString(timeMs, now(), DateUtils.MINUTE_IN_MILLIS).toString()

    companion object {
        private val KNOWN_OPS = setOf(
            "note.bin",
            "note.restore",
            "note.purge",
            "note.move",
            "notebook.create",
            "notebook.rename",
            "notebook.delete",
            "tag.create",
            "tag.rename",
            "tag.delete",
            "device.reid",
        )
    }
}
