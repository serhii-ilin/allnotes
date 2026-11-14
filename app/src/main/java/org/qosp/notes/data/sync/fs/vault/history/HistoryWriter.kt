package org.qosp.notes.data.sync.fs.vault.history

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.IOException

/** What [HistoryWriter.flush] managed to do. A failure is reported, never thrown. */
sealed interface FlushResult {
    /** Nothing recorded and the manifest is current: no vault access happened. */
    data object NothingToDo : FlushResult

    data class Flushed(val events: Int, val deviceId: String) : FlushResult

    /** The outbox is intact: the next flush retries the same actions. */
    data class Failed(val error: Exception) : FlushResult
}

/**
 * Moves recorded actions from the outbox into this device's own monthly log.
 *
 * Every flush rewrites only files under `.allnotes/devices/<its own id>/`, so Syncthing can never see two
 * devices writing one file. The outbox loses only the entries the flush consumed, after the log write is
 * read back and verified.
 */
class HistoryWriter(
    private val access: VaultFileAccess,
    private val identity: DeviceIdentity,
    private val outbox: HistoryOutbox,
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val reader = HistoryReader(access)

    /**
     * Caller must hold the vault lock: the clone guard and the Lamport clock are computed from a read of every
     * log, then this device's month file is rewritten read-append-write.
     */
    suspend fun flush(): FlushResult = try {
        flushOrThrow()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FlushResult.Failed(e)
    }

    /** Records an action in the app-private outbox; no vault access, so a failed sync or locked folder cannot lose it. */
    suspend fun record(
        op: String,
        id: String? = null,
        path: String? = null,
        to: String? = null,
        name: String? = null,
    ) {
        // A note that has no allnotes-id is not in the folder yet: there is nothing another device could match.
        if (op in HistoryOps.NOTE_OPS && id.isNullOrBlank()) return
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
    }

    private suspend fun flushOrThrow(): FlushResult {
        val scope = access.historyScope
        var deviceId = identity.deviceId(scope)
        var lastWritten = identity.lastWrittenSeq(scope)
        val queued = outbox.readAll()
        val pending = queued.filter { it.scope == scope }.toMutableList()
        val dropped = queued.size - pending.size
        if (dropped > 0) {
            Log.i(TAG, "Dropping $dropped folder-history action(s) recorded for another notes folder")
        }
        // Everything read is consumed by this flush, whether written or dropped as another folder's.
        var consumed = queued.size
        val day = utcDay(now())
        // Nothing queued and this device already advertised today: only the manifest is worth a vault read.
        // The clone guard and the Lamport clock need the logs, so they still run below when anything is written.
        if (queued.isEmpty() && readManifest(deviceId) == manifestFor(deviceId, day)) {
            return FlushResult.NothingToDo
        }

        val history = if (pending.isEmpty()) HistoryReadResult.empty() else reader.read()
        var adopted = false
        val ownLines = history.events
            .filter { it.dev == deviceId && it.seq > lastWritten }
            .sortedBy { it.seq }
        val ownIdConflict = history.conflictingEids.any { it.startsWith("$deviceId:") }
        if (ownIdConflict) {
            // Two installs already wrote different lines under one sequence: neither can continue it.
            val old = deviceId
            deviceId = identity.rotate(scope)
            lastWritten = 0L
            val reid = PendingHistoryEvent(op = HistoryOps.DEVICE_REID, name = old, t = now(), scope = scope)
            // Durable before anything else is rewritten, and at the front so a retry cannot reorder it.
            outbox.prepend(reid)
            pending.add(0, reid)
            consumed += 1
        } else if (ownLines.isNotEmpty() && ownLines.size <= pending.size &&
            ownLines.indices.all { pending[it].sameActionAs(ownLines[it]) }
        ) {
            // Our own flush wrote and verified these lines but died before committing the seq and the outbox:
            // they are not another install's, so keep our id and count them as already written.
            lastWritten = ownLines.last().seq
            repeat(ownLines.size) { pending.removeAt(0) }
            adopted = true
        } else if (ownLines.isNotEmpty()) {
            // Lines under our own id that this install never recorded mean a cloned install.
            val old = deviceId
            deviceId = identity.rotate(scope)
            lastWritten = 0L
            val reid = PendingHistoryEvent(op = HistoryOps.DEVICE_REID, name = old, t = now(), scope = scope)
            // Durable before anything else is rewritten: a failed flush must not lose the only trace of the old id.
            outbox.prepend(reid)
            pending.add(0, reid)
            consumed += 1
        }

        val manifest = manifestFor(deviceId, day)
        val manifestNeedsWrite = readManifest(deviceId) != manifest
        if (pending.isEmpty() && !manifestNeedsWrite && !adopted) {
            if (consumed > 0) outbox.removeFirst(consumed)
            return FlushResult.NothingToDo
        }

        var seq = lastWritten
        var lc = history.maxLc
        val events = pending.map { action ->
            seq += 1
            lc += 1
            HistoryEvent(
                v = HistoryOps.VERSION,
                eid = "$deviceId:$seq",
                dev = deviceId,
                seq = seq,
                lc = lc,
                t = action.t,
                op = action.op,
                id = action.id,
                path = action.path,
                to = action.to,
                name = action.name,
            )
        }
        for ((fileName, monthEvents) in events.groupBy { HistoryPaths.monthlyEventsFile(it.t) }) {
            val path = "${HistoryPaths.deviceDir(deviceId)}/$fileName"
            appendAndVerify(path, monthEvents)
        }

        // Commit the log before the metadata: a README or manifest failure must never re-record these events.
        if (adopted || events.isNotEmpty()) identity.setLastWrittenSeq(seq, scope)
        outbox.removeFirst(consumed)

        writeReadmeIfMissing()
        if (manifestNeedsWrite) {
            access.writeVaultFile(HistoryPaths.manifestPath(deviceId), HistoryJson.json.encodeToString(manifest) + "\n")
        }
        return FlushResult.Flushed(events.size, deviceId)
    }

    /** True when [event] is exactly the line this pending action produced: same action, same wall time. */
    private fun PendingHistoryEvent.sameActionAs(event: HistoryEvent): Boolean =
        op == event.op && id == event.id && path == event.path && to == event.to &&
            name == event.name && t == event.t

    private fun manifestFor(deviceId: String, day: String): DeviceManifest = DeviceManifest(
        format = 1,
        deviceId = deviceId,
        name = identity.label(deviceId),
        appVersion = appVersion,
        writes = HistoryOps.ALL_V1,
        partialApplyOk = true,
        lastSeenDay = day,
    )

    /** Reads this device's own month file, appends the new lines and reads it back before trusting the write. */
    private suspend fun appendAndVerify(path: String, monthEvents: List<HistoryEvent>) {
        val existing = readExistingOrFail(path)
        val separator = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
        val lines = monthEvents.joinToString("\n") { HistoryJson.json.encodeToString(it) }
        access.writeVaultFile(path, existing + separator + lines + "\n")
        val written = access.readVaultFile(path) ?: throw IOException("History write not visible: $path")
        val present = written.lineSequence().toSet()
        monthEvents.firstOrNull { HistoryJson.json.encodeToString(it) !in present }?.let {
            throw IOException("History write not verified: ${it.eid}")
        }
    }

    /**
     * A null read is only an empty file when the folder listing agrees. On SAF a failed listing also answers
     * null; treating that as empty would overwrite this device's month with only the new lines.
     */
    private suspend fun readExistingOrFail(path: String): String {
        access.readVaultFile(path)?.let { return it }
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val listed = access.listVaultDir(dir).any { !it.isDirectory && it.name == name }
        if (listed) throw IOException("History read failed for a listed file: $path")
        return ""
    }

    private suspend fun readManifest(deviceId: String): DeviceManifest? {
        val raw = runCatching { access.readVaultFile(HistoryPaths.manifestPath(deviceId)) }.getOrNull()
        if (raw.isNullOrBlank()) return null
        return runCatching { HistoryJson.json.decodeFromString<DeviceManifest>(raw) }.getOrNull()
    }

    /** Same bytes on every device: written once, never rewritten, so it cannot become a sync conflict. */
    private suspend fun writeReadmeIfMissing() {
        if (access.readVaultFile(HistoryPaths.README) != null) return
        access.writeVaultFile(HistoryPaths.README, README_TEXT)
    }

    private fun utcDay(t: Long): String =
        java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()

    companion object {
        private const val TAG = "HistoryWriter"

        const val README_TEXT: String =
            "# AllNotes folder history\n" +
                "\n" +
                "AllNotes writes what happened in this notes folder here, so every device can show it.\n" +
                "Each device owns one folder under `devices/` and writes only there:\n" +
                "\n" +
                "- `devices/<deviceId>/device.json` — what that device records and when it last synced.\n" +
                "- `devices/<deviceId>/events-YYYY-MM.jsonl` — one JSON event per line, oldest first.\n" +
                "\n" +
                "The files are safe to read, and deleting this folder never changes or loses a note:\n" +
                "the next recorded action recreates them. Please do not edit them by hand.\n"
    }
}
