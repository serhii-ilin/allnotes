package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.SyncNote
import org.junit.Test

class VaultSyncImportTest {
  private fun remoteBody(path: String, body: String): SyncNote {
    val yaml = """
      |---
      |title: "t"
      |pinned: false
      |tags: []
      |---
      |$body
    """.trimMargin()
    return SyncNote(
      id = 0,
      idStr = path,
      title = "t",
      content = yaml,
      lastModified = 1L,
      extra = VaultContentHash.ofText(yaml),
    )
  }

    @Test
    fun isLocallyDirty_detectsUnsavedBodyEdit() {
        val path = "Work/Note.md"
        val note = Note(title = "t", content = "local edit")
        val baseline = VaultSyncImport.localContentHash(
            note.copy(content = "remote body"),
            path,
        )
        assertTrue(VaultSyncImport.isLocallyDirty(note, path, baseline))
    }

    @Test
    fun isLocallyDirty_falseWhenMatchesBaseline() {
        val path = "Work/Note.md"
        val note = Note(title = "t", content = "same")
        val baseline = VaultSyncImport.localContentHash(note, path)
        assertFalse(VaultSyncImport.isLocallyDirty(note, path, baseline))
    }

    @Test
    fun attachmentsFromRemote_parsedFromBody() {
        val path = "Work/Note.md"
        val body = "![x](vaultrel:Work/Note/x.jpg)"
        val remote = remoteBody(path, body)
        val attachments = VaultSyncImport.attachmentsFromRemote(remote)
        assertEquals(1, attachments.size)
        assertEquals(Attachment.Type.IMAGE, attachments[0].type)
    }

    @Test
    fun noteFromRemote_doesNotDropExistingTaskListWhenNotListNote() {
        val path = "Work/Note.md"
        val remote = remoteBody(path, "body")
        val existing = Note(title = "t", content = "body", taskList = listOf())
        val imported = VaultSyncImport.noteFromRemote(remote, existing)
        assertEquals("body", imported.content)
        assertEquals(existing.id, imported.id)
    }
}
