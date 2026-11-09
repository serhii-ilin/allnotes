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

    /**
     * Same bytes as [org.qosp.notes.data.sync.fs.StorageBackend] uses when writing a note file.
     */
    fun canonicalFileHash(
        note: Note,
        relativePath: String,
        assignedNotebookName: String?,
        existingDiskRaw: String?,
    ): String {
        val doc = VaultNoteDocument.fromNote(
            note,
            relativePath,
            assignedNotebookName = assignedNotebookName,
            exportNotebookExplicitly = true,
        )
        val merged = VaultNoteDocument.mergePreservedFromDisk(existingDiskRaw, doc)
        val vaultOnly = note.attachments.filter { VaultPaths.isVaultRelativeAttachment(it.path) }
        val body = VaultAttachments.mergeAttachmentLinks(merged.body, relativePath, vaultOnly)
        return VaultContentHash.ofText(merged.copy(body = body).renderFullText())
    }

    fun hasPendingLocalMedia(note: Note): Boolean =
        note.attachments.any { !VaultPaths.isVaultRelativeAttachment(it.path) }

    fun isLocallyDirty(
        note: Note,
        relativePath: String,
        mappingExtras: String?,
        assignedNotebookName: String?,
        existingDiskRaw: String?,
    ): Boolean {
        val baseline = VaultMappingExtras.baselineHash(mappingExtras)
        if (baseline.isNullOrEmpty()) return false
        if (hasPendingLocalMedia(note)) return true
        val localHash = canonicalFileHash(note, relativePath, assignedNotebookName, existingDiskRaw)
        return localHash != baseline
    }

    /**
     * While the note is dirty, merge remote vault links with local rows without dropping private media
     * or re-adding vault paths the user removed locally.
     */
    fun mergeAttachmentsWhenDirty(
        local: List<Attachment>,
        remote: List<Attachment>,
    ): List<Attachment> {
        val byPath = LinkedHashMap<String, Attachment>()
        remote.filter { VaultPaths.isVaultRelativeAttachment(it.path) }.forEach { byPath[it.path] = it }
        local.filter { VaultPaths.isVaultRelativeAttachment(it.path) }.forEach { byPath[it.path] = it }
        local.filter { !VaultPaths.isVaultRelativeAttachment(it.path) }.forEach { byPath[it.path] = it }
        return byPath.values.toList()
    }

    fun mergeNoteAfterPush(local: Note, pushedFromDisk: Note): Note = pushedFromDisk.copy(
        attachments = mergeAttachmentsWhenDirty(local.attachments, pushedFromDisk.attachments),
        reminders = local.reminders,
        isCompactPreview = local.isCompactPreview,
        screenAlwaysOn = local.screenAlwaysOn,
        isLocalOnly = local.isLocalOnly,
        notebookId = local.notebookId,
    )
}
