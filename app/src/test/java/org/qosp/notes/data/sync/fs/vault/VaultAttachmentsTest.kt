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
    fun rejectsRemoteUrls() {
        val body = "![](https://example.com/x.png)"
        assertTrue(VaultAttachments.parseFromBody(body, "a.md").isEmpty())
    }
}
