package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
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

    @Test
    fun exportStripsInlineLink_keepsSurroundingProse() {
        val body = "See ![x](Note/x.jpg) please"
        val stripped = VaultAttachments.removeVaultLinksForPaths(body, path, setOf(vaultRel))
        assertEquals("See  please", stripped)
    }

    @Test
    fun exportStripsAngleBracketImageTarget() {
        val body = "Hi ![x](<Note/x.jpg>) end"
        val stripped = VaultAttachments.removeVaultLinksForPaths(body, path, setOf(vaultRel))
        assertEquals("Hi  end", stripped)
    }

    @Test
    fun exportSkipsFencedCodeBlock() {
        val body = """
            |```markdown
            |![x](Note/x.jpg)
            |```
            |ok
        """.trimMargin()
        val stripped = VaultAttachments.removeVaultLinksForPaths(body, path, setOf(vaultRel))
        assertTrue(stripped.contains("![x]"))
        assertTrue(stripped.contains("```"))
    }

    @Test
    fun exportSkipsInlineCodeSpan() {
        val body = "Use `![x](Note/x.jpg)` literal"
        val stripped = VaultAttachments.removeVaultLinksForPaths(body, path, setOf(vaultRel))
        assertEquals(body, stripped)
    }

    @Test
    fun export_doesNotStripNonImageFileLinks() {
        val fileRel = VaultPaths.vaultRelative("Work/Note/doc.pdf")
        val body = "Get [doc](Note/doc.pdf)"
        val stripped = VaultAttachments.removeVaultLinksForPaths(body, path, setOf(fileRel))
        assertEquals(body, stripped)
    }
}

