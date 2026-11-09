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
            var last: String = ""
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
                            !validateVaultFence(action.vaultFence)
                        ) {
                            Log.w(tag, "Stale vault push aborted for note $noteId")
                            return@runOp
                        }
                        when (action) {
                            is Create -> {
                                last = action.note.content.takeLast(4)
                                Log.d(tag, "insertRemoteNote: for note ID=$noteId ending ...$last")
                                insertRemote(provider, action.note)
                            }

                            is Update -> {
                                last = action.note.content.takeLast(4)
                                Log.d(tag, "updateRemoteNote: for note ID=$noteId ending ...$last")
                                updateRemote(action.note, provider)
                            }

                            is Delete -> {
                                last = action.note.content.takeLast(4)
                                Log.d(tag, "deleteRemoteNotes: for $noteId ending ...$last")
                                deleteRemoteNotes(action.note, provider)
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
                Log.d(tag, "Completed ...$last")
            }
        }
        // Store the job in the map
        pendingJobs[noteId] = job
    }

    private suspend fun deleteRemoteNotes(note: Note, syncProvider: ISyncBackend) = try {
        idMappingDao.getByLocalIdAndProvider(note.id, syncProvider.type)?.let { mapping ->
            syncProvider.deleteNote(mapping = mapping)
            idMappingDao.deleteByLocalId(note.id)
        }
    } catch (e: Exception) {
        Log.e(tag, "processRemoteOperation: Failed to delete notes: ${e.message}", e)
    }

    private suspend fun validateVaultFence(fence: VaultFileOpFence?): Boolean {
        if (fence == null) return true
        val currentUri = preferenceRepository.getEncryptedString(PreferenceRepository.STORAGE_LOCATION).first()
        val currentGen = vaultSyncCoordinator.currentGeneration()
        return !fence.isStale(currentGen, currentUri)
    }

    private suspend fun insertRemote(syncProvider: ISyncBackend, note: Note) = try {
        val created = syncProvider.createNote(note)
        val base = created.getMapping(note.id, syncProvider.type)
        val toInsert = if (syncProvider.type == CloudService.FILE_STORAGE) {
            val gen = vaultSyncCoordinator.currentGeneration()
            val notebookName = note.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
            val state = VaultMappingState.fromDiskSync(
                created.content.orEmpty(),
                note,
                created.idStr,
                VaultSyncImport.vaultPathsFromNote(note),
                gen,
                notebookName,
            )
            base.copy(extras = VaultMappingState.encode(state))
        } else {
            base
        }
        idMappingDao.insert(toInsert)
        noteDao.updateLastModified(note.id, created.lastModified)
        applyVaultPushResult(note, syncProvider)
    } catch (e: Exception) {
        Log.e(tag, "processRemoteOperation: Failed to create note ID=${note.id}: ${e.message}", e)
        toaster.showShort(e.message ?: "Failed to create note")
    }

    private suspend fun updateRemote(note: Note, syncProvider: ISyncBackend) = try {
        idMappingDao.getByLocalIdAndProvider(note.id, syncProvider.type)?.let {
            val updatedMapping = syncProvider.updateNote(note, it)
            idMappingDao.update(updatedMapping)
            applyVaultPushResult(note, syncProvider)
        }
    } catch (e: Exception) {
        Log.e(tag, "processRemoteOperation: Failed to update note ID=${note.id}: ${e.message}", e)
    }

    private suspend fun applyVaultPushResult(note: Note, syncProvider: ISyncBackend) {
        if (syncProvider.type != CloudService.FILE_STORAGE) return
        val mapping = idMappingDao.getByLocalIdAndProvider(note.id, syncProvider.type) ?: return
        val mappingState = VaultMappingState.resolve(mapping.extras)
        if (mappingState != null && vaultSyncCoordinator.isStaleMapping(mappingState.vaultGeneration)) {
            Log.w(tag, "Skipping push Room apply: stale vault generation for note ${note.id}")
            return
        }
        val remote = syncProvider.getNote(mapping) ?: return
        val path = mapping.storageUri.orEmpty()
        val notebookName = note.notebookId?.let { notebookDao.getById(it).firstOrNull()?.name }
        val diskSnapshot = VaultSyncImport.noteFromRemote(remote, note)
        val patched = VaultSyncImport.mergeNoteAfterPush(
            note,
            diskSnapshot,
            mappingState,
            path,
            notebookName,
        )
        noteDao.update(patched.toEntity())
        noteDao.updateLastModified(note.id, remote.lastModified)
        val gen = vaultSyncCoordinator.currentGeneration()
        val state = VaultMappingState.fromDiskSync(
            remote.content.orEmpty(),
            patched,
            path,
            VaultSyncImport.vaultPathsFromNote(patched),
            gen,
            notebookName,
            mappingState,
        )
        idMappingDao.update(
            mapping.copy(
                storageUri = remote.idStr,
                extras = VaultMappingState.encode(state),
            ),
        )
    }
}
