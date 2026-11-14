package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultAttachmentsTest {
    @Test
    fun parse_imageLink() {
        val body = "Hello\n\n![kitchen](Meeting/photo.jpg)"
        val attachments = VaultAttachments.parseFromBody(body, "Work/Meeting.md")
        assertEquals(1, attachments.size)
        assertTrue(attachments[0].path.startsWith(VaultPaths.VAULT_REL_PREFIX))
        assertEquals("Work/Meeting/photo.jpg", VaultPaths.toVaultRelative(attachments[0].path))
    }

    @Test
    fun relativeLinkFromNote_rootNote_keepsAssetDirectorySegment() {
        val rel = VaultAttachments.relativeLinkFromNote("Note.md", "Note/photo.jpg")
        assertEquals("Note/photo.jpg", rel)
    }

    @Test
    fun rejectsRemoteUrls() {
        val body = "![](https://example.com/x.png)"
        assertTrue(VaultAttachments.parseFromBody(body, "a.md").isEmpty())
    }

    @Test
    fun mergeAttachmentLinks_nonImageUsesFileLink() {
        val audio = org.qosp.notes.data.model.Attachment(
            org.qosp.notes.data.model.Attachment.Type.AUDIO,
            VaultPaths.vaultRelative("Work/Plan/clip.m4a"),
            "clip.m4a",
        )
        val image = org.qosp.notes.data.model.Attachment(
            org.qosp.notes.data.model.Attachment.Type.IMAGE,
            VaultPaths.vaultRelative("Work/Plan/photo.jpg"),
            "photo.jpg",
        )
        var body = VaultAttachments.mergeAttachmentLinks("", "Work/Plan.md", listOf(audio))
        assertTrue(body.contains("[clip.m4a](Plan/clip.m4a)"))
        assertTrue(!body.contains("![clip.m4a]"))
        body = VaultAttachments.mergeAttachmentLinks(body, "Work/Plan.md", listOf(image))
        assertTrue(body.contains("![photo.jpg](Plan/photo.jpg)"))
    }
}
