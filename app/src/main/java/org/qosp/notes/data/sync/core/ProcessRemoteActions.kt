package org.qosp.notes.data.sync.core

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.qosp.notes.Config
import kotlinx.coroutines.flow.firstOrNull
import org.qosp.notes.data.dao.IdMappingDao
import org.qosp.notes.data.dao.NoteDao
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.RemoteOperation.Create
import org.qosp.notes.data.sync.core.RemoteOperation.Delete
import org.qosp.notes.data.sync.core.RemoteOperation.Update
import kotlinx.coroutines.flow.first
import org.qosp.notes.data.sync.fs.vault.VaultFileOpFence
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultNoteRelocation
import org.qosp.notes.data.sync.fs.vault.VaultPaths
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.data.sync.fs.vault.VaultSyncImport
import org.qosp.notes.data.sync.getMapping
import org.qosp.notes.di.SyncScope
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.ui.utils.Toaster
import java.util.concurrent.ConcurrentHashMap

class ProcessRemoteActions(
    private val syncingScope: SyncScope,
    private val backendProvider: BackendProvider,
    private val idMappingDao: IdMappingDao,
    private val noteDao: NoteDao,
    private val toaster: Toaster,
    private val vaultSyncCoordinator: VaultSyncCoordinator,
    private val notebookDao: NotebookDao,
    private val preferenceRepository: PreferenceRepository,
) {
    private val tag = "ProcessRemoteActions"

    // Thread-safe map to store the latest operation for each noteId
    private val operationQueue = ConcurrentHashMap<Long, RemoteOperation>()

    // Map to track pending remote operation jobs by noteId
    private val pendingJobs = ConcurrentHashMap<Long, Job>()

    /**
     * Process a remote operation for a note.
     * This method ensures that only the latest operation for each noteId is processed,
     * and earlier operations are ignored.
     */
    operator fun invoke(noteId: Long, operation: RemoteOperation) {
        if (!backendProvider.isSyncing) return // Not syncing now

        // Store the operation in the queue, replacing any existing operation for this noteId
        operationQueue[noteId] = operation

        if (pendingJobs[noteId] != null) return // If there's an existing job, let it finish.
        val job = syncingScope.launch { // Create a new job to process the operation
            try {
                // Add debounce delay to allow for batching of rapid operations
                delay(Config.RemoteUpdateDebounceTime)

                // Check backend availability before processing
                val syncProvider = backendProvider.syncProvider.value ?: return@launch
                when (val status = syncProvider.isAvailable()) {
                    is AvailabilityStatus.Available -> { /* Continue */
                    }

                    is AvailabilityStatus.Unavailable -> {
                        Log.w(tag, "Backend unavailable for remote operation: ${status.reason}")
                        toaster.showLong(status.reason)
                        return@launch
                    }
                }

                while (true) {
                    val action = operationQueue.remove(noteId) ?: break
                    val runOp: suspend () -> Unit = runOp@{
                        val provider = backendProvider.syncProvider.value ?: return@runOp
                        if (provider.type == CloudService.FILE_STORAGE &&
                            !validateVaultFence(action.vaultFence, provider.type)
                        ) {
                            Log.w(tag, "Stale vault push aborted for note $noteId")
                            return@runOp
                        }
                        when (action) {
                            is Create -> {
                                Log.d(tag, "insertRemoteNote: for note ID=$noteId")
                                insertRemote(provider, action.note)
                            }

                            is Update -> {
                                Log.d(tag, "updateRemoteNote: for note ID=$noteId")
                                updateRemote(action.note, provider)
                            }

                            is Delete -> {
                                Log.d(tag, "deleteRemoteNotes: for $noteId")
                                deleteRemoteNotes(action.note, provider, action.removeAssets)
                            }
                        }
                    }
                    val provider = backendProvider.syncProvider.value
                    if (provider?.type == CloudService.FILE_STORAGE) {
                        vaultSyncCoordinator.withVaultLock { runOp() }
                    } else {
                        runOp()
                    }
                }
            } catch (_: CancellationException) {
                // Job was canceled, which is expected when a newer operation comes in
            } catch (e: Exception) {
                Log.e(tag, "processRemoteOperation: Error processing operation: ${e.message}", e)
            } finally {
                // Remove the job from the maps when done
                pendingJobs.remove(noteId)
                Log.d(tag, "Completed remote actions for note $noteId")
            }
        }
        // Store the job in the map
        pendingJobs[noteId] = job
    }

    private suspend fun deleteRemoteNotes(
        note: Note,
        syncProvider: ISyncBackend,
        removeAssets: Boolean,
    ) = try {
        idMappingDao.getByLocalIdAndProvider(note.id, syncProvider.type)?.let { mapping ->
            val binned = VaultMappingState.resolve(mapping.extras)?.binnedVaultPath
            val vaultPath = VaultPaths.vaultMappingPath(mapping.storageUri)
                ?: binned?.let(VaultPaths::vaultMappingPath)?.takeIf { binnedPath ->
                    idMappingDao.countActiveMappingsAtPath(
                        syncProvider.type,
                        binnedPath,
                        note.id,
                    ) == 0
                }
            val priorState = VaultMappingState.resolve(mapping.extras)
            if (syncProvider.type == CloudService.FILE_STORAGE && priorState?.binnedVaultPath != null &&
                !priorState.userBinned && removeAssets
            ) {
                // Binned because a scan missed the file, not because the user deleted it: drop only the Room rows.
                idMappingDao.deleteByLocalId(note.id)
                return@let
            }
            val mappingForDelete = if (vaultPath != null && mapping.storageUri != vaultPath) {
                mapping.copy(storageUri = vaultPath)
            } else {
                mapping
            }
            syncProvider.deleteNote(mapping = mappingForDelete, removeAssets = removeAssets)
            if (removeAssets) {
                idMappingDao.deleteByLocalId(note.id)
            } else if (syncProvider.type == CloudService.FILE_STORAGE && !vaultPath.isNullOrBlank()) {
                val prior = VaultMappingState.resolve(mapping.extras)
                val state = (prior ?: VaultMappingState(baselineHash = "")).copy(
                    binnedVaultPath = vaultPath,
                    vaultAttachmentPaths = prior?.vaultAttachmentPaths ?: emptyList(),
                )
                idMappingDao.update(
                    mapping.copy(
                        storageUri = null,
                        isDeletedLocally = true,
                        extras = VaultMappingState.encode(state.copy(pendingVaultDelete = false)),
                    ),
                )
            } else if (syncProvider.type == CloudService.FILE_STORAGE) {
                val prior = VaultMappingState.resolve(mapping.extras)
                if (prior?.pendingVaultDelete == true) {
                    idMappingDao.update(
                        mapping.copy(
                            extras = VaultMappingState.encode(prior.copy(pendingVaultDelete = false)),
                        ),
                    )
                }
            }
        }
    } catch (e: Exception) {
        Log.e(tag, "processRemoteOperation: Failed to delete notes: ${e.message}", e)
    }

    private suspend fun upgradeAttachmentPathsAfterPush(noteId: Long, syncProvider: ISyncBackend) {
        if (syncProvider.type != CloudService.FILE_STORAGE) return
        val mapping = idMappingDao.getByLocalIdAndProvider(noteId, syncProvider.type) ?: return
        val live = noteDao.getById(noteId).first() ?: return
        val remote = syncProvider.getNote(mapping) ?: return
        val diskSnapshot = VaultSyncImport.attachmentsFromRemote(remote)
        val mappingState = VaultMappingState.resolve(mapping.extras)
        var upgraded = VaultNoteRelocation.materializePathsFromPush(live.attachments, diskSnapshot)
        upgraded = VaultNoteRelocation.applyBaselineVaultPathsIfPending(upgraded, mappingState, diskSnapshot)
        if (upgraded != live.attachments) {
            noteDao.update(live.copy(attachments = upgraded).toEntity())
        }
    }

    private suspend fun validateVaultFence(fence: VaultFileOpFence?, provider: CloudService): Boolean {
        if (provider == CloudService.FILE_STORAGE && fence == null) {
            Log.w(tag, "Vault operation rejected: missing fence")
            return false
        }
        if (fence == null) return true
        val currentUri = preferenceRepository.getEncryptedString(PreferenceRepository.STORAGE_LOCATION).first()
        val currentGen = vaultSyncCoordinator.currentGeneration()
        return !fence.isStale(currentGen, currentUri)
    }

    private suspend fun insertRemote(syncProvider: ISyncBackend, note: Note) {
        try {
            val liveBefore = noteDao.getById(note.id).first()
            if (liveBefore?.isDeleted == true) {
                Log.d(tag, "insertRemote skipped: note ${note.id} is binned")
                return
            }
            val created = syncProvider.createNote(note).let { linked ->
                // Linking reuses an identical file, but never one that already belongs to another note: two notes
                // on one file overwrite each other.
                val taken = syncProvider.type == CloudService.FILE_STORAGE &&
                    idMappingDao.countActiveMappingsAtPath(syncProvider.type, linked.idStr, note.id) > 0
                if (taken) syncProvider.createNote(note, linkExisting = false) else linked
            }
        val diskAfterCreate = VaultSyncImport.noteFromRemote(created, note)
        val base = created.getMapping(note.id, syncProvider.type)
        val toInsert = if (syncProvider.type == CloudService.FILE_STORAGE) {
            val gen = vaultSyncCoordinator.currentGeneration()
            val notebookName = note.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
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
            base.copy(extras = VaultMappingState.encode(state))
        } else {
            base
        }
        val existing = idMappingDao.getByLocalIdAndProvider(note.id, syncProvider.type)
        if (existing != null) {
            idMappingDao.update(
                toInsert.copy(
                    mappingId = existing.mappingId,
                    isDeletedLocally = false,
                    storageUri = toInsert.storageUri ?: existing.storageUri,
                ),
            )
        } else {
            idMappingDao.insert(toInsert)
        }
        upgradeAttachmentPathsAfterPush(note.id, syncProvider)
        persistBaselineAfterPush(note.id, syncProvider)
        noteDao.updateLastModified(note.id, created.lastModified)
        } catch (e: Exception) {
            Log.e(tag, "processRemoteOperation: Failed to create note ID=${note.id}: ${e.message}", e)
            toaster.showShort(e.message ?: "Failed to create note")
        }
    }

    private suspend fun persistBaselineAfterPush(noteId: Long, syncProvider: ISyncBackend) {
        if (syncProvider.type != CloudService.FILE_STORAGE) return
        val mapping = idMappingDao.getByLocalIdAndProvider(noteId, syncProvider.type) ?: return
        val path = VaultPaths.vaultMappingPath(mapping.storageUri) ?: return
        val remote = syncProvider.getNote(mapping) ?: return
        val live = noteDao.getById(noteId).first() ?: return
        val notebookName = live.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
        val prior = VaultMappingState.resolve(mapping.extras)
        val state = VaultMappingState.fromDiskSync(
            remote.content.orEmpty(),
            live,
            path,
            VaultSyncImport.vaultPathsFromNote(live),
            vaultSyncCoordinator.currentGeneration(),
            notebookName,
            prior?.copy(conflictRemoteHash = null, conflictCopyPath = null),
        )
        idMappingDao.update(mapping.copy(extras = VaultMappingState.encode(state)))
    }

    private suspend fun updateRemote(note: Note, syncProvider: ISyncBackend) = try {
        idMappingDao.getByLocalIdAndProvider(note.id, syncProvider.type)?.let { mapping ->
            val path = VaultPaths.vaultMappingPath(mapping.storageUri).orEmpty()
            val mappingState = VaultMappingState.resolve(mapping.extras)
            val updatedMapping = syncProvider.updateNote(note, mapping)
            idMappingDao.update(updatedMapping)
            upgradeAttachmentPathsAfterPush(note.id, syncProvider)
            persistBaselineAfterPush(note.id, syncProvider)
        }
    } catch (e: Exception) {
        Log.e(tag, "processRemoteOperation: Failed to update note ID=${note.id}: ${e.message}", e)
    }

}
