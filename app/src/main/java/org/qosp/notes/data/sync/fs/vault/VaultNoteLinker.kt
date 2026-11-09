package org.qosp.notes.data.sync.fs.vault

import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.SyncNote

/**
 * Links Room notes and vault files by path and canonical body hash (never two locals → one file).
 */
object VaultNoteLinker {

    fun linkRemoteImportToLocal(
        remote: SyncNote,
        locals: List<Note>,
        mappingsByLocalId: Map<Long, IdMapping>,
        claimedLocalIds: MutableSet<Long>,
    ): Note? {
        val path = VaultPaths.normalizeNotePath(remote.idStr)
        val remoteNote = VaultSyncImport.noteFromRemote(remote)
        val notebookName = remoteNote.notebookId?.let { null }
        val remoteHash = VaultSyncImport.logicalHashFromRemoteFile(remote, remoteNote, path, notebookName)
        val pathMatch = locals.firstOrNull { local ->
            if (local.id in claimedLocalIds) return@firstOrNull false
            if (mappingsByLocalId[local.id] != null) return@firstOrNull false
            val desired = VaultPaths.titleToNotePath(local.title, local.isMarkdownEnabled)
            VaultPaths.normalizeNotePath(desired) == path
        }
        if (pathMatch != null) {
            val localHash = VaultSyncImport.canonicalFileHash(pathMatch, path, notebookName, null)
            if (localHash == remoteHash) {
                claimedLocalIds.add(pathMatch.id)
                return pathMatch
            }
        }
        val hashMatch = locals.firstOrNull { local ->
            if (local.id in claimedLocalIds) return@firstOrNull false
            if (mappingsByLocalId[local.id] != null) return@firstOrNull false
            val desired = VaultPaths.titleToNotePath(local.title, local.isMarkdownEnabled)
            val localHash = VaultSyncImport.canonicalFileHash(local, desired, notebookName, null)
            localHash == remoteHash
        }
        if (hashMatch != null) {
            claimedLocalIds.add(hashMatch.id)
        }
        return hashMatch
    }

    fun linkLocalExportToRemote(
        local: Note,
        remotes: List<SyncNote>,
        mappedPaths: Set<String>,
        claimedPaths: MutableSet<String>,
        notebookName: String?,
    ): SyncNote? {
        val desired = VaultPaths.titleToNotePath(local.title, local.isMarkdownEnabled)
        val desiredNorm = VaultPaths.normalizeNotePath(desired)
        if (desiredNorm in mappedPaths || desiredNorm in claimedPaths) return null
        val localHash = VaultSyncImport.canonicalFileHash(local, desiredNorm, notebookName, null)
        return remotes.firstOrNull { remote ->
            val path = VaultPaths.normalizeNotePath(remote.idStr)
            if (path in mappedPaths || path in claimedPaths) return@firstOrNull false
            val remoteHash = VaultSyncImport.logicalHashFromRemoteFile(remote, local, path, notebookName)
            when {
                path == desiredNorm && remoteHash == localHash -> true
                remoteHash == localHash &&
                    VaultPaths.noteBaseName(path) == VaultPaths.noteBaseName(desiredNorm) -> true
                else -> false
            }
        }?.also { claimedPaths.add(VaultPaths.normalizeNotePath(it.idStr)) }
    }
}
