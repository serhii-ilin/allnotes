package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.SyncNote

class VaultAttachmentMembershipTest {

    private val path = "Work/Note.md"
    private val vaultRel = "vaultrel:Work/Note/x.jpg"

    @Test
    fun gridRemoval_linkStillInBody_marksDirtyWithoutBodyEdit() {
        val body = "Intro\n\n![x](Note/x.jpg)"
        val local = Note(title = "t", content = body, attachments = emptyList())
        val state = VaultMappingState(
            baselineHash = VaultSyncImport.canonicalFileHash(
                Note(title = "t", content = body, attachments = listOf(Attachment(Attachment.Type.IMAGE, vaultRel, "x"))),
                path,
                null,
                "",
            ),
            vaultAttachmentPaths = listOf(vaultRel),
        )
        assertTrue(VaultSyncImport.isLocallyDirty(local, path, state, null))
    }

    @Test
    fun dirtyRefresh_doesNotResurrectRemovedPath() {
        val remote = SyncNote(
            id = 0,
            idStr = path,
            title = "t",
            content = "---\n---\n![x](Note/x.jpg)\n",
            lastModified = 1L,
            extra = "h",
        )
        val merged = VaultSyncImport.attachmentsAfterDirtyRefresh(emptyList(), remote, setOf(vaultRel))
        assertTrue(merged.isEmpty())
    }

    @Test
    fun exportStripsRemovedLinkWithoutBodyRewrite() {
        val body = "text\n\n![x](Note/x.jpg)"
        val stripped = VaultAttachments.removeVaultLinksForPaths(body, path, setOf(vaultRel))
        assertFalse(stripped.contains("![x]"))
        assertTrue(stripped.contains("text"))
    }
}
