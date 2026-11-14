package org.qosp.notes.data.sync.fs.vault.history

import kotlinx.coroutines.flow.firstOrNull
import org.qosp.notes.data.dao.IdMappingDao
import org.qosp.notes.data.dao.NoteDao
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.dao.ReminderDao
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultPaths
import org.qosp.notes.data.sync.fs.vault.VaultPortableMetadata
import org.qosp.notes.data.sync.fs.vault.VaultSyncImport
import org.qosp.notes.preferences.CloudService

/**
 * Applies another device's note bins, restores and purges to this device's Room rows.
 *
 * The file move itself already happened on the device that recorded the event, so nothing here touches the
 * folder: a bin drops the local mapping to the note's now-absent path, a restore re-links the returned file,
 * and a purge drops only the local rows. The latest of the three ops per note id wins, so an older event never
 * undoes a newer decision.
 */
class HistoryNoteApplier(
    private val noteDao: NoteDao,
    private val idMappingDao: IdMappingDao,
    private val reminderDao: ReminderDao,
    private val notebookDao: NotebookDao,
    private val vaultPortableMetadata: VaultPortableMetadata,
    private val backendProvider: BackendProvider,
    private val now: () -> Long = System::currentTimeMillis,
) : HistoryApplier {

    override suspend fun apply(
        history: HistoryReadResult,
        ownDeviceId: String,
        scannedPaths: Set<String>,
    ): ApplyOutcome {
        val latest = latestPerNote(history)
        if (latest.isEmpty()) return ApplyOutcome()

        val mappings = idMappingDao.getAllByCloudService(CloudService.FILE_STORAGE)
        val mappingByUid = mappings.mapNotNull { mapping ->
            mapping.noteUid()?.let { it to mapping }
        }.toMap()

        var changed = false
        val mismatches = mutableListOf<StoredMismatch>()
        for (item in latest.values) {
            val event = item.event
            if (event.dev == ownDeviceId) continue
            // A newer format from a device that does not promise safe partial application is display-only.
            if (!item.applicable) continue

            val id = event.id ?: continue
            val mapping = mappingByUid[id]
            if (mapping == null) {
                // A bin of a note this device never held: the moved file in `.trash/` is its own copy.
                if (event.op == HistoryOps.NOTE_BIN && importBinned(item, scannedPaths)) changed = true
                continue
            }
            val note = noteDao.getById(mapping.localNoteId).firstOrNull() ?: continue
            val path = event.path?.let { runCatching { VaultPaths.normalizeNotePath(it) }.getOrNull() }
            when (event.op) {
                HistoryOps.NOTE_BIN -> {
                    if (note.isDeleted) continue
                    // The note's file as this device knows it; it differs from the event's path after a local rename.
                    val ownPath = VaultPaths.vaultMappingPath(mapping.storageUri)
                    when {
                        path == null -> mismatches += item.mismatch(note, "No folder path in this event; kept")
                        // The log arrived before the moved file: wait for the file to leave.
                        isScanned(path, scannedPaths) -> Unit
                        // Renamed or moved here before the bin arrived: the note's own file is still in the folder.
                        ownPath != null && isScanned(ownPath, scannedPaths) ->
                            mismatches += item.mismatch(note, "Renamed or moved here; kept")
                        isLocallyDirty(note, mapping, ownPath ?: path) ->
                            mismatches += item.mismatch(note, "Edited here; kept")
                        else -> {
                            bin(note, mapping, path, event.to)
                            changed = true
                        }
                    }
                }

                HistoryOps.NOTE_RESTORE -> {
                    if (!note.isDeleted) continue
                    // This device binned the note and its file move has not completed yet: the local bin is
                    // newer than any event already in the logs, so an older remote restore must not undo it.
                    if (mapping.state()?.pendingVaultDelete == true) continue
                    // The file returns through the sync tool: wait until the scan can see it.
                    if (path == null) {
                        mismatches += item.mismatch(note, "No folder path in this event; kept")
                    } else if (isScanned(path, scannedPaths)) {
                        restore(note, mapping, path)
                        changed = true
                    }
                }

                HistoryOps.NOTE_PURGE -> {
                    if (!note.isDeleted) {
                        mismatches += item.mismatch(note, "Restored here; kept")
                    } else {
                        purge(note)
                        changed = true
                    }
                }
            }
        }
        return ApplyOutcome(changed = changed, mismatches = mismatches)
    }

    private fun latestPerNote(history: HistoryReadResult): Map<String, HistoryReadResult.Item> {
        val latest = LinkedHashMap<String, HistoryReadResult.Item>()
        for (item in history.items) {
            val event = item.event
            if (event.op !in NOTE_STATE_OPS) continue
            val id = event.id?.takeIf(String::isNotBlank) ?: continue
            // Items are in merged (lc, dev, seq) order, so the last one for an id is the newest.
            latest[id] = item
        }
        return latest
    }

    /**
     * Imports a note binned before it ever reached this device. The event moved the file into `.trash/` on the
     * other device; that file is enough to rebuild the row and a binned mapping, without touching the folder.
     * The next sync finds the mapping by `noteUid`, so this happens once.
     */
    private suspend fun importBinned(item: HistoryReadResult.Item, scannedPaths: Set<String>): Boolean {
        val event = item.event
        val id = event.id?.takeIf { it.isNotBlank() } ?: return false
        val originalPath = event.path?.let { runCatching { VaultPaths.normalizeNotePath(it) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        // The log can arrive before the file move: while the live path is still scanned, importing the
        // trashed copy would make the note binned here and live again a moment later.
        if (originalPath != null && isScanned(originalPath, scannedPaths)) return false
        val to = event.to?.let { runCatching { VaultPaths.normalizeNotePath(it) }.getOrNull() }
            ?.takeIf { it.isNotBlank() } ?: return false
        val access = backendProvider.syncProvider.value as? VaultFileAccess ?: return false
        val raw = runCatching { access.readVaultFile(to) }.getOrNull() ?: return false
        val path = originalPath ?: to
        val syncNote = SyncNote(
            id = 0L,
            idStr = path,
            content = raw,
            title = VaultPaths.noteTitleFromPath(path),
            lastModified = event.t / 1000,
        )
        val imported = VaultSyncImport.noteFromRemote(syncNote)
            .copy(isDeleted = true, deletionDate = event.t / 1000)
        val noteId = noteDao.insert(imported.toEntity())
        // The file's notebook, tags and reminders become portable metadata rows before the baseline snapshot.
        vaultPortableMetadata.applyFromRemote(noteId, syncNote)
        val note = noteDao.getById(noteId).firstOrNull() ?: imported.copy(id = noteId)
        val notebookName = note.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
        val state = VaultMappingState.fromDiskSync(
            rawFileContent = raw,
            note = note,
            relativePath = path,
            vaultAttachmentPaths = VaultSyncImport.vaultPathsFromNote(note),
            vaultGeneration = 0L,
            assignedNotebookName = notebookName,
        ).copy(
            binnedVaultPath = path,
            trashedPath = to,
            userBinned = true,
            noteUid = id,
        )
        idMappingDao.insert(
            IdMapping(
                localNoteId = noteId,
                remoteNoteId = 0L,
                provider = CloudService.FILE_STORAGE,
                extras = VaultMappingState.encode(state),
                isDeletedLocally = true,
                storageUri = null,
            ),
        )
        return true
    }

    /** A live note becomes a user bin without touching files: the other device already moved the file. */
    private suspend fun bin(
        note: Note,
        mapping: IdMapping,
        path: String,
        trashedPath: String?,
    ) {
        val prior = mapping.state() ?: VaultMappingState(baselineHash = "")
        val deleted = note.copy(isDeleted = true, deletionDate = now() / 1000)
        noteDao.update(deleted.toEntity())
        reminderDao.deleteIfNoteIdIn(listOf(note.id))
        idMappingDao.update(
            mapping.copy(
                storageUri = null,
                isDeletedLocally = true,
                extras = VaultMappingState.encode(
                    prior.copy(
                        binnedVaultPath = path,
                        trashedPath = trashedPath?.let {
                            runCatching { VaultPaths.normalizeNotePath(it) }.getOrNull()
                        },
                        userBinned = true,
                        pendingVaultDelete = false,
                        missingScanCount = 0,
                    ),
                ),
            ),
        )
    }

    /** Re-activates a binned note's mapping once the scan lists its returned file again. */
    private suspend fun restore(note: Note, mapping: IdMapping, path: String) {
        val restored = note.copy(isDeleted = false, deletionDate = null)
        noteDao.update(restored.toEntity())
        // Binning dropped the reminder rows; the returned file still carries them, plus its notebook and tags.
        val raw = (backendProvider.syncProvider.value as? VaultFileAccess)?.readVaultFile(path)
        if (raw != null) {
            vaultPortableMetadata.applyFromRemote(
                note.id,
                SyncNote(
                    id = 0,
                    idStr = path,
                    content = raw,
                    title = VaultPaths.noteTitleFromPath(path),
                    lastModified = restored.modifiedDate,
                ),
            )
        }
        val prior = mapping.state() ?: VaultMappingState(baselineHash = "")
        idMappingDao.update(
            mapping.copy(
                storageUri = path,
                isDeletedLocally = false,
                extras = VaultMappingState.encode(
                    prior.copy(
                        binnedVaultPath = null,
                        trashedPath = null,
                        userBinned = false,
                        pendingVaultDelete = false,
                        missingScanCount = 0,
                        noteUid = prior.noteUid.ifBlank { mapping.noteUid().orEmpty() },
                    ),
                ),
            ),
        )
    }

    private suspend fun purge(note: Note) {
        reminderDao.deleteIfNoteIdIn(listOf(note.id))
        idMappingDao.deleteByLocalId(note.id)
        noteDao.delete(note.toEntity())
    }

    /**
     * A note changed here but not pushed yet keeps its live copy; the file move would otherwise look like the
     * user's bin and the local edit would be lost.
     */
    private suspend fun isLocallyDirty(note: Note, mapping: IdMapping, path: String): Boolean {
        if (mapping.isBeingUpdated) return true
        val notebookName = note.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
        return VaultSyncImport.isLocallyDirty(note, path, mapping.state(), notebookName)
    }

    /** Case is ignored: on a case-insensitive volume the scan may spell a path differently, and a false match only waits. */
    private fun isScanned(path: String, scannedPaths: Set<String>): Boolean =
        path in scannedPaths || scannedPaths.any { it.equals(path, ignoreCase = true) }

    private fun IdMapping.noteUid(): String? =
        state()?.noteUid?.takeIf(String::isNotBlank)

    private fun IdMapping.state(): VaultMappingState? = VaultMappingState.resolve(extras)

    private fun HistoryReadResult.Item.mismatch(note: Note, reason: String): StoredMismatch =
        StoredMismatch(
            eid = event.eid,
            t = event.t,
            dev = event.dev,
            op = event.op,
            reason = reason,
            id = event.id,
            path = event.path,
            name = note.title,
        )

    private companion object {
        val NOTE_STATE_OPS: Set<String> = HistoryOps.NOTE_STATE_OPS
    }
}
