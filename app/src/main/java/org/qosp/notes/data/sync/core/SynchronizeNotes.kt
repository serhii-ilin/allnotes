package org.qosp.notes.data.sync.core

import android.util.Log
import kotlinx.coroutines.flow.firstOrNull
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.IdMappingRepository
import org.qosp.notes.data.sync.fs.vault.VaultMappingExtras
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultNoteLinker
import org.qosp.notes.data.sync.fs.vault.VaultPaths
import org.qosp.notes.data.sync.fs.vault.VaultSyncImport
import org.qosp.notes.data.sync.getMapping
import org.qosp.notes.preferences.CloudService
import kotlin.math.abs

class SynchronizeNotes(
    private val idMappingRepository: IdMappingRepository,
    private val notebookDao: NotebookDao,
) {
    private val tag = SynchronizeNotes::class.java.simpleName

    private fun logDebug(message: String) = Log.d(tag, message)

    suspend operator fun invoke(
        localNotes: List<Note>,
        remoteNotes: List<SyncNote>,
        service: CloudService,
        method: SyncMethod = SyncMethod.MAPPING,
        remoteScanSucceeded: Boolean = true,
        vaultExportCutoffEpoch: Long? = null,
    ): SyncNotesResult {
        logDebug("SynchronizeNotes: Starting synchronization with method: $method")

        val localUpdates = mutableListOf<NoteAction>()
        val remoteUpdates = mutableListOf<NoteAction>()
        val mappingUpdates = mutableListOf<IdMapping>()
        val vaultConflicts = mutableListOf<VaultSyncConflict>()

        when (method) {
            SyncMethod.MAPPING -> {
                // Fetch all mappings for this service at once to minimize idMapping queries (requirement #1)
                val allMappings = idMappingRepository.getAllByProvider(service)

                // Create maps for faster lookups
                val localNotesMap = localNotes.associateBy { it.id }
                val remoteNotesMap = remoteNotes.associateBy { it.idStr }

                // Maps for local note ID to remote note ID and vice versa
                val localToRemoteMap = allMappings.associateBy { it.localNoteId }
                val remoteToLocalMap = allMappings
                    .filterNot { it.isDeletedLocally }
                    .mapNotNull { m ->
                        VaultPaths.vaultMappingPath(m.storageUri)?.let { it to m }
                    }
                    .toMap()
                val claimedLocalIds = mutableSetOf<Long>()
                val claimedRemotePaths = allMappings
                    .filterNot { it.isDeletedLocally }
                    .mapNotNull { it.storageUri }
                    .map { VaultPaths.normalizeNotePath(it) }
                    .toMutableSet()
                val sharedClaimedPaths = claimedRemotePaths

                // Process local notes
                for (localNote in localNotes) {
                    if (localNote.isDeleted) continue
                    val mapping = localToRemoteMap[localNote.id]

                    if (mapping != null) {
                        if (mapping.isDeletedLocally) continue
                        // Local note has a mapping to a remote note
                        val remoteNote = mapping.storageUri?.let { remoteNotesMap[it] }

                        if (remoteNote != null) {
                            val vaultPath = mapping.storageUri.orEmpty()
                            if (service == CloudService.FILE_STORAGE) {
                                val mappingState = VaultMappingState.resolve(mapping.extras)
                                val parsedExtras = VaultMappingExtras.parse(mapping.extras)
                                val baseline = mappingState?.baselineHash ?: parsedExtras.baselineHash.orEmpty()
                                val notebookName = localNote.notebookId?.let {
                                    notebookDao.getById(it).firstOrNull()?.name
                                }
                                val preservedBaseline = mappingState?.preservedYaml
                                val remoteHash = VaultSyncImport.logicalHashFromRemoteFile(
                                    remoteNote,
                                    localNote,
                                    vaultPath,
                                    notebookName,
                                )
                                val localHash = VaultSyncImport.canonicalFileHash(
                                    localNote,
                                    vaultPath,
                                    notebookName,
                                    preservedBaseline,
                                )
                                val dirty = VaultSyncImport.isLocallyDirty(
                                    localNote,
                                    vaultPath,
                                    mappingState,
                                    notebookName,
                                )
                                val divergent = baseline.isNotEmpty() &&
                                    remoteHash.isNotEmpty() &&
                                    localHash != baseline &&
                                    remoteHash != baseline &&
                                    remoteHash != localHash
                                if (divergent) {
                                    val alreadyRecorded = mappingState?.conflictRemoteHash == remoteHash ||
                                        parsedExtras.conflictRemoteHash == remoteHash
                                    if (alreadyRecorded) {
                                        logDebug("Vault conflict already recorded for ${localNote.title}")
                                    } else {
                                        logDebug("Vault conflict: ${localNote.title} at $vaultPath")
                                        vaultConflicts.add(VaultSyncConflict(localNote, remoteNote))
                                    }
                                } else if (dirty) {
                                    val membershipDirty = VaultSyncImport.isAttachmentMembershipDirty(
                                        localNote,
                                        mappingState,
                                    )
                                    val contentDirty = localHash != baseline
                                    when {
                                        contentDirty && localNote.modifiedDate >= remoteNote.lastModified -> {
                                            logDebug("Vault push while dirty: ${localNote.title}")
                                            remoteUpdates.add(NoteAction.Update(localNote, remoteNote))
                                        }
                                        membershipDirty && !contentDirty -> when {
                                            remoteHash == baseline -> {
                                                logDebug("Vault push attachment membership: ${localNote.title}")
                                                remoteUpdates.add(NoteAction.Update(localNote, remoteNote))
                                            }
                                            localHash == baseline -> {
                                                logDebug(
                                                    "Vault pull: remote changed while attachment-only dirty " +
                                                        localNote.title,
                                                )
                                                localUpdates.add(NoteAction.Update(localNote, remoteNote))
                                            }
                                        }
                                    }
                                } else {
                                    val hashChanged = baseline.isNotEmpty() &&
                                        remoteHash.isNotEmpty() && remoteHash != baseline
                                    val mtimeChanged = abs(localNote.modifiedDate - remoteNote.lastModified) > 1
                                    when {
                                        hashChanged && (mtimeChanged || localHash == baseline) -> {
                                            logDebug("Vault pull (hash): ${remoteNote.title}")
                                            localUpdates.add(NoteAction.Update(localNote, remoteNote))
                                        }
                                        localHash != baseline && localNote.modifiedDate > remoteNote.lastModified -> {
                                            logDebug("Vault push (hash): ${localNote.title}")
                                            remoteUpdates.add(NoteAction.Update(localNote, remoteNote))
                                        }
                                        mtimeChanged && localNote.modifiedDate > remoteNote.lastModified -> {
                                            remoteUpdates.add(NoteAction.Update(localNote, remoteNote))
                                        }
                                        mtimeChanged && localNote.modifiedDate < remoteNote.lastModified -> {
                                            localUpdates.add(NoteAction.Update(localNote, remoteNote))
                                        }
                                    }
                                }
                            }
                        } else if (service == CloudService.FILE_STORAGE && !remoteScanSucceeded) {
                            logDebug("Skipping delete; vault scan incomplete for ${localNote.title}")
                        } else if (
                            service == CloudService.FILE_STORAGE &&
                            mapping.storageUri?.contains("://") == true
                        ) {
                            logDebug("Awaiting legacy relink for ${localNote.title}")
                        } else {
                            val syncNote = SyncNote(
                                title = localNote.title,
                                lastModified = localNote.modifiedDate,
                                content = localNote.content,
                                idStr = "",
                                id = 0,
                            )
                            logDebug("May be deleted remotely: $syncNote")
                            localUpdates.add(NoteAction.Delete(localNote, syncNote))
                            if (service == CloudService.FILE_STORAGE) {
                                val vaultPath = VaultPaths.vaultMappingPath(mapping.storageUri)
                                if (!vaultPath.isNullOrBlank()) {
                                    val prior = VaultMappingState.resolve(mapping.extras)
                                    val state = (prior ?: VaultMappingState(baselineHash = "")).copy(
                                        binnedVaultPath = vaultPath,
                                        vaultAttachmentPaths = prior?.vaultAttachmentPaths ?: emptyList(),
                                    )
                                    mappingUpdates.add(
                                        mapping.copy(
                                            storageUri = null,
                                            isDeletedLocally = true,
                                            extras = VaultMappingState.encode(state),
                                        ),
                                    )
                                }
                            }
                        }

                    } else if (service == CloudService.FILE_STORAGE && vaultExportCutoffEpoch == null) {
                        logDebug("Skip vault export until folder is configured: ${localNote.title}")
                    } else if (service == CloudService.FILE_STORAGE) {
                        val notebookName = localNote.notebookId?.let {
                            notebookDao.getById(it).firstOrNull()?.name
                        }
                        val linked = VaultNoteLinker.linkLocalExportToRemote(
                            localNote,
                            remoteNotes,
                            remoteToLocalMap.keys,
                            claimedRemotePaths,
                            notebookName,
                        )
                        if (linked != null) {
                            logDebug("Link local export to existing file: ${linked.idStr}")
                            mappingUpdates.add(linked.getMapping(localNote.id, service))
                        } else {
                            val remoteNoteMetaData = SyncNote(
                                id = 0,
                                idStr = "",
                                content = localNote.content,
                                title = localNote.title,
                                lastModified = localNote.modifiedDate,
                            )
                            logDebug("Export or new vault-backed local note: ${localNote.title}")
                            remoteUpdates.add(NoteAction.Create(localNote, remoteNoteMetaData))
                        }
                    }
                }

                // Process remote notes
                for (remoteNote in remoteNotes) {
                    val mapping = remoteToLocalMap[remoteNote.idStr]
                    if (mapping != null) {
                        val localNote = localNotesMap[mapping.localNoteId]
                        val binnedPath = VaultMappingState.resolve(mapping.extras)?.binnedVaultPath
                        when {
                            localNote != null && localNote.isDeleted -> {
                                logDebug("Vault file returned; restoring binned note: ${remoteNote.title}")
                                localUpdates.add(NoteAction.Update(localNote, remoteNote))
                                if (mapping.isDeletedLocally && binnedPath == remoteNote.idStr) {
                                    mappingUpdates.add(
                                        mapping.copy(
                                            isDeletedLocally = false,
                                            storageUri = remoteNote.idStr,
                                            extras = VaultMappingState.encode(
                                                (VaultMappingState.resolve(mapping.extras)
                                                    ?: VaultMappingState(baselineHash = ""))
                                                    .copy(binnedVaultPath = null),
                                            ),
                                        ),
                                    )
                                }
                            }
                            localNote == null -> {
                                val dummyLocalNote = Note(id = mapping.localNoteId)
                                logDebug("Local note deleted. Deleting remotely: ${remoteNote.title}")
                                remoteUpdates.add(NoteAction.Delete(dummyLocalNote, remoteNote))
                            }
                            mapping.isDeletedLocally &&
                                binnedPath == VaultPaths.normalizeNotePath(remoteNote.idStr) -> {
                                logDebug("Re-link binned mapping to returned file: ${remoteNote.title}")
                                mappingUpdates.add(
                                    mapping.copy(
                                        isDeletedLocally = false,
                                        storageUri = remoteNote.idStr,
                                        extras = VaultMappingState.encode(
                                            (VaultMappingState.resolve(mapping.extras)
                                                ?: VaultMappingState(baselineHash = ""))
                                                .copy(binnedVaultPath = null),
                                        ),
                                    ),
                                )
                            }
                            mapping.isDeletedLocally -> {
                                logDebug("Remote note ignored for binned local: ${remoteNote.title}")
                            }
                        }
                    } else {
                        val linked = VaultNoteLinker.linkRemoteImportToLocal(
                            remoteNote,
                            localNotes.filterNot { it.isDeleted },
                            localToRemoteMap,
                            claimedLocalIds,
                            sharedClaimedPaths,
                        )
                        if (linked != null) {
                            logDebug("Link remote import to local: ${linked.title}")
                            mappingUpdates.add(remoteNote.getMapping(linked.id, service))
                        } else {
                            val newLocalNote = Note(
                                title = remoteNote.title, modifiedDate = remoteNote.lastModified
                            )
                            logDebug("New Remote note: ${remoteNote.title}")
                            localUpdates.add(NoteAction.Create(newLocalNote, remoteNote))
                        }
                    }
                }
            }

            SyncMethod.TITLE -> {
                // Create maps for faster lookups based on title
                val localNotesMap = localNotes.associateBy { it.title }
                val remoteNotesMap = remoteNotes.associateBy { it.title }

                // Process local notes
                for (localNote in localNotes) {
                    val remoteNote = remoteNotesMap[localNote.title]

                    if (remoteNote != null) {
                        // Both local and remote notes exist with the same title, compare last modified times
                        if (abs(localNote.modifiedDate - remoteNote.lastModified) <= 1) {
                            // About the same note, just create mapping
                            mappingUpdates.add(remoteNote.getMapping(localNote.id, service))
                        } else if (localNote.modifiedDate > remoteNote.lastModified) {
                            // Local note is newer, update remote
                            logDebug("Local note (${localNote.title}) is newer ${localNote.modifiedDate}, ${remoteNote.lastModified}")
                            remoteUpdates.add(NoteAction.Update(localNote, remoteNote))
                        } else if (localNote.modifiedDate < remoteNote.lastModified) {
                            // Remote note is newer, update local
                            logDebug("Remote note(${remoteNote.title}) is newer ${remoteNote.lastModified}, ${localNote.modifiedDate}")
                            localUpdates.add(NoteAction.Update(localNote, remoteNote))
                        }
                        // If equal, no action needed
                    } else {
                        // No remote note with this title, create a new remote note
                        val syncNote = SyncNote(
                            id = 0, // Empty ID for new remote notes
                            idStr = "", content = localNote.content,
                            title = localNote.title, lastModified = localNote.modifiedDate
                        )
                        logDebug("New local note: ${localNote.title}")
                        remoteUpdates.add(NoteAction.Create(localNote, syncNote))
                    }
                }

                // Process remote notes
                for (remoteNote in remoteNotes) {
                    val localNote = localNotesMap[remoteNote.title]

                    if (localNote == null) {
                        // No local note with this title, create a new local note
                        val newLocalNote = Note(
                            title = remoteNote.title, modifiedDate = remoteNote.lastModified
                        )
                        logDebug("New Remote note: ${remoteNote.title}")
                        localUpdates.add(NoteAction.Create(newLocalNote, remoteNote))
                    }
                    // If the local note exists, it was already handled in the local notes loop
                }
            }
        }
        return SyncNotesResult(localUpdates, remoteUpdates, mappingUpdates, vaultConflicts)
    }
}

sealed interface NoteAction {
    data class Create(val note: Note, val remoteNote: SyncNote) : NoteAction
    data class Update(val note: Note, val remoteNote: SyncNote) : NoteAction
    data class Delete(val note: Note, val remoteNote: SyncNote) : NoteAction
}

data class VaultSyncConflict(val local: Note, val remote: SyncNote)

data class SyncNotesResult(
    val localUpdates: List<NoteAction>,
    val remoteUpdates: List<NoteAction>,
    val newMappings: List<IdMapping> = emptyList(),
    val vaultConflicts: List<VaultSyncConflict> = emptyList(),
)
