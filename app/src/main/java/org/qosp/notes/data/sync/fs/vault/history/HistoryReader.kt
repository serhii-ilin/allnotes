package org.qosp.notes.data.sync.fs.vault.history

import kotlinx.serialization.decodeFromString

/**
 * Reads every device's log under `.allnotes/devices/`. The result is deterministic: the same files on any
 * device give the same order, because the order is `(lc, dev, seq)` and duplicate `eid`s collapse to one.
 */
class HistoryReader(private val access: VaultFileAccess) {

    suspend fun read(): HistoryReadResult {
        val manifests = readManifests()
        val byEid = LinkedHashMap<String, HistoryEvent>()
        val conflictingEids = LinkedHashSet<String>()
        var skippedLines = 0
        for (file in eventFiles()) {
            val text = runCatching { access.readVaultFile(file) }.getOrNull() ?: continue
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                val event = runCatching { HistoryJson.json.decodeFromString<HistoryEvent>(line) }.getOrNull()
                if (event == null || event.eid.isBlank() || event.dev.isBlank()) {
                    skippedLines++
                    continue
                }
                // A Syncthing conflict copy repeats lines verbatim; the first copy of an id wins.
                val first = byEid.putIfAbsent(event.eid, event)
                // Same id with different content means two installs wrote one sequence: clone evidence.
                if (first != null && first != event) conflictingEids.add(event.eid)
            }
        }
        val items = byEid.values
            .map { event ->
                HistoryReadResult.Item(
                    event = event,
                    knownOp = event.op in HistoryOps.ALL_V1,
                    applicable = isApplicable(event, manifests[event.dev]),
                )
            }
            .sortedWith(compareBy({ it.event.lc }, { it.event.dev }, { it.event.seq }))
        return HistoryReadResult(
            items = items,
            manifests = manifests,
            skippedLines = skippedLines,
            conflictingEids = conflictingEids,
        )
    }

    /**
     * While a device's manifest is missing or unreadable its lines are display-only, and so are lines in a
     * format newer than this build unless that device promises a partial apply is safe.
     */
    private fun isApplicable(event: HistoryEvent, manifest: DeviceManifest?): Boolean {
        if (manifest == null) return false
        if (event.op !in HistoryOps.ALL_V1) return false
        return event.v <= HistoryOps.VERSION || manifest.partialApplyOk
    }

    private suspend fun readManifests(): Map<String, DeviceManifest> {
        val manifests = LinkedHashMap<String, DeviceManifest>()
        for (dir in deviceDirs()) {
            val folderId = dir.substringAfterLast('/')
            val raw = runCatching { access.readVaultFile("$dir/${HistoryPaths.MANIFEST}") }.getOrNull()
            val manifest = raw?.takeIf { it.isNotBlank() }?.let {
                runCatching { HistoryJson.json.decodeFromString<DeviceManifest>(it) }.getOrNull()
            } ?: continue
            // A manifest copied into another device's folder does not make that folder's events its own.
            if (manifest.deviceId != folderId) continue
            manifests.putIfAbsent(folderId, manifest)
        }
        return manifests
    }

    /** Sorted so two devices that list a folder in different orders still read the same files in the same order. */
    private suspend fun eventFiles(): List<String> {
        val files = mutableListOf<String>()
        for (dir in deviceDirs()) {
            val children = access.listVaultDir(dir)
            for (child in children) {
                if (child.isDirectory) continue
                if (!HistoryPaths.isEventsFile(child.name)) continue
                files.add(if (child.relativePath.isBlank()) "$dir/${child.name}" else child.relativePath)
            }
        }
        return files.sorted()
    }

    private suspend fun deviceDirs(): List<String> =
        access.listVaultDir(HistoryPaths.DEVICES)
            .filter { it.isDirectory }
            .map { if (it.relativePath.isBlank()) "${HistoryPaths.DEVICES}/${it.name}" else it.relativePath }
            .sorted()
}

/**
 * Everything the reader found. [items] keep the per-event verdicts the UI needs; [maxLc] and [maxSeqFor] are what
 * the writer builds on.
 */
data class HistoryReadResult(
    val items: List<Item>,
    val manifests: Map<String, DeviceManifest>,
    val skippedLines: Int,
    /** Ids that appear twice with different content, as two clones sharing one sequence would write them. */
    val conflictingEids: Set<String> = emptySet(),
) {
    /** [event] in merged order; [applicable] is false when it must never change anything here, [knownOp] when
     * this build does not know the action at all. */
    data class Item(val event: HistoryEvent, val applicable: Boolean, val knownOp: Boolean)

    val events: List<HistoryEvent> get() = items.map { it.event }

    /** Highest usable Lamport clock seen in any log, so this device's next event sorts after everything it saw. */
    val maxLc: Long
        get() = items.asSequence()
            .map { it.event.lc }
            .filter { it in 0..MAX_SAFE_LC }
            .maxOrNull() ?: 0L

    /** Highest `seq` recorded under [deviceId]; a higher value than this device wrote means it was cloned. */
    fun maxSeqFor(deviceId: String): Long =
        items.asSequence().filter { it.event.dev == deviceId }.maxOfOrNull { it.event.seq } ?: 0L

    companion object {
        /** Hostile or corrupt clocks above this never seed ours, so `lc + 1` can never overflow. */
        const val MAX_SAFE_LC: Long = Long.MAX_VALUE / 2

        /** A missing `.allnotes/` is an empty history, not an error. */
        fun empty(): HistoryReadResult = HistoryReadResult(emptyList(), emptyMap(), 0)
    }
}
