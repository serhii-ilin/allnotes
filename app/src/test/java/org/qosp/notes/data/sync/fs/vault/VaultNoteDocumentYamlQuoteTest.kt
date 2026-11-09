package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Test
import org.qosp.notes.data.model.Note

class VaultNoteDocumentYamlQuoteTest {

    @Test
    fun blocker2_titleWithQuote_roundTripsWithoutMutation() {
        val title = "Say \"hello\""
        val note = Note(title = title, content = "Body")
        val doc = VaultNoteDocument.fromNote(note, "Say.md", exportNotebookExplicitly = true)
        val raw = doc.renderFullText()
        val parsed = VaultNoteDocument.parse(raw)
        assertEquals(title, parsed.frontMatter.title)
        val secondPass = VaultNoteDocument.parse(VaultNoteDocument.parse(raw).let {
            VaultNoteDocument.fromNote(
                note.copy(content = parsed.body),
                "Say.md",
                exportNotebookExplicitly = true,
            ).renderFullText()
        })
        assertEquals(title, secondPass.frontMatter.title)
    }
}
