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
        claimedRemotePaths: MutableSet<String>,
    ): Note? {
        val path = VaultPaths.normalizeNotePath(remote.idStr)
        val remoteNote = VaultSyncImport.noteFromRemote(remote)
        val parsed = VaultNoteDocument.parse(remote.content.orEmpty())
        val notebookName = parsed.frontMatter.notebook?.takeIf { it.isNotBlank() }
        val remoteHash = VaultSyncImport.logicalHashFromRemoteFile(remote, remoteNote, path, notebookName)
        val remoteBodyHash = VaultContentHash.ofText(parsed.body.trimEnd())
        val remoteTitle = parsed.frontMatter.title?.takeIf { it.isNotBlank() }
            ?: VaultPaths.noteBaseName(path)
        val pathMatch = locals.firstOrNull { local ->
            if (local.id in claimedLocalIds) return@firstOrNull false
            if (mappingsByLocalId[local.id] != null) return@firstOrNull false
            val desired = VaultPaths.titleToNotePath(local.title, local.isMarkdownEnabled)
            VaultPaths.normalizeNotePath(desired) == path
        }
        if (pathMatch != null &&
            contentMatchesLocal(pathMatch, path, remoteTitle, remoteBodyHash, notebookName, remoteHash)
        ) {
            claimedLocalIds.add(pathMatch.id)
            claimedRemotePaths.add(path)
            return pathMatch
        }
        val hashMatch = locals.firstOrNull { local ->
            if (local.id in claimedLocalIds) return@firstOrNull false
            if (mappingsByLocalId[local.id] != null) return@firstOrNull false
            val desired = VaultPaths.titleToNotePath(local.title, local.isMarkdownEnabled)
            contentMatchesLocal(local, path, remoteTitle, remoteBodyHash, notebookName, remoteHash) ||
                contentMatchesLocal(local, desired, remoteTitle, remoteBodyHash, notebookName, remoteHash)
        }
        if (hashMatch != null) {
            claimedLocalIds.add(hashMatch.id)
            claimedRemotePaths.add(path)
        }
        return hashMatch
    }

    /**
     * [remoteTitle] is the remote file's YAML title, or its own base name when it has none. The candidate
     * path ([pathForHash]) only shapes the canonical hash; it never supplies the title, so a title-less file
     * cannot match a note with a different title. The hash fallback is gated by the same title check, since
     * a canonical hash of a title-less file does not include the title.
     */
    private fun contentMatchesLocal(
        local: Note,
        pathForHash: String,
        remoteTitle: String,
        remoteBodyHash: String,
        notebookName: String?,
        remoteHash: String,
    ): Boolean {
        if (!local.title.equals(remoteTitle, ignoreCase = true)) return false
        val localBody = if (local.isList) local.taskListToMd() else local.content
        val localBodyHash = VaultContentHash.ofText(localBody.trimEnd())
        if (localBodyHash == remoteBodyHash) return true
        val localHash = VaultSyncImport.canonicalFileHash(local, pathForHash, notebookName, null)
        return localHash == remoteHash
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
        val localBodyHash = VaultContentHash.ofText(
            (if (local.isList) local.taskListToMd() else local.content).trimEnd(),
        )
        return remotes.firstOrNull { remote ->
            val path = VaultPaths.normalizeNotePath(remote.idStr)
            if (path in mappedPaths || path in claimedPaths) return@firstOrNull false
            val parsed = VaultNoteDocument.parse(remote.content.orEmpty())
            val remoteTitle = parsed.frontMatter.title?.takeIf { it.isNotBlank() }
                ?: VaultPaths.noteBaseName(path)
            val remoteBodyHash = VaultContentHash.ofText(parsed.body.trimEnd())
            val localBody = if (local.isList) local.taskListToMd() else local.content
            val localBodyHash = VaultContentHash.ofText(localBody.trimEnd())
            val titleMatch = if (parsed.frontMatter.title?.isNotBlank() == true) {
                local.title.equals(remoteTitle, ignoreCase = true)
            } else {
                local.title.equals(VaultPaths.noteBaseName(path), ignoreCase = true)
            }
            titleMatch && localBodyHash == remoteBodyHash
        }?.also { claimedPaths.add(VaultPaths.normalizeNotePath(it.idStr)) }
    }
}
