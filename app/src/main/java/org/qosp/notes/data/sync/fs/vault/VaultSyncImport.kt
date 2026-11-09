package org.qosp.notes.data.sync.fs.vault

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
                )
            } ?: imported
        }
    }

    fun attachmentsFromRemote(remote: org.qosp.notes.data.sync.core.SyncNote): List<org.qosp.notes.data.model.Attachment> {
        val doc = VaultNoteDocument.parse(remote.content.orEmpty())
        return VaultAttachments.parseFromBody(doc.body, remote.idStr)
    }

    fun localContentHash(note: Note, relativePath: String): String {
        val doc = VaultNoteDocument.fromNote(note, relativePath)
        val body = VaultAttachments.mergeAttachmentLinks(doc.body, relativePath, note.attachments.filter {
            VaultPaths.isVaultRelativeAttachment(it.path)
        })
        return VaultContentHash.ofText(doc.copy(body = body).renderFullText())
    }

    fun isLocallyDirty(note: Note, relativePath: String, baselineHash: String?): Boolean {
        if (baselineHash.isNullOrEmpty()) return false
        return localContentHash(note, relativePath) != baselineHash
    }
}
