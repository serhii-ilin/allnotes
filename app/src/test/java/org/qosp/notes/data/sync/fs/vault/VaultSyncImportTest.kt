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
        val disk = remoteBody(path, "remote body")
        val state = VaultMappingState(
            baselineHash = VaultSyncImport.canonicalFileHash(
                note.copy(content = "remote body"),
                path,
                null,
                "",
            ),
        )
        assertTrue(VaultSyncImport.isLocallyDirty(note, path, state, null))
    }

    @Test
    fun isLocallyDirty_falseWhenMatchesBaseline() {
        val path = "Work/Note.md"
        val note = Note(title = "t", content = "same")
        val state = VaultMappingState(
            baselineHash = VaultSyncImport.canonicalFileHash(note, path, null, ""),
        )
        assertFalse(VaultSyncImport.isLocallyDirty(note, path, state, null))
    }

    @Test
    fun pendingPrivateAttachment_isDirtyEvenWhenBodyMatches() {
        val path = "Work/Note.md"
        val note = Note(
            title = "t",
            content = "body",
            attachments = listOf(Attachment(Attachment.Type.IMAGE, "content://media/1", "a")),
        )
        val state = VaultMappingState(
            baselineHash = VaultSyncImport.canonicalFileHash(
                note.copy(attachments = emptyList()),
                path,
                null,
                "",
            ),
        )
        assertTrue(VaultSyncImport.isLocallyDirty(note, path, state, null))
    }

    @Test
    fun canonicalHash_matchesExportWithNotebookAndPreservedYaml() {
        val path = "Work/Note.md"
        val note = Note(title = "t", content = "Body")
        val diskRaw = """
            |---
            |aliases:
            |  - one
            |---
            |Old
        """.trimMargin()
        val withNotebook = VaultSyncImport.canonicalFileHash(note, path, "Journal", "")
        val preservedOnly = VaultNoteDocument.parse(diskRaw).frontMatter.preservedYaml
        val withPreserved = VaultSyncImport.canonicalFileHash(note, path, "Journal", preservedOnly)
        assertTrue(withPreserved != withNotebook)
        val rendered = VaultNoteDocument.fromNote(note, path, "Journal", exportNotebookExplicitly = true)
            .copy(frontMatter = VaultNoteDocument.fromNote(note, path, "Journal", true).frontMatter.copy(preservedYaml = preservedOnly))
            .copy(body = "Body")
            .renderFullText()
        assertEquals(VaultContentHash.ofText(rendered), withPreserved)
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
