package org.qosp.notes.data.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import me.msoul.datastore.defaultOf
import org.qosp.notes.R
import org.qosp.notes.data.dao.IdMappingDao
import org.qosp.notes.data.dao.countActiveMappingsAtPath
import org.qosp.notes.data.dao.NoteDao
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.dao.ReminderDao
import org.qosp.notes.data.sync.fs.vault.VaultMappingExtras
import org.qosp.notes.data.sync.fs.vault.VaultFileOpFence
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.data.sync.fs.vault.VaultTreeAccess
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.NoteEntity
import org.qosp.notes.data.sync.core.AvailabilityStatus
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.core.BaseResult
import org.qosp.notes.data.sync.core.GenericError
import org.qosp.notes.data.sync.core.ISyncBackend
import org.qosp.notes.data.sync.core.NoteAction
import org.qosp.notes.data.sync.core.ProcessRemoteActions
import org.qosp.notes.data.sync.core.RemoteOperation.Create
import org.qosp.notes.data.sync.core.RemoteOperation.Delete
import org.qosp.notes.data.sync.core.RemoteOperation.Update
import org.qosp.notes.data.sync.core.Success
import org.qosp.notes.data.sync.core.SyncMethod
import org.qosp.notes.data.sync.core.SyncNotesResult
import org.qosp.notes.data.sync.core.SynchronizeNotes
import org.qosp.notes.data.sync.fs.StorageBackend
import org.qosp.notes.data.sync.fs.vault.history.FolderHistoryService
import org.qosp.notes.data.sync.fs.vault.history.HistoryApplyCoordinator
import org.qosp.notes.data.sync.fs.vault.history.VaultFileAccess
import org.qosp.notes.data.sync.fs.vault.history.HistoryOps
import org.qosp.notes.data.sync.fs.vault.VaultPortableMetadata
import org.qosp.notes.data.sync.fs.vault.VaultNoteDocument
import org.qosp.notes.data.sync.fs.vault.VaultNoteIds
import org.qosp.notes.data.sync.fs.vault.VaultContentHash
import org.qosp.notes.data.sync.fs.vault.VaultSyncImport
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.data.sync.fs.vault.VaultPaths
import org.qosp.notes.data.sync.fs.vault.VaultIo
import org.qosp.notes.data.sync.getMapping
import org.qosp.notes.data.sync.toLocalNote
import org.qosp.notes.data.sync.updateLocalNote
import org.qosp.notes.di.SyncScope
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.preferences.SortMethod
import org.qosp.notes.ui.utils.Toaster
import java.time.Instant

/** At least this many missing mapped files, and more than half of all, count as a mass-missing scan. */
private const val MASS_MISSING_COUNT = 3

/** One file that carries an id; the owner is picked by file facts every device sees. */
private data class UidOwner(
    val uid: String,
    val localNoteId: Long,
    val created: Long?,
    val lastModifiedMs: Long,
    val path: String,
)

class NoteRepositoryImpl(
    private val noteDao: NoteDao,
    private val idMappingDao: IdMappingDao,
    private val reminderDao: ReminderDao,
    private val backendProvider: BackendProvider,
    private val synchronizeNotes: SynchronizeNotes,
    private val processRemoteActions: ProcessRemoteActions,
    private val syncingScope: SyncScope,
    private val toaster: Toaster,
    private val preferenceRepository: PreferenceRepository,
    private val vaultPortableMetadata: VaultPortableMetadata,
    private val notebookDao: NotebookDao,
    private val vaultTreeAccess: VaultTreeAccess,
    private val vaultSyncCoordinator: VaultSyncCoordinator,
    private val editorSessionRegistry: EditorSessionRegistry,
    private val context: Context,
    private val folderHistoryService: FolderHistoryService? = null,
    private val historyApply: HistoryApplyCoordinator? = null,
) : NoteRepository {

    private val tag = NoteRepositoryImpl::class.java.simpleName

    /** The note's front-matter id, the only identity another device can match an event against. */
    private fun historyNoteId(mapping: IdMapping?): String? =
        mapping?.let { VaultMappingState.resolve(it.extras)?.noteUid?.takeIf(String::isNotBlank) }

    private suspend fun captureVaultFence(): VaultFileOpFence? {
        if (backendProvider.syncProvider.value?.type != CloudService.FILE_STORAGE) return null
        return VaultFileOpFence(
            vaultSyncCoordinator.currentGeneration(),
            preferenceRepository.getEncryptedString(PreferenceRepository.STORAGE_LOCATION).first(),
        )
    }

    private suspend fun persistVaultMappingBaselineForCreate(
        noteId: Long,
        syncNote: org.qosp.notes.data.sync.core.SyncNote,
        provider: CloudService,
    ) {
        val local = getById(noteId).first() ?: return
        val mapping = syncNote.getMapping(noteId, provider)
        val gen = vaultSyncCoordinator.currentGeneration()
        val notebookName = local.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
        val state = VaultMappingState.fromDiskSync(
            syncNote.content.orEmpty(),
            local,
            syncNote.idStr,
            VaultSyncImport.vaultPathsFromNote(local),
            gen,
            notebookName,
        )
        idMappingDao.insert(mapping.copy(extras = VaultMappingState.encode(state)))
    }

    private suspend fun persistVaultMappingBaseline(
        noteId: Long,
        relativePath: String,
        rawContent: String,
        existing: VaultMappingState? = null,
    ) {
        val local = getById(noteId).first() ?: return
        val gen = vaultSyncCoordinator.currentGeneration()
        val notebookName = local.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
        val cleared = existing?.copy(conflictRemoteHash = null, conflictCopyPath = null)
        val state = VaultMappingState.fromDiskSync(
            rawContent,
            local,
            relativePath,
            VaultSyncImport.vaultPathsFromNote(local),
            gen,
            notebookName,
            cleared,
        )
        idMappingDao.updateNoteExtras(
            localId = noteId,
            cloudService = CloudService.FILE_STORAGE,
            extras = VaultMappingState.encode(state),
        )
    }

    /**
     * A file that moved (rename or notebook folder) keeps its note. Room's notebook has to follow before a
     * later push, or the push would move the file back to the old folder.
     */
    private suspend fun applyMovedNotebooks(
        mappings: List<IdMapping>,
        previous: Map<Long, IdMapping>,
    ) {
        for (mapping in mappings) {
            if (mapping.provider != CloudService.FILE_STORAGE || mapping.isDeletedLocally) continue
            val newPath = VaultPaths.vaultMappingPath(mapping.storageUri) ?: continue
            val oldPath = VaultPaths.vaultMappingPath(previous[mapping.localNoteId]?.storageUri) ?: continue
            if (newPath == oldPath) continue
            vaultPortableMetadata.applyFolderNotebook(mapping.localNoteId, newPath)
            vaultPortableMetadata.applyFileName(mapping.localNoteId, oldPath, newPath)
        }
    }

    /**
     * Writes a front-matter id and the missing `created`/`modified` dates onto mapped files, without rebuilding
     * the note. A mapped note already has its own dates: they are the file's dates for a note imported in this
     * same sync, and they must not move to the file's current mtime after an external edit. Only a file with no
     * note falls back to its own modification time, which every device sees.
     *
     * A file that carries the id of another note is a copy: it gets the id its own path derives, so the
     * original keeps its id and the copy becomes its own note on the next scan.
     *
     * [scanned] is the scan this sync already paid for, keyed by [vaultPathKey]: re-reading each file here
     * would add a SAF lookup per note on every sync. Each file's front matter is parsed once for the whole
     * pass. One file that cannot be written is logged and left for the next sync, so backfill never decides
     * whether the whole sync succeeds.
     */
    private suspend fun backfillNoteIds(
        syncProvider: ISyncBackend,
        skipLocalIds: Set<Long>,
        scanned: Map<String, SyncNote>,
        notesById: Map<Long, Note>,
    ) {
        if (syncProvider.type != CloudService.FILE_STORAGE) return
        val quarantined = syncProvider.quarantinedPaths.map { vaultPathKey(it, syncProvider) }.toSet()
        val mappings = idMappingDao.getAllByCloudService(CloudService.FILE_STORAGE).filterNot { it.isDeletedLocally }
        val parsedByPath = HashMap<String, VaultNoteDocument>()
        fun parsedAt(key: String, remote: SyncNote): VaultNoteDocument =
            parsedByPath.getOrPut(key) { VaultNoteDocument.parse(remote.content.orEmpty()) }

        // The file that claims an id on every device keeps it: smallest `created` first, files without one
        // last, then the file's own modification time (a copy made with `cp` is newer), then the normalized
        // path. Local mapping ids are per device, so they cannot pick the owner.
        val ownerByUid = mappings.mapNotNull { mapping ->
            val path = VaultPaths.vaultMappingPath(mapping.storageUri) ?: return@mapNotNull null
            val key = vaultPathKey(path, syncProvider)
            if (key in quarantined) return@mapNotNull null
            val remote = scanned[key]
            val parsed = remote?.let { parsedAt(key, it) }
            val stateUid = VaultMappingState.resolve(mapping.extras)?.noteUid?.takeIf { it.isNotBlank() }
            val fileUid = parsed?.frontMatter?.id?.takeIf { it.isNotBlank() }
            val uid = stateUid ?: fileUid ?: return@mapNotNull null
            UidOwner(
                uid = uid,
                localNoteId = mapping.localNoteId,
                created = parsed?.frontMatter?.created,
                lastModifiedMs = remote?.lastModifiedMs?.takeIf { it > 0 } ?: Long.MAX_VALUE,
                path = VaultPaths.normalizeNotePath(path),
            )
        }.groupBy { it.uid }
            .mapValues { (_, owners) ->
                owners.minWith(
                    compareBy<UidOwner>(
                        { it.created == null },
                        { it.created ?: Long.MAX_VALUE },
                        { it.lastModifiedMs },
                        { it.path },
                    ),
                ).localNoteId
            }
        for (mapping in mappings) {
            if (mapping.localNoteId in skipLocalIds) continue
            val path = VaultPaths.vaultMappingPath(mapping.storageUri) ?: continue
            val key = vaultPathKey(path, syncProvider)
            if (key in quarantined) continue
            try {
                val remote = scanned[key] ?: continue
                val raw = remote.content.orEmpty()
                // Text decoded with replacement characters (not UTF-8) or behind a BOM would not be written back
                // byte-for-byte; such a file keeps matching by path and body instead of gaining an id.
                if (raw.startsWith('﻿') || '�' in raw) continue
                val parsed = parsedAt(key, remote)
                val state = VaultMappingState.resolve(mapping.extras)
                val parsedId = parsed.frontMatter.id?.takeIf { it.isNotBlank() }
                val stateUid = state?.noteUid?.takeIf { it.isNotBlank() }
                val claimedUid = parsedId ?: stateUid
                val ownedByAnother = claimedUid != null && ownerByUid[claimedUid]
                    ?.let { it != mapping.localNoteId } == true
                val derivedId = if (ownedByAnother || (parsedId == null && stateUid == null)) {
                    // Only what the file itself says: a note's creationDate falls back to the import-time mtime,
                    // which differs between devices.
                    VaultNoteIds.forFile(VaultPaths.normalizeNotePath(path), parsed.frontMatter.created)
                } else {
                    ""
                }
                val uid = when {
                    ownedByAnother -> derivedId
                    parsedId != null -> parsedId
                    else -> stateUid ?: derivedId
                }
                val withId = if (parsedId != uid) VaultNoteDocument.replaceNoteId(raw, uid) else raw
                // A mapped note's own dates are the file's dates; the mtime is only a fallback for a file that
                // has no note. A note that is locally dirty is pushed anyway, so its file keeps its bytes.
                val note = notesById[mapping.localNoteId]
                val (created, modified) = when {
                    note == null -> remote.lastModified.takeIf { it > 0 }?.let { it to it } ?: (null to null)
                    isDirty(note, path, state) -> null to null
                    else -> note.creationDate to note.modifiedDate
                }
                val updated = VaultNoteDocument.withMissingDates(withId, created, modified)
                val wrote = updated != raw && syncProvider.replaceNoteText(
                    path,
                    updated,
                    VaultContentHash.ofText(raw),
                )
                if (state == null) {
                    if (wrote) {
                        persistVaultMappingBaseline(mapping.localNoteId, path, updated)
                    }
                    continue
                }
                // A lost compare-and-swap means the file changed: leave the mapping for the next sync.
                if (parsedId == null && !wrote) continue
                if (!wrote && state.noteUid == uid) continue
                val nextRaw = if (wrote) updated else raw
                val next = state.copy(
                    noteUid = uid,
                    baselineRawHash = if (wrote) VaultContentHash.ofText(nextRaw) else state.baselineRawHash,
                )
                idMappingDao.update(mapping.copy(extras = VaultMappingState.encode(next)))
            } catch (e: Exception) {
                Log.w(tag, "backfillNoteIds: could not complete $path; will retry: ${e.message}")
            }
        }
    }

    /** True when the note has an unsynced local change, so a date backfill would only fight its push. */
    private suspend fun isDirty(note: Note, path: String, state: VaultMappingState?): Boolean {
        val notebookName = note.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
        return VaultSyncImport.isLocallyDirty(note, path, state, notebookName)
    }

    /**
     * A file Android renamed to `.trashed-<time>-<name>` is already in the bin. It is not a second note,
     * and the bin shows the original name. The file belongs to Android's trash: this app never moves or
     * deletes it, and since no AllNotes user action binned the note, emptying the bin only drops its rows.
     */
    private suspend fun retireAndroidTrashNotes() {
        val mappings = idMappingDao.getAllByCloudService(CloudService.FILE_STORAGE)
        for (mapping in mappings) {
            val path = VaultPaths.vaultMappingPath(mapping.storageUri) ?: continue
            if (!VaultPaths.isAndroidTrashFileName(VaultPaths.fileName(path))) continue
            val note = noteDao.getById(mapping.localNoteId).firstOrNull() ?: continue
            retireAndroidTrashNote(mapping, note, path)
        }
        for (note in noteDao.getAll(SortMethod.TITLE_ASC).first()) {
            if (!VaultPaths.isAndroidTrashFileName(note.title)) continue
            val visible = VaultPaths.noteTitleFromPath(note.title)
            if (visible.isBlank() || visible == note.title) continue
            noteDao.update(note.toEntity().copy(title = visible))
        }
    }

    /** Bin bookkeeping for a note whose only file is Android's `.trashed-` copy; the file is left in place. */
    private suspend fun retireAndroidTrashNote(mapping: IdMapping, note: Note, path: String) {
        val visible = VaultPaths.noteTitleFromPath(path)
        val entity = note.toEntity()
        val title = if (visible.isNotBlank() &&
            (VaultPaths.isAndroidTrashFileName(entity.title) || entity.title == VaultPaths.noteBaseName(path))
        ) {
            visible
        } else {
            entity.title
        }
        noteDao.update(
            entity.copy(
                title = title,
                isDeleted = true,
                deletionDate = entity.deletionDate ?: Instant.now().epochSecond,
            ),
        )
        val prior = VaultMappingState.resolve(mapping.extras) ?: VaultMappingState(baselineHash = "")
        idMappingDao.update(
            mapping.copy(
                storageUri = null,
                isDeletedLocally = true,
                extras = VaultMappingState.encode(
                    prior.copy(
                        binnedVaultPath = path,
                        trashedPath = null,
                        userBinned = false,
                        pendingVaultDelete = false,
                    ),
                ),
            ),
        )
    }

    /** Mapped files absent from a complete scan; many at once point at a bad listing, so they are kept. */
    private fun vaultPathKey(path: String, provider: ISyncBackend): String =
        VaultPaths.normalizeNotePath(path).let { if (provider.caseInsensitivePaths) it.lowercase() else it }

    private fun missingMappedCount(mappedPaths: Set<String>, scan: List<SyncNote>, provider: ISyncBackend): Int {
        val scanned = scan.map { vaultPathKey(it.idStr, provider) }.toSet()
        return mappedPaths.count { vaultPathKey(it, provider) !in scanned }
    }

    private suspend fun flushPendingVaultDeletes(syncProvider: ISyncBackend) {
        val pending = idMappingDao.getAllByCloudService(CloudService.FILE_STORAGE).filter { row ->
            val state = VaultMappingState.resolve(row.extras)
            row.isDeletedLocally && state?.pendingVaultDelete == true && state.userBinned
        }
        for (mapping in pending) {
            val note = getById(mapping.localNoteId).first() ?: continue
            if (!note.isDeleted) continue
            val path = VaultMappingState.resolve(mapping.extras)?.binnedVaultPath
                ?: VaultPaths.vaultMappingPath(mapping.storageUri)
                ?: continue
            // Another live note may hold the path by now: its file is not ours to move.
            val pathTaken = idMappingDao.countActiveMappingsAtPath(
                syncProvider.type,
                path,
                mapping.localNoteId,
                syncProvider.caseInsensitivePaths,
            ) > 0
            // Rows from before the bin lived in .trash take the same path: the file moves if it is still there.
            val trashed = try {
                if (pathTaken) null else syncProvider.trashNote(mapping.copy(storageUri = path))
            } catch (e: Exception) {
                Log.w(tag, "flushPendingVaultDeletes: could not move to the bin, will retry: ${e.message}")
                continue
            }
            if (trashed != null) {
                folderHistoryService?.record(
                    op = HistoryOps.NOTE_BIN,
                    id = historyNoteId(mapping),
                    path = path,
                    to = trashed,
                )
            }
            val state = VaultMappingState.resolve(mapping.extras)
                ?: VaultMappingState(baselineHash = "")
            idMappingDao.update(
                mapping.copy(
                    extras = VaultMappingState.encode(
                        state.copy(pendingVaultDelete = false, trashedPath = trashed ?: state.trashedPath),
                    ),
                ),
            )
        }
    }

    override suspend fun syncNotes(): BaseResult = VaultIo.run {
        Log.d(tag, "syncNotes: Starting synchronization")

        val syncProvider = backendProvider.syncProvider.value
        if (syncProvider == null || !backendProvider.isSyncing) {
            Log.i(tag, "syncNotes: Sync not available or disabled")
            return@run Success
        }

        // Check backend availability before proceeding
        when (val status = syncProvider.isAvailable()) {
            is AvailabilityStatus.Available -> {
                Log.d(tag, "syncNotes: Backend is available")
            }

            is AvailabilityStatus.Unavailable -> {
                Log.w(tag, "syncNotes: Backend unavailable - ${status.reason}")
                toaster.showLong(status.reason)
                return@run GenericError(status.reason)
            }
        }

        // Upgraded installs have a folder but no confirmation yet; touching it could import or export
        // against the wrong folder. Until the user confirms, the vault is read and written by nothing.
        val vaultCutoff = if (syncProvider.type == CloudService.FILE_STORAGE) {
            val configuredAt = preferenceRepository.getEncryptedString(PreferenceRepository.VAULT_CONFIGURED_AT)
                .first().toLongOrNull()
            if (configuredAt == null) {
                Log.i(tag, "syncNotes: vault folder not confirmed yet; skipping sync")
                return@run Success
            }
            configuredAt
        } else {
            null
        }

        var pendingRemoteUpdates: List<NoteAction> = emptyList()
        val lockedResult = vaultSyncCoordinator.withVaultLock sync@{
            if (syncProvider.type == CloudService.FILE_STORAGE) {
                retireAndroidTrashNotes()
                flushPendingVaultDeletes(syncProvider)
            }
            val syncMethod = SyncMethod.MAPPING
            try {
                // The "local only" feature is gone; notes that still carry the flag are exported like any other.
                noteDao.clearLocalOnlyFlags()
                val vaultMappings = idMappingDao.getAllByCloudService(CloudService.FILE_STORAGE)
                    .associateBy { it.localNoteId }
                val localNotes = getAll().first()

                // Get all remote notes and convert to metadata
                val activeMappings = if (syncProvider.type == CloudService.FILE_STORAGE) {
                    idMappingDao.getAllByCloudService(CloudService.FILE_STORAGE)
                        .filterNot { it.isDeletedLocally }
                } else {
                    emptyList()
                }
                val allMappedPaths = activeMappings.mapNotNull { VaultPaths.vaultMappingPath(it.storageUri) }.toSet()
                val scanResult = syncProvider.getAll(allMappedPaths)
                // The scan skipped quarantined paths and Room keeps its copy: such a path is neither
                // missing nor importable, so its note stays out of this pass entirely.
                val quarantined = syncProvider.quarantinedPaths.map { vaultPathKey(it, syncProvider) }.toSet()
                val mappedPaths = allMappedPaths.filterNot { vaultPathKey(it, syncProvider) in quarantined }.toSet()
                val quarantinedLocalIds = activeMappings
                    .filter { m ->
                        VaultPaths.vaultMappingPath(m.storageUri)?.let { vaultPathKey(it, syncProvider) in quarantined } == true
                    }
                    .map { it.localNoteId }
                    .toSet()
                val syncedLocalNotes = localNotes.filterNot { it.id in quarantinedLocalIds }
                // An empty listing while notes are mapped is far more likely a provider glitch or an
                // emptied folder than every note being deleted. Fail the scan so no note is binned.
                val emptyScanWhileMapped = syncProvider.type == CloudService.FILE_STORAGE &&
                    scanResult != null && scanResult.isEmpty() && mappedPaths.isNotEmpty()
                val remoteScanSucceeded = scanResult != null && !emptyScanWhileMapped
                if (!remoteScanSucceeded) {
                    // An empty folder can be real when another device binned its last note. Decide without
                    // changing anything first; an unexplained empty listing is a provider glitch.
                    val access = syncProvider as? VaultFileAccess
                    if (scanResult != null && scanResult.isEmpty() && access != null) {
                        // This device's newest actions are only in the outbox until a flush.
                        folderHistoryService?.flush()
                        val liveUids = activeMappings
                            .filterNot { it.localNoteId in quarantinedLocalIds }
                            .map {
                                VaultMappingState.resolve(it.extras)?.noteUid?.takeIf(String::isNotBlank).orEmpty()
                            }
                        if (liveUids.isNotEmpty() && historyApply?.explainsEmptyScan(access, liveUids) == true) {
                            historyApply.apply(access, emptySet())
                            return@sync Success
                        }
                    }
                    val msg = "Vault scan incomplete; local notes were not changed."
                    Log.w(tag, msg)
                    return@sync GenericError(msg)
                }
                val allRemoteNotes = scanResult!!
                val missingCount = if (syncProvider.type == CloudService.FILE_STORAGE) {
                    missingMappedCount(mappedPaths, allRemoteNotes, syncProvider)
                } else {
                    0
                }
                val massMissing = missingCount >= MASS_MISSING_COUNT && missingCount * 2 > mappedPaths.size
                if (massMissing) {
                    Log.w(tag, "Vault scan lost $missingCount of ${mappedPaths.size} mapped files; keeping those notes")
                    toaster.showLong(
                        context.resources.getQuantityString(
                            R.plurals.vault_files_missing_kept,
                            missingCount,
                            missingCount,
                        ),
                    )
                }
                val deferredExportIds = if (syncProvider.type == CloudService.FILE_STORAGE) {
                    val legacyMappings = idMappingDao.getAllByCloudService(CloudService.FILE_STORAGE)
                        .filter { it.storageUri?.contains("://") == true }
                    relinkLegacyContentMappings(legacyMappings, localNotes, allRemoteNotes)
                } else {
                    emptySet()
                }
                if (syncProvider.type == CloudService.FILE_STORAGE) {
                    vaultTreeAccess.warmFrom(preferenceRepository)
                }
                // Other devices' recorded bins, restores and notebook/tag changes land before this pass decides
                // anything, so a note binned elsewhere is not pushed or counted as missing here.
                // This device's own actions are still in the outbox until a flush; the appliers must see them,
                // or an older event from another device would undo what the user just did.
                folderHistoryService?.flush()
                val historyScannedPaths = allRemoteNotes.mapTo(mutableSetOf()) {
                    VaultPaths.normalizeNotePath(it.idStr)
                }
                if (syncProvider.type == CloudService.FILE_STORAGE) {
                    // The scan skips quarantined files while Room keeps their notes: they are present, so a
                    // bin for one waits instead of hiding a note whose file is still in the folder.
                    allMappedPaths
                        .filter { vaultPathKey(it, syncProvider) in quarantined }
                        .forEach { historyScannedPaths += VaultPaths.normalizeNotePath(it) }
                }
                val historyChanged = syncProvider.type == CloudService.FILE_STORAGE &&
                    (syncProvider as? VaultFileAccess)?.let { access ->
                        historyApply?.apply(access, historyScannedPaths)
                    } == true
                val notesForSync = if (historyChanged) {
                    getAll().first().filterNot { it.id in quarantinedLocalIds }
                } else {
                    syncedLocalNotes
                }
                Log.d(
                    tag, "syncNotes: Syncing by $syncMethod. " +
                        "Found ${allRemoteNotes.size} remote notes, and ${localNotes.size} local notes"
                )

                // Use SynchronizeNotes to determine what updates are needed
                val syncResult =
                    synchronizeNotes(
                        notesForSync,
                        allRemoteNotes,
                        service = syncProvider.type,
                        syncMethod,
                        remoteScanSucceeded = remoteScanSucceeded,
                        vaultExportCutoffEpoch = vaultCutoff,
                        suppressMissingBins = massMissing,
                        deferExportLocalIds = deferredExportIds,
                    )
                Log.d(
                    tag, "sync updates: ${syncResult.localUpdates.size} local, " +
                        "${syncResult.remoteUpdates.size} remote, " +
                        "${syncResult.vaultConflicts.size} conflicts"
                )

                // Caller holds [vaultSyncCoordinator.withVaultLock]; do not re-enter.
                syncResult.newMappings.forEach { upsertVaultMapping(it) }
                applyMovedNotebooks(syncResult.newMappings, vaultMappings)
                applyLocalUpdates(syncResult.localUpdates, syncProvider, vaultLockHeld = true)
                handleVaultConflicts(syncResult.vaultConflicts, syncProvider)
                refreshVaultAttachmentIndex(syncProvider)
                val pushingIds = syncResult.remoteUpdates.mapNotNull { action ->
                    when (action) {
                        is NoteAction.Create -> action.note.id
                        is NoteAction.Update -> action.note.id
                        is NoteAction.Delete -> null
                    }
                }.toSet()
                backfillNoteIds(
                    syncProvider,
                    pushingIds,
                    allRemoteNotes.associateBy { vaultPathKey(it.idStr, syncProvider) },
                    // Read after this pass's pulls and imports: the dates written must be the ones the note has now.
                    getAll().first().associateBy { it.id },
                )
                // The locked phase is over for note work: flush what user actions queued while the lock was held.
                folderHistoryService?.flush()
                pendingRemoteUpdates = syncResult.remoteUpdates
                Log.i(tag, "syncNotes: vault-locked phase completed")
            } catch (e: Exception) {
                Log.e(tag, "syncNotes: Synchronization failed: ${e.message}", e)
                return@sync GenericError(e.message ?: "Unknown error")
            }
            Success
        }
        if (lockedResult is Success && pendingRemoteUpdates.isNotEmpty()) {
            applyRemoteUpdates(pendingRemoteUpdates, syncProvider)
        }
        return@run lockedResult
    }

    /**
     * @param vaultLockHeld When true, FILE_STORAGE baseline persistence runs without acquiring
     *   the vault mutex (required when called from [syncNotes]).
     */
    private suspend fun applyLocalUpdates(
        localUpdates: List<NoteAction>,
        syncProvider: ISyncBackend,
        vaultLockHeld: Boolean = false,
    ) {
        for (action in localUpdates) {
            try {
                when (action) {
                    is NoteAction.Create -> {
                        val syncNote = action.remoteNote
                        val imported = if (syncProvider.type == CloudService.FILE_STORAGE) {
                            VaultSyncImport.noteFromRemote(syncNote)
                        } else {
                            syncNote.toLocalNote(defaultPinned = false)
                        }
                        val noteId = insertNote(imported, sync = false)
                        if (syncProvider.type == CloudService.FILE_STORAGE) {
                            vaultPortableMetadata.applyFromRemote(noteId, syncNote)
                            check(vaultLockHeld) {
                                "FILE_STORAGE create mapping requires vault lock (syncNotes)"
                            }
                            persistVaultMappingBaselineForCreate(noteId, syncNote, syncProvider.type)
                        } else {
                            idMappingDao.insert(syncNote.getMapping(noteId, syncProvider.type))
                        }
                    }

                    is NoteAction.Update -> {
                        val current = getById(action.note.id).first() ?: continue
                        if (current.isDeleted && syncProvider.type == CloudService.FILE_STORAGE) {
                            noteDao.update(
                                current.toEntity().copy(isDeleted = false, deletionDate = null),
                            )
                        } else if (current.isDeleted || current.modifiedDate != action.note.modifiedDate) {
                            Log.d(tag, "applyLocalUpdates: stale pull skipped for ${action.note.id}")
                            continue
                        }
                        val note = if (syncProvider.type == CloudService.FILE_STORAGE) {
                            VaultSyncImport.noteFromRemote(action.remoteNote, action.note)
                        } else {
                            val mergedNote = action.remoteNote.updateLocalNote(action.note)
                            if (action.note.isList) {
                                val tasks = mergedNote.mdToTaskList(mergedNote.content)
                                mergedNote.copy(content = "", taskList = tasks, isList = true)
                            } else {
                                mergedNote
                            }
                        }
                        if (syncProvider.type == CloudService.FILE_STORAGE) {
                            updateNote(note, sync = false)
                            vaultPortableMetadata.applyFromRemote(action.note.id, action.remoteNote)
                            check(vaultLockHeld) {
                                "FILE_STORAGE pull mapping requires vault lock (syncNotes)"
                            }
                            val prior = VaultMappingState.resolve(
                                idMappingDao.getByLocalIdAndProvider(action.note.id, syncProvider.type)?.extras,
                            )
                            persistVaultMappingBaseline(
                                action.note.id,
                                action.remoteNote.idStr,
                                action.remoteNote.content.orEmpty(),
                                prior,
                            )
                        } else {
                            idMappingDao.updateNoteExtras(
                                localId = action.note.id,
                                cloudService = syncProvider.type,
                                extras = action.remoteNote.extra,
                            )
                        }
                        if (syncProvider.type != CloudService.FILE_STORAGE) {
                            updateNote(note, sync = false)
                        }
                    }

                    is NoteAction.Delete -> {
                        moveNotesToBin(action.note, sync = false)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "applyLocalUpdates: Failed to apply ${action::class.simpleName}: ${e.message}")
            }
        }
    }

    private suspend fun applyRemoteUpdates(remoteUpdates: List<NoteAction>, syncProvider: ISyncBackend) {
        for (action in remoteUpdates) {
            try {
                when (action) {
                    is NoteAction.Create -> processRemoteActions(
                        action.note.id,
                        Create(action.note, vaultFence = captureVaultFence()),
                    )
                    is NoteAction.Update -> processRemoteActions(
                        action.note.id,
                        Update(action.note, vaultFence = captureVaultFence()),
                    )
                    is NoteAction.Delete -> processRemoteActions(
                        action.note.id,
                        Delete(action.note, vaultFence = captureVaultFence()),
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "applyRemoteUpdates: Failed to apply ${action::class.simpleName}: ${e.message}")
            }
        }
    }

    private suspend fun applyMappingChanges(syncResult: SyncNotesResult, syncProvider: ISyncBackend) {
        syncResult.newMappings.forEach { upsertVaultMapping(it) }
        syncResult.localUpdates.filterIsInstance<NoteAction.Update>().map {
            it.remoteNote.getMapping(it.note.id, syncProvider.type)
        }.forEach { idMappingDao.insert(it) }
        syncResult.remoteUpdates.filterIsInstance<NoteAction.Update>().map {
            it.remoteNote.getMapping(it.note.id, syncProvider.type)
        }.forEach { idMappingDao.insert(it) }
    }

    override suspend fun insertNote(note: Note, sync: Boolean): Long {
        Log.d(tag, "insertNote: Creating note")
        val noteId = noteDao.insert(note.toEntity())
        if (backendProvider.isSyncing && sync) {
            val note1 = note.copy(id = noteId)
            processRemoteActions(note1.id, Create(note1, vaultFence = captureVaultFence()))
        }
        return noteId
    }

    override suspend fun updateNotes(vararg notes: Note, sync: Boolean) = notes.forEach { updateNote(it, sync) }

    override suspend fun pushNotesToVault(vararg notes: Note) {
        notes.forEach { note ->
            if (backendProvider.isSyncing) {
                processRemoteActions(note.id, Update(note, vaultFence = captureVaultFence()))
            }
        }
    }

    private suspend fun updateNote(note: Note, sync: Boolean) {
        Log.d(tag, "updateNote: Updating note ID=${note.id}")
        noteDao.update(note.toEntity())
        if (backendProvider.isSyncing && sync) {
            processRemoteActions(note.id, Update(note, vaultFence = captureVaultFence()))
        }
    }

    override suspend fun moveNotesToBin(vararg notes: Note, sync: Boolean) {
        Log.d(tag, "moveNotesToBin: Moving ${notes.size} notes to bin")
        val entities = notes.map { it.toEntity().copy(isDeleted = true, deletionDate = Instant.now().epochSecond) }
            .toTypedArray<NoteEntity>()

        noteDao.update(*entities)
        reminderDao.deleteIfNoteIdIn(notes.map { it.id })
        notes.forEach { note ->
            idMappingDao.getByLocalIdAndProvider(note.id, CloudService.FILE_STORAGE)?.let { mapping ->
                val vaultPath = VaultPaths.vaultMappingPath(mapping.storageUri)
                if (!vaultPath.isNullOrBlank()) {
                    val prior = VaultMappingState.resolve(mapping.extras)
                    val state = (prior ?: VaultMappingState(baselineHash = "")).copy(
                        binnedVaultPath = vaultPath,
                        vaultAttachmentPaths = prior?.vaultAttachmentPaths ?: emptyList(),
                        pendingVaultDelete = true,
                        userBinned = true,
                        missingScanCount = 0,
                    )
                    idMappingDao.update(
                        mapping.copy(
                            storageUri = null,
                            isDeletedLocally = true,
                            extras = VaultMappingState.encode(state),
                        ),
                    )
                }
            }
            if (sync) {
                processRemoteActions(
                    note.id,
                    Delete(note, removeAssets = false, vaultFence = captureVaultFence()),
                )
            }
        }
    }

    override suspend fun restoreNotes(vararg notes: Note) {
        Log.d(tag, "restoreNotes: Restoring ${notes.size} notes from bin")
        val array = notes
            .map { it.toEntity().copy(isDeleted = false, deletionDate = null) }
            .toTypedArray()
        noteDao.update(*array)
        if (backendProvider.isSyncing) {
            notes.forEach { n ->
                val restored = n.copy(isDeleted = false, deletionDate = null)
                idMappingDao.getByLocalIdAndProvider(n.id, CloudService.FILE_STORAGE)?.let { mapping ->
                    if (mapping.isDeletedLocally) {
                        val state = VaultMappingState.resolve(mapping.extras)
                        val path = state?.binnedVaultPath ?: mapping.storageUri
                        if (!path.isNullOrBlank()) {
                            val norm = VaultPaths.normalizeNotePath(path)
                            val pathTaken = idMappingDao.countActiveMappingsAtPath(
                                CloudService.FILE_STORAGE,
                                norm,
                                n.id,
                                backendProvider.syncProvider.value?.caseInsensitivePaths == true,
                            ) > 0
                            val fromTrash = state?.trashedPath != null && restoreFromTrash(mapping, state, restored, norm)
                            // Android's trash copy is not ours to move back; restore writes a fresh file instead.
                            val androidTrashBin = state?.binnedVaultPath
                                ?.let { VaultPaths.isAndroidTrashFileName(VaultPaths.fileName(it)) } == true
                            if (fromTrash) {
                                // The file is back and the mapping re-activated.
                            } else if (pathTaken || androidTrashBin) {
                                restoreToFreshVaultFile(mapping, restored)
                            } else {
                                idMappingDao.update(
                                    mapping.copy(
                                        storageUri = norm,
                                        isDeletedLocally = false,
                                        extras = VaultMappingState.encode(
                                            (state ?: VaultMappingState(baselineHash = ""))
                                                .copy(binnedVaultPath = null, pendingVaultDelete = false, userBinned = false),
                                        ),
                                    ),
                                )
                                val provider = backendProvider.syncProvider.value
                                provider?.getNote(mapping.copy(storageUri = norm))?.let { file ->
                                    vaultPortableMetadata.applyFromRemote(restored.id, file)
                                }
                            }
                        }
                    }
                }
                val vaultPath = idMappingDao.getByLocalIdAndProvider(n.id, CloudService.FILE_STORAGE)
                    ?.let { m ->
                        VaultPaths.vaultMappingPath(m.storageUri)
                            ?: VaultMappingState.resolve(m.extras)?.binnedVaultPath?.let(VaultPaths::vaultMappingPath)
                    }
                if (!vaultPath.isNullOrBlank()) {
                    val mapping = idMappingDao.getByLocalIdAndProvider(n.id, CloudService.FILE_STORAGE)
                    folderHistoryService?.record(
                        op = HistoryOps.NOTE_RESTORE,
                        id = historyNoteId(mapping),
                        path = vaultPath,
                    )
                }
                val op = if (!vaultPath.isNullOrBlank()) {
                    Update(restored, vaultFence = captureVaultFence())
                } else {
                    Create(restored, vaultFence = captureVaultFence())
                }
                processRemoteActions(n.id, op)
            }
        }
    }

    /**
     * Moves a binned note's file back out of `.trash` and re-activates its mapping, baselined on the moved file.
     * False when the bin file is gone, so the caller falls back to writing a fresh file.
     */
    private suspend fun restoreFromTrash(
        mapping: IdMapping,
        state: VaultMappingState,
        restored: Note,
        originalPath: String,
    ): Boolean {
        val provider = backendProvider.syncProvider.value ?: return false
        val trashedPath = state.trashedPath ?: return false
        return vaultSyncCoordinator.withVaultLock {
            val target = try {
                provider.restoreFromTrash(trashedPath, originalPath, restored.id)
            } catch (e: Exception) {
                Log.w(tag, "restoreFromTrash: move failed, re-creating the file: ${e.message}")
                null
            } ?: return@withVaultLock false
            val moved = provider.getNote(mapping.copy(storageUri = target)) ?: return@withVaultLock false
            // Binning dropped the reminder rows. The file still has them; put them back before the push.
            vaultPortableMetadata.applyFromRemote(restored.id, moved)
            val notebookName = restored.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
            val fresh = VaultMappingState.fromDiskSync(
                moved.content.orEmpty(),
                restored,
                target,
                state.vaultAttachmentPaths,
                vaultSyncCoordinator.currentGeneration(),
                notebookName,
            )
            idMappingDao.update(
                mapping.copy(
                    storageUri = target,
                    isDeletedLocally = false,
                    extras = VaultMappingState.encode(fresh),
                ),
            )
            true
        }
    }

    /**
     * Restores a note whose old path another live note now holds. Writes a new file (never links to an identical
     * one) and baselines the mapping on that file, so the next update is not mistaken for a concurrent edit.
     */
    private suspend fun restoreToFreshVaultFile(mapping: IdMapping, restored: Note) {
        val provider = backendProvider.syncProvider.value ?: return
        vaultSyncCoordinator.withVaultLock {
            val created = provider.createNote(restored, linkExisting = false)
            val gen = vaultSyncCoordinator.currentGeneration()
            val notebookName = restored.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
            val diskAfterCreate = VaultSyncImport.noteFromRemote(created, restored)
            val vaultPaths = VaultSyncImport.attachmentsFromRemote(created)
                .map { it.path }
                .filter { VaultPaths.isVaultRelativeAttachment(it) }
                .ifEmpty { VaultSyncImport.vaultPathsFromNote(diskAfterCreate) }
            val state = VaultMappingState.fromDiskSync(
                created.content.orEmpty(),
                diskAfterCreate,
                created.idStr,
                vaultPaths,
                gen,
                notebookName,
            )
            idMappingDao.update(
                mapping.copy(
                    storageUri = created.idStr,
                    isDeletedLocally = false,
                    extras = VaultMappingState.encode(state.copy(binnedVaultPath = null, pendingVaultDelete = false, userBinned = false)),
                ),
            )
        }
    }

    override suspend fun deleteNotes(vararg notes: Note, sync: Boolean, recordPurge: Boolean) {
        Log.d(tag, "deleteNotes: Permanently deleting ${notes.size} notes")
        val array = notes.map { it.toEntity() }.toTypedArray()
        noteDao.delete(*array)
        if (sync) notes.forEach {
            if (it.isDeleted) {
                val mapping = idMappingDao.getByLocalIdAndProvider(it.id, CloudService.FILE_STORAGE)
                val state = mapping?.let { row -> VaultMappingState.resolve(row.extras) }
                // Only a note the user binned may be purged; a bin caused by a missing file is not a user act,
                // and the retention cleaner's local deletions must not purge the note on other devices.
                if (recordPurge && state?.userBinned == true) {
                    folderHistoryService?.record(
                        op = HistoryOps.NOTE_PURGE,
                        id = historyNoteId(mapping),
                        path = state.binnedVaultPath ?: mapping.storageUri?.let(VaultPaths::vaultMappingPath),
                    )
                }
            }
            processRemoteActions(it.id, Delete(it, vaultFence = captureVaultFence()))
        }
    }

    override suspend fun discardEmptyNotes(): Boolean {
        val notes = noteDao.getAllBlankTitleNotes().first()
            .filter { it.isEmpty() && !editorSessionRegistry.isPending(it.id) }
            .toTypedArray()
        Log.d(tag, "discardEmptyNotes: Found ${notes.size} empty notes to discard")
        deleteNotes(*notes)
        return notes.isNotEmpty()
    }

    override suspend fun permanentlyDeleteNotesInBin() {
        val deleted = noteDao.getDeleted(defaultOf()).first()
        Log.d(tag, "permanentlyDeleteNotesInBin: Permanently deleting ${deleted.size} notes from bin")
        val syncProvider = backendProvider.syncProvider.value
        // Record before the mapping rows go: the event names the note by its front-matter id.
        deleted.forEach { note ->
            val mapping = idMappingDao.getByLocalIdAndProvider(note.id, CloudService.FILE_STORAGE)
            val state = mapping?.let { row -> VaultMappingState.resolve(row.extras) }
            // Only a note the user binned may be purged; a bin caused by a missing file is not a user act.
            if (state?.userBinned == true) {
                folderHistoryService?.record(
                    op = HistoryOps.NOTE_PURGE,
                    id = historyNoteId(mapping),
                    path = state.binnedVaultPath ?: mapping.storageUri?.let(VaultPaths::vaultMappingPath),
                )
            }
        }
        if (syncProvider != null) {
            deleted.forEach { note ->
                val mapping = idMappingDao.getByLocalIdAndProvider(note.id, syncProvider.type) ?: return@forEach
                val state = VaultMappingState.resolve(mapping.extras)
                // A bin caused by a scan that missed the file never proves the user wanted the file gone.
                if (state?.binnedVaultPath != null && !state.userBinned) {
                    idMappingDao.deleteByLocalId(note.id)
                    return@forEach
                }
                val path = state?.binnedVaultPath ?: mapping.storageUri ?: return@forEach
                val others = idMappingDao.countActiveMappingsAtPath(
                    syncProvider.type,
                    path,
                    note.id,
                    syncProvider.caseInsensitivePaths,
                )
                if (others == 0) {
                    syncProvider.deleteNote(mapping.copy(storageUri = path), removeAssets = true)
                } else if (state?.trashedPath != null) {
                    // The original path is another note's now; only the bin copy is this note's.
                    syncProvider.deleteNote(mapping.copy(storageUri = null), removeAssets = true)
                }
                idMappingDao.deleteByLocalId(note.id)
            }
        }
        noteDao.permanentlyDeleteNotesInBin()
    }

    override fun getById(noteId: Long): Flow<Note?> {
        return noteDao.getById(noteId)
    }

    override fun getDeleted(sortMethod: SortMethod): Flow<List<Note>> {
        return noteDao.getDeleted(sortMethod)
    }

    override fun getArchived(sortMethod: SortMethod): Flow<List<Note>> {
        return noteDao.getArchived(sortMethod)
    }

    override fun getNonDeleted(sortMethod: SortMethod): Flow<List<Note>> {
        return noteDao.getNonDeleted(sortMethod)
    }

    override fun getNonDeletedOrArchived(sortMethod: SortMethod): Flow<List<Note>> {
        return noteDao.getNonDeletedOrArchived(sortMethod)
    }

    override fun getAll(sortMethod: SortMethod): Flow<List<Note>> {
        return noteDao.getAll(sortMethod)
    }

    override fun getByNotebook(notebookId: Long, sortMethod: SortMethod): Flow<List<Note>> {
        return noteDao.getByNotebook(notebookId, sortMethod)
    }

    override fun getNonRemoteNotes(provider: CloudService, sortMethod: SortMethod): Flow<List<Note>> {
        return noteDao.getNonRemoteNotes(sortMethod, provider)
    }

    override fun getNotesWithoutNotebook(sortMethod: SortMethod): Flow<List<Note>> {
        return noteDao.getNotesWithoutNotebook(sortMethod)
    }

    override suspend fun getNotesByCloudService(provider: CloudService): Map<IdMapping, Note?> {
        val allNotes = getAll().first().associateBy { it.id }
        val mappings = idMappingDao.getAllByCloudService(provider)
        Log.d(tag, "getNotesByCloudService: Found ${mappings.size} mappings for $provider")
        return mappings.associateWith { allNotes[it.localNoteId] }
    }

    override suspend fun deleteIdMappingsForCloudService(cloudService: CloudService) =
        idMappingDao.deleteAllMappingsFor(cloudService)

    override suspend fun deleteActiveIdMappingsForCloudService(cloudService: CloudService) =
        idMappingDao.deleteActiveMappingsFor(cloudService)

    override suspend fun countIdMappings(cloudService: CloudService): Int =
        idMappingDao.getCountByCloudService(cloudService)

    private suspend fun upsertVaultMapping(mapping: IdMapping) {
        val provider = mapping.provider ?: return
        var toStore = mapping
        if (provider == CloudService.FILE_STORAGE && !mapping.storageUri.isNullOrBlank()) {
            val resolved = VaultMappingState.resolve(mapping.extras)
            if (resolved == null || resolved.baselineHash.isEmpty()) {
                val local = getById(mapping.localNoteId).first()
                val syncProvider = backendProvider.syncProvider.value
                val raw = if (local != null && syncProvider != null) {
                    syncProvider.getNote(mapping)?.content
                } else {
                    null
                }
                if (local != null && !raw.isNullOrBlank()) {
                    val path = VaultPaths.vaultMappingPath(mapping.storageUri)!!
                    val gen = vaultSyncCoordinator.currentGeneration()
                    val notebookName = local.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
                    val state = VaultMappingState.fromDiskSync(
                        raw,
                        local,
                        path,
                        VaultSyncImport.vaultPathsFromNote(local),
                        gen,
                        notebookName,
                    )
                    toStore = mapping.copy(extras = VaultMappingState.encode(state))
                }
            }
        }
        val existing = idMappingDao.getByLocalIdAndProvider(mapping.localNoteId, provider)
        if (existing != null) {
            idMappingDao.update(toStore.copy(mappingId = existing.mappingId))
        } else {
            idMappingDao.insert(toStore)
        }
    }

    private suspend fun handleVaultConflicts(
        conflicts: List<org.qosp.notes.data.sync.core.VaultSyncConflict>,
        syncProvider: ISyncBackend,
    ) {
        val backend = syncProvider as? StorageBackend ?: return
        for (conflict in conflicts) {
            val mapping = idMappingDao.getByLocalIdAndProvider(conflict.local.id, CloudService.FILE_STORAGE)
                ?: continue
            val path = mapping.storageUri.orEmpty()
            val notebookName = conflict.local.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
            val remoteHash = VaultSyncImport.logicalHashFromRemoteFile(
                conflict.remote,
                conflict.local,
                path,
                notebookName,
            )
            val mappingState = VaultMappingState.resolve(mapping.extras)
            val parsed = VaultMappingExtras.parse(mapping.extras)
            val existingRemote = mappingState?.conflictRemoteHash ?: parsed.conflictRemoteHash
            val existingPath = mappingState?.conflictCopyPath ?: parsed.conflictCopyPath
            if (existingRemote == remoteHash && !existingPath.isNullOrEmpty()) {
                continue
            }
            val conflictPath = backend.createConflictCopy(conflict.remote) ?: continue
            val baseline = mappingState?.baselineHash ?: parsed.baselineHash ?: remoteHash
            val next = (mappingState ?: VaultMappingState(baselineHash = baseline)).copy(
                conflictRemoteHash = remoteHash,
                conflictCopyPath = conflictPath,
            )
            idMappingDao.update(
                mapping.copy(extras = VaultMappingState.encode(next)),
            )
            toaster.showLong("Edit conflict for ${conflict.local.title}; a copy was saved in the vault.")
        }
    }

    override suspend fun getVaultRelativePath(noteId: Long): String? {
        return idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE)?.storageUri
    }

    /**
     * Relinks old `content://` mappings to vault files by content. Returns the notes whose mapping matched no
     * file and was dropped; they must not be exported in the same sync, because the folder's files may still
     * hold their content under a name this pass could not tie to them.
     */
    private suspend fun relinkLegacyContentMappings(
        legacyMappings: List<IdMapping>,
        localNotes: List<Note>,
        remotes: List<SyncNote>,
    ): Set<Long> {
        if (legacyMappings.isEmpty()) return emptySet()
        // Files already bound to another note are never candidates: a body-only match must not create a second
        // mapping onto the same file.
        val claimedPaths = idMappingDao.getAllByCloudService(CloudService.FILE_STORAGE)
            .filterNot { it.isDeletedLocally }
            .mapNotNull { VaultPaths.vaultMappingPath(it.storageUri)?.let(VaultPaths::normalizeNotePath) }
            .toMutableSet()
        val unmatched = legacyMappings.toMutableList()

        class Candidate(val remote: SyncNote, val desired: String, val titleMatch: Boolean, val contentMatch: Boolean)

        fun candidates(local: Note): List<Candidate> = remotes.mapNotNull { sync ->
            val desired = VaultPaths.normalizeNotePath(sync.idStr)
            if (desired in claimedPaths) return@mapNotNull null
            val doc = VaultNoteDocument.parse(sync.content.orEmpty())
            val title = doc.frontMatter.title?.takeIf { it.isNotBlank() }
                ?: VaultPaths.noteBaseName(sync.idStr)
            val bodyMatch = VaultContentHash.ofText(local.content) == VaultContentHash.ofText(doc.body)
            val hashMatch = VaultSyncImport.canonicalFileHash(local, desired, null, null) ==
                VaultSyncImport.logicalHashFromRemoteFile(sync, local, desired, null)
            val titleMatch = title.equals(local.title, ignoreCase = true)
            // Without a title match, a blank body would link any blank file.
            Candidate(sync, desired, titleMatch, (bodyMatch || hashMatch) && (titleMatch || local.content.isNotBlank()))
        }.filter { it.contentMatch }

        // Title + body first, so an exact match is not taken by another note that only shares the body.
        val passes = listOf<(Candidate) -> Boolean>({ it.titleMatch }, { true })
        for (matchesPass in passes) {
            for (legacy in legacyMappings) {
                if (legacy !in unmatched) continue
                val local = localNotes.find { it.id == legacy.localNoteId } ?: continue
                val remote = candidates(local).firstOrNull(matchesPass)?.remote ?: continue
                val desired = VaultPaths.normalizeNotePath(remote.idStr)
                val notebookName = local.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
                val imported = VaultSyncImport.noteFromRemote(remote, local)
                val gen = vaultSyncCoordinator.currentGeneration()
                val state = VaultMappingState.fromDiskSync(
                    remote.content.orEmpty(),
                    imported,
                    desired,
                    VaultSyncImport.vaultPathsFromNote(imported),
                    gen,
                    notebookName,
                )
                val updated = legacy.copy(
                    storageUri = desired,
                    extras = VaultMappingState.encode(state),
                    isDeletedLocally = false,
                )
                idMappingDao.update(updated)
                claimedPaths.add(desired)
                unmatched.remove(legacy)
                Log.i(tag, "Relinked legacy mapping for note ${local.id} → $desired")
            }
        }
        // Rows that matched no file are dropped, not bound to a guessed file. A mapping left with no remote would
        // be read as a remote delete, and the note would be binned. The note exports on the next sync instead.
        val deferred = mutableSetOf<Long>()
        for (stuck in unmatched) {
            if (localNotes.none { it.id == stuck.localNoteId }) continue
            Log.w(tag, "Dropped unmatched legacy content URI mapping for note ${stuck.localNoteId}")
            idMappingDao.deleteByLocalId(stuck.localNoteId)
            deferred.add(stuck.localNoteId)
        }
        return deferred
    }

    private suspend fun refreshVaultAttachmentIndex(syncProvider: ISyncBackend) {
        if (syncProvider.type != CloudService.FILE_STORAGE) return
        val mappings = idMappingDao.getAllByCloudService(CloudService.FILE_STORAGE)
            .filterNot { it.isDeletedLocally }
        for (mapping in mappings) {
            val path = mapping.storageUri ?: continue
            val remote = syncProvider.getNote(mapping) ?: continue
            val local = getById(mapping.localNoteId).first() ?: continue
            val notebookName = local.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
            val mappingState = VaultMappingState.resolve(mapping.extras)
            var dirty = VaultSyncImport.isLocallyDirty(local, path, mappingState, notebookName)
            var noteForBaseline = local
            if (dirty) {
                val baselinePaths = mappingState?.vaultAttachmentPathSet.orEmpty()
                val merged = VaultSyncImport.attachmentsAfterDirtyRefresh(local.attachments, remote, baselinePaths)
                if (merged != local.attachments) {
                    updateNote(local.copy(attachments = merged), sync = false)
                }
            } else {
                val imported = VaultSyncImport.noteFromRemote(remote, local)
                val bodyOrAttachmentsChanged = imported.content != local.content ||
                    imported.attachments != local.attachments
                val metadataChanged = imported.isPinned != local.isPinned ||
                    imported.isArchived != local.isArchived ||
                    imported.isHidden != local.isHidden ||
                    imported.color != local.color ||
                    imported.isList != local.isList ||
                    imported.title != local.title
                if (bodyOrAttachmentsChanged || metadataChanged) {
                    updateNote(imported, sync = false)
                    vaultPortableMetadata.applyFromRemote(mapping.localNoteId, remote)
                    noteForBaseline = getById(mapping.localNoteId).first() ?: imported
                    dirty = VaultSyncImport.isLocallyDirty(noteForBaseline, path, mappingState, notebookName)
                } else if (remoteAttachmentsDifferFromDisk(local, remote)) {
                    vaultPortableMetadata.applyFromRemote(mapping.localNoteId, remote)
                    noteForBaseline = getById(mapping.localNoteId).first() ?: local
                    dirty = VaultSyncImport.isLocallyDirty(noteForBaseline, path, mappingState, notebookName)
                }
            }
            if (!dirty) {
                val gen = vaultSyncCoordinator.currentGeneration()
                val noteEntity = noteForBaseline
                val paths = VaultSyncImport.vaultPathsFromNote(noteEntity)
                val raw = remote.content.orEmpty()
                val nbName = noteEntity.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
                val state = VaultMappingState.fromDiskSync(
                    raw,
                    noteEntity,
                    path,
                    paths,
                    gen,
                    nbName,
                    mappingState,
                )
                val encoded = VaultMappingState.encode(state)
                if (encoded != mapping.extras) {
                    idMappingDao.update(mapping.copy(extras = encoded))
                }
            }
        }
    }

    private fun remoteAttachmentsDifferFromDisk(
        local: Note,
        remote: org.qosp.notes.data.sync.core.SyncNote,
    ): Boolean {
        return VaultSyncImport.attachmentsFromRemote(remote) != local.attachments
    }
}
