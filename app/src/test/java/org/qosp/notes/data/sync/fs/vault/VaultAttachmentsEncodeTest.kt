package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultAttachmentsEncodeTest {

    @Test
    fun encodeLinkTarget_percentEncodesSpaces() {
        assertEquals("my%20photo.jpg", VaultAttachments.encodeLinkTarget("my photo.jpg"))
    }

    @Test
    fun markdownImageLink_wrapsSpacedTargetInAngleBrackets() {
        val md = VaultAttachments.markdownImageLink("a b.jpg", "pic")
        assertTrue(md.contains("(<a%20b.jpg>)"))
    }
}
