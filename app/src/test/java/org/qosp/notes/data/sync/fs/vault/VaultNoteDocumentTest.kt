package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.qosp.notes.data.model.Note
import org.junit.Test

class VaultNoteDocumentTest {
    @Test
    fun roundTrip_frontMatterAndBody() {
        val note = Note(title = "Hello", content = "World", isPinned = true)
        val path = "Hello.md"
        val doc = VaultNoteDocument.fromNote(note, path)
        val rendered = doc.renderFullText()
        val parsed = VaultNoteDocument.parse(rendered)
        assertEquals("World", parsed.body.trim())
        assertEquals(true, parsed.frontMatter.pinned)
    }

    @Test
    fun parse_legacyBodyWithoutFrontMatter() {
        val doc = VaultNoteDocument.parse("# Title\n\nBody")
        assertTrue(doc.body.contains("Body"))
    }
}
