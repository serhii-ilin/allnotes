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
import org.qosp.notes.data.sync.fs.vault.VaultPortableMetadata
import org.qosp.notes.data.sync.fs.vault.VaultNoteDocument
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
) : NoteRepository {

    private val tag = NoteRepositoryImpl::class.java.simpleName

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

    /** Mapped files absent from a complete scan; many at once point at a bad listing, so they are kept. */
    private fun missingMappedCount(mappedPaths: Set<String>, scan: List<SyncNote>): Int {
        val scanned = scan.map { VaultPaths.normalizeNotePath(it.idStr) }.toSet()
        return mappedPaths.count { VaultPaths.normalizeNotePath(it) !in scanned }
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
            val deleteMapping = mapping.copy(storageUri = path)
            val deleted = syncProvider.deleteNote(deleteMapping, removeAssets = false)
            if (deleted) {
                val state = VaultMappingState.resolve(mapping.extras)
                    ?: VaultMappingState(baselineHash = "")
                idMappingDao.update(
                    mapping.copy(
                        extras = VaultMappingState.encode(state.copy(pendingVaultDelete = false)),
                    ),
                )
            }
        }
    }

    private suspend fun cleanMappingsForLocalNotes(vararg notes: Note) {
        val n = notes.filter { it.isLocalOnly }
        Log.d(tag, "cleanMappingsForLocalNotes: Cleaning ${n.size} local-only notes from ${notes.size} total")
        idMappingDao.setNotesToBeDeleted(*n.map { it.id }.toLongArray())
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
                flushPendingVaultDeletes(syncProvider)
            }
            val syncMethod = SyncMethod.MAPPING
            try {
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
                val mappedPaths = activeMappings.mapNotNull { VaultPaths.vaultMappingPath(it.storageUri) }.toSet()
                val scanResult = syncProvider.getAll(mappedPaths)
                // An empty listing while notes are mapped is far more likely a provider glitch or an
                // emptied folder than every note being deleted. Fail the scan so no note is binned.
                val emptyScanWhileMapped = syncProvider.type == CloudService.FILE_STORAGE &&
                    scanResult != null && scanResult.isEmpty() && mappedPaths.isNotEmpty()
                val remoteScanSucceeded = scanResult != null && !emptyScanWhileMapped
                if (!remoteScanSucceeded) {
                    val msg = "Vault scan incomplete; local notes were not changed."
                    Log.w(tag, msg)
                    return@sync GenericError(msg)
                }
                val allRemoteNotes = scanResult!!
                val missingCount = if (syncProvider.type == CloudService.FILE_STORAGE) {
                    missingMappedCount(mappedPaths, allRemoteNotes)
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
                Log.d(
                    tag, "syncNotes: Syncing by $syncMethod. " +
                        "Found ${allRemoteNotes.size} remote notes, and ${localNotes.size} local notes"
                )

                // Use SynchronizeNotes to determine what updates are needed
                val syncResult =
                    synchronizeNotes(
                        localNotes,
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
                applyLocalUpdates(syncResult.localUpdates, syncProvider, vaultLockHeld = true)
                handleVaultConflicts(syncResult.vaultConflicts, syncProvider)
                refreshVaultAttachmentIndex(syncProvider)
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
        Log.d(tag, "insertNote: Creating note, isLocalOnly=${note.isLocalOnly}")
        val noteId = noteDao.insert(note.toEntity())
        if (note.isLocalOnly.not() && backendProvider.isSyncing && sync) {
            val note1 = note.copy(id = noteId)
            processRemoteActions(note1.id, Create(note1, vaultFence = captureVaultFence()))
        }
        return noteId
    }

    override suspend fun updateNotes(vararg notes: Note, sync: Boolean) = notes.forEach { updateNote(it, sync) }

    override suspend fun pushNotesToVault(vararg notes: Note) {
        notes.filterNot { it.isLocalOnly }.forEach { note ->
            if (backendProvider.isSyncing) {
                processRemoteActions(note.id, Update(note, vaultFence = captureVaultFence()))
            }
        }
    }

    private suspend fun updateNote(note: Note, sync: Boolean) {
        Log.d(tag, "updateNote: Updating note ID=${note.id}")
        noteDao.update(note.toEntity())
        if (note.isLocalOnly.not() && backendProvider.isSyncing && sync) {
            processRemoteActions(note.id, Update(note, vaultFence = captureVaultFence()))
        }
    }

    override suspend fun moveNotesToBin(vararg notes: Note, sync: Boolean) {
        Log.d(tag, "moveNotesToBin: Moving ${notes.size} notes to bin")
        val entities = notes.map { it.toEntity().copy(isDeleted = true, deletionDate = Instant.now().epochSecond) }
            .toTypedArray<NoteEntity>()

        noteDao.update(*entities)
        reminderDao.deleteIfNoteIdIn(notes.map { it.id })
        cleanMappingsForLocalNotes(*notes)
        notes.filterNot { it.isLocalOnly }.forEach { note ->
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
        cleanMappingsForLocalNotes(*notes)
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
                            ) > 0
                            if (pathTaken) {
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
                            }
                        }
                    }
                }
                val vaultPath = idMappingDao.getByLocalIdAndProvider(n.id, CloudService.FILE_STORAGE)
                    ?.let { m ->
                        VaultPaths.vaultMappingPath(m.storageUri)
                            ?: VaultMappingState.resolve(m.extras)?.binnedVaultPath?.let(VaultPaths::vaultMappingPath)
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

    override suspend fun deleteNotes(vararg notes: Note, sync: Boolean) {
        Log.d(tag, "deleteNotes: Permanently deleting ${notes.size} notes")
        val array = notes.map { it.toEntity() }.toTypedArray()
        noteDao.delete(*array)
        if (sync) notes.filterNot { it.isLocalOnly }.forEach {
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
                )
                if (others == 0) {
                    syncProvider.deleteNote(mapping.copy(storageUri = path), removeAssets = true)
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
