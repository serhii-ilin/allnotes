package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Test
import org.qosp.notes.data.model.Attachment

class VaultAttachmentsTitleStripTest {

    @Test
    fun stripDoubleQuotedTitle_fromImageLink() {
        assertEquals("a.png", VaultAttachments.normalizeLinkTarget("a.png \"Title\""))
    }

    @Test
    fun stripParenthesizedTitle_fromImageLink() {
        assertEquals("a.png", VaultAttachments.normalizeLinkTarget("a.png (Title)"))
    }

    @Test
    fun angleBracketPath_withQuotedTitle() {
        assertEquals("a%20b.png", VaultAttachments.normalizeLinkTarget("<a%20b.png> \"Caption\""))
    }

    @Test
    fun parseBody_ignoresTitleInPath() {
        val body = "![x](holiday.png \"Summer trip\")"
        val attachments = VaultAttachments.parseFromBody(body, "Note.md")
        assertEquals(1, attachments.size)
        assertEquals(
            VaultPaths.vaultRelative("holiday.png"),
            attachments.single().path,
        )
    }
}
