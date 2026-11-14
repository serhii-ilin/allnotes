package org.qosp.notes.data.sync.fs.vault

import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Note

object VaultSyncImport {
    fun noteFromRemote(remote: org.qosp.notes.data.sync.core.SyncNote, existing: Note? = null): Note {
        val raw = remote.content.orEmpty()
        val doc = VaultNoteDocument.parse(raw)
        val attachments = VaultAttachments.parseFromBody(doc.body, remote.idStr)
        return VaultNoteDocument.toNote(
            doc,
            remote.idStr,
            remote.lastModified,
            attachments,
            existing,
        ).let { imported ->
            existing?.let {
                imported.copy(
                    id = it.id,
                    reminders = it.reminders,
                    isLocalOnly = it.isLocalOnly,
                    notebookId = it.notebookId,
                    isCompactPreview = it.isCompactPreview,
                    screenAlwaysOn = it.screenAlwaysOn,
                )
            } ?: imported
        }
    }

    fun attachmentsFromRemote(remote: org.qosp.notes.data.sync.core.SyncNote): List<Attachment> {
        val doc = VaultNoteDocument.parse(remote.content.orEmpty())
        return VaultAttachments.parseFromBody(doc.body, remote.idStr)
    }

    fun vaultPathsFromNote(note: Note): List<String> =
        note.attachments
            .filter { VaultPaths.isVaultRelativeAttachment(it.path) }
            .map { it.path }
            .distinct()
            .sorted()

    /**
     * Same bytes as [org.qosp.notes.data.sync.fs.StorageBackend] would write, using the
     * **baseline** preserved YAML snapshot — not the current foreign file content.
     */
    fun canonicalFileHash(
        note: Note,
        relativePath: String,
        assignedNotebookName: String?,
        preservedYamlBaseline: String?,
    ): String {
        var doc = VaultNoteDocument.fromNote(
            note,
            relativePath,
            assignedNotebookName = assignedNotebookName,
            exportNotebookExplicitly = true,
        )
        if (!preservedYamlBaseline.isNullOrBlank()) {
            doc = doc.copy(frontMatter = doc.frontMatter.copy(preservedYaml = preservedYamlBaseline))
        }
        val vaultOnly = note.attachments.filter { VaultPaths.isVaultRelativeAttachment(it.path) }
        val body = VaultAttachments.mergeAttachmentLinks(doc.body, relativePath, vaultOnly)
        return VaultContentHash.ofText(doc.copy(body = body).renderFullText())
    }

    fun hasPendingLocalMedia(note: Note): Boolean =
        note.attachments.any { !VaultPaths.isVaultRelativeAttachment(it.path) }

    fun isAttachmentMembershipDirty(note: Note, mappingState: VaultMappingState?): Boolean {
        if (mappingState == null) return false
        val baseline = mappingState.vaultAttachmentPathSet
        if (baseline.isEmpty() && note.attachments.none { VaultPaths.isVaultRelativeAttachment(it.path) }) {
            return false
        }
        return vaultPathsFromNote(note).toSet() != baseline
    }

    fun removedVaultAttachmentPaths(note: Note, mappingState: VaultMappingState?): Set<String> {
        val baseline = mappingState?.vaultAttachmentPathSet.orEmpty()
        val current = vaultPathsFromNote(note).toSet()
        return baseline - current
    }

    fun isLocallyDirty(
        note: Note,
        relativePath: String,
        mappingState: VaultMappingState?,
        assignedNotebookName: String?,
    ): Boolean {
        val baseline = mappingState?.baselineHash ?: VaultMappingExtras.baselineHash(null)
        if (baseline.isNullOrEmpty()) return false
        if (hasPendingLocalMedia(note)) return true
        if (isAttachmentMembershipDirty(note, mappingState)) return true
        val preserved = mappingState?.preservedYaml
        val localHash = canonicalFileHash(note, relativePath, assignedNotebookName, preserved)
        return localHash != baseline
    }

    /** Logical hash of on-disk bytes (uses file YAML + body, not Room-only fields like merged tags). */
    fun logicalHashFromRemoteFile(
        remote: org.qosp.notes.data.sync.core.SyncNote,
        localNote: Note,
        relativePath: String,
        assignedNotebookName: String?,
    ): String {
        val raw = remote.content.orEmpty()
        val doc = VaultNoteDocument.parse(raw)
        val fm = doc.frontMatter
        val imported = noteFromRemote(remote, localNote)
        val fileTags = fm.tags?.map { org.qosp.notes.data.model.Tag(id = 0L, name = it) }
        // What the file holds, not Room's reminders: a reminder changed in the file must change this hash.
        val fileReminders = fm.reminders.orEmpty().map {
            org.qosp.notes.data.model.Reminder(name = it.name, noteId = localNote.id, date = it.date)
        }
        val snapshot = imported.copy(tags = fileTags ?: imported.tags, reminders = fileReminders)
        val notebookName = fm.notebook?.takeIf { it.isNotBlank() } ?: assignedNotebookName
        return canonicalFileHash(snapshot, relativePath, notebookName, fm.preservedYaml)
    }

    /**
     * While dirty: local attachment membership wins; only late vault files not in baseline may be added.
     */
    fun attachmentsAfterDirtyRefresh(
        local: List<Attachment>,
        remote: org.qosp.notes.data.sync.core.SyncNote,
        baselineVaultPaths: Set<String>,
    ): List<Attachment> {
        val result = local.toMutableList()
        val localPaths = local.map { it.path }.toSet()
        for (remoteAtt in attachmentsFromRemote(remote)) {
            if (!VaultPaths.isVaultRelativeAttachment(remoteAtt.path)) continue
            if (remoteAtt.path in localPaths) continue
            if (remoteAtt.path in baselineVaultPaths) continue
            result.add(remoteAtt)
        }
        return result
    }

    fun mergeNoteAfterPush(
        local: Note,
        diskSnapshot: Note,
        mappingState: VaultMappingState?,
        relativePath: String,
        assignedNotebookName: String?,
    ): Note {
        val dirty = isLocallyDirty(local, relativePath, mappingState, assignedNotebookName)
        return if (dirty) {
            val upgraded = VaultNoteRelocation.materializePathsFromPush(local.attachments, diskSnapshot.attachments)
            local.copy(
                attachments = VaultNoteRelocation.applyBaselineVaultPathsIfPending(
                    upgraded,
                    mappingState,
                    diskSnapshot.attachments,
                ),
            )
        } else {
            diskSnapshot.copy(
                reminders = local.reminders,
                isCompactPreview = local.isCompactPreview,
                screenAlwaysOn = local.screenAlwaysOn,
                isLocalOnly = local.isLocalOnly,
                notebookId = local.notebookId,
            )
        }
    }

}
