package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.SyncNote
import org.junit.Test

class VaultSyncImportTest {
    private fun remoteBody(path: String, body: String, extra: String? = null): SyncNote {
        val yaml = """
            |---
            |title: "t"
            |pinned: false
            |archived: false
            |hidden: false
            |isList: false
            |markdown: true
            |color: "Default"
            |tags: []
            |notebook: ""
            |---
            |$body
        """.trimMargin()
        return SyncNote(
            id = 0,
            idStr = path,
            title = "t",
            content = yaml,
            lastModified = 1L,
            extra = extra ?: VaultContentHash.ofText(yaml),
        )
    }

    @Test
    fun isLocallyDirty_detectsUnsavedBodyEdit() {
        val path = "Work/Note.md"
        val note = Note(title = "t", content = "local edit")
        val baseline = VaultSyncImport.canonicalFileHash(
            note.copy(content = "remote body"),
            path,
            null,
            remoteBody(path, "remote body").content,
        )
        assertTrue(
            VaultSyncImport.isLocallyDirty(note, path, baseline, null, remoteBody(path, "remote body").content),
        )
    }

    @Test
    fun isLocallyDirty_falseWhenMatchesBaseline() {
        val path = "Work/Note.md"
        val note = Note(title = "t", content = "same")
        val disk = remoteBody(path, "same")
        val baseline = VaultSyncImport.canonicalFileHash(note, path, null, disk.content)
        assertFalse(VaultSyncImport.isLocallyDirty(note, path, baseline, null, disk.content))
    }

    @Test
    fun pendingPrivateAttachment_isDirtyEvenWhenBodyMatches() {
        val path = "Work/Note.md"
        val note = Note(
            title = "t",
            content = "body",
            attachments = listOf(Attachment(Attachment.Type.IMAGE, "content://media/1", "a")),
        )
        val disk = remoteBody(path, "body")
        val baseline = VaultSyncImport.canonicalFileHash(note.copy(attachments = emptyList()), path, null, disk.content)
        assertTrue(VaultSyncImport.isLocallyDirty(note, path, baseline, null, disk.content))
    }

    @Test
    fun canonicalHash_matchesExportWithNotebookAndPreservedYaml() {
        val path = "Work/Note.md"
        val note = Note(title = "t", content = "Body", notebookId = 1L)
        val diskRaw = """
            |---
            |aliases:
            |  - one
            |---
            |Old
        """.trimMargin()
        val withNotebook = VaultSyncImport.canonicalFileHash(note, path, "Journal", diskRaw)
        val withoutNotebook = VaultSyncImport.canonicalFileHash(note, path, null, diskRaw)
        assertTrue(withNotebook != withoutNotebook)
        val rendered = VaultNoteDocument.fromNote(note, path, "Journal", exportNotebookExplicitly = true)
            .let { VaultNoteDocument.mergePreservedFromDisk(diskRaw, it) }
            .copy(body = "Body")
            .renderFullText()
        assertEquals(VaultContentHash.ofText(rendered), withNotebook)
    }

    @Test
    fun mergeAttachmentsWhenDirty_keepsPrivateAndRemoteVault() {
        val local = listOf(
            Attachment(Attachment.Type.IMAGE, "content://x", "pending"),
            Attachment(Attachment.Type.IMAGE, "vaultrel:Work/a.jpg", "a"),
        )
        val remote = listOf(Attachment(Attachment.Type.IMAGE, "vaultrel:Work/b.jpg", "b"))
        val merged = VaultSyncImport.mergeAttachmentsWhenDirty(local, remote)
        assertEquals(2, merged.count { VaultPaths.isVaultRelativeAttachment(it.path) })
        assertTrue(merged.any { it.path == "content://x" })
    }

    @Test
    fun attachmentsFromRemote_parsedFromBody() {
        val path = "Work/Note.md"
        val body = "![x](Work/Note/x.jpg)"
        val remote = remoteBody(path, body)
        val attachments = VaultSyncImport.attachmentsFromRemote(remote)
        assertEquals(1, attachments.size)
        assertEquals(Attachment.Type.IMAGE, attachments[0].type)
    }

    @Test
    fun noteFromRemote_preservesLocalOnlyFields() {
        val path = "Work/Note.md"
        val remote = remoteBody(path, "body")
        val existing = Note(
            title = "t",
            content = "body",
            isCompactPreview = true,
            screenAlwaysOn = true,
            notebookId = 9L,
        )
        val imported = VaultSyncImport.noteFromRemote(remote, existing)
        assertTrue(imported.isCompactPreview)
        assertTrue(imported.screenAlwaysOn)
        assertEquals(9L, imported.notebookId)
    }
}
