package org.qosp.notes.data.sync.fs.vault.history

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/** A user action waiting to be written into this device's log. `seq`, `lc` and `eid` are assigned at flush. */
@Serializable
data class PendingHistoryEvent(
    val op: String,
    val id: String? = null,
    val path: String? = null,
    val to: String? = null,
    val name: String? = null,
    val t: Long,
    /** Vault scope the action was recorded in; entries of another scope never reach this folder's log. */
    val scope: String? = null,
)

/**
 * App-private outbox (`files/vault-history/outbox.jsonl`): the action is durable before the vault is touched,
 * so a bin never fails because the notes folder is slow, locked or gone. [removeFirst] replaces the file
 * atomically and drops only what the flush consumed, never an action recorded meanwhile.
 */
class HistoryOutbox(private val file: File) {

    @Synchronized
    fun append(pending: PendingHistoryEvent) {
        file.parentFile?.mkdirs()
        file.appendText(HistoryJson.json.encodeToString(pending) + "\n")
    }

    /** Adds at the front: a retried clone flush must not put `device.reid` behind the actions it re-identifies. */
    @Synchronized
    fun prepend(pending: PendingHistoryEvent) {
        val existing = runCatching {
            if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
        }.getOrElse { emptyList() }
        writeLines(listOf(HistoryJson.json.encodeToString(pending)) + existing)
    }

    /** Pending actions in the order they were recorded; a damaged line is dropped instead of failing the flush. */
    @Synchronized
    fun readAll(): List<PendingHistoryEvent> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                if (line.isBlank()) null
                else runCatching { HistoryJson.json.decodeFromString<PendingHistoryEvent>(line) }.getOrNull()
            }
        }.getOrElse { emptyList() }
    }

    /**
     * Drops the first [count] readable actions and keeps everything appended after the flush read them. Re-reads
     * under the monitor, so a slow vault write cannot wipe a user action recorded while it ran.
     */
    @Synchronized
    fun removeFirst(count: Int) {
        if (count <= 0 || !file.exists()) return
        val lines = runCatching { file.readLines().filter { it.isNotBlank() } }.getOrElse { return }
        val keep = mutableListOf<String>()
        var removed = 0
        for (line in lines) {
            if (runCatching { HistoryJson.json.decodeFromString<PendingHistoryEvent>(line) }.isFailure) {
                // A damaged line is not a recorded action: drop it instead of letting it block the queue.
                continue
            }
            if (removed < count) {
                removed++
                continue
            }
            keep.add(line)
        }
        writeLines(keep)
    }

    /** Empties the outbox with a temp-file replace, so a crash never leaves a half-written outbox behind. */
    @Synchronized
    fun clear() {
        writeLines(emptyList())
    }

    private fun writeLines(lines: List<String>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            val text = lines.joinToString("\n") + if (lines.isEmpty()) "" else "\n"
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    companion object {
        const val DIR_NAME = "vault-history"
        const val FILE_NAME = "outbox.jsonl"

        /** Excluded from backup like `vault-recovery/`: the outbox is this install's business. */
        fun defaultFile(context: Context): File = File(context.filesDir, "$DIR_NAME/$FILE_NAME")
    }
}
