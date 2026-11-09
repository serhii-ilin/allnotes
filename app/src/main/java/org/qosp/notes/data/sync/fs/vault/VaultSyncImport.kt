package org.qosp.notes.data.sync.fs.vault

import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.SyncNote

object VaultSyncImport {
    fun noteFromRemote(remote: SyncNote, existing: Note? = null): Note {
        val raw = remote.content.orEmpty()
        val doc = VaultNoteDocument.parse(raw)
        val attachments = VaultAttachments.parseFromBody(doc.body, remote.idStr)
        val imported = VaultNoteDocument.toNote(doc, remote.idStr, remote.lastModified, attachments)
        return existing?.let {
            imported.copy(
                id = it.id,
                notebookId = it.notebookId,
                reminders = it.reminders,
                tags = it.tags,
                isLocalOnly = it.isLocalOnly,
            )
        } ?: imported
    }

    fun localContentHash(note: Note, relativePath: String): String {
        val doc = VaultNoteDocument.fromNote(note, relativePath)
        val body = VaultAttachments.mergeAttachmentLinks(doc.body, relativePath, note.attachments.filter {
            VaultPaths.isVaultRelativeAttachment(it.path)
        })
        return VaultContentHash.ofText(doc.copy(body = body).renderFullText())
    }
}
