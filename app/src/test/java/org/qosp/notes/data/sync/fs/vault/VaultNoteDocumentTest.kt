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

    @Test
    fun parse_hrInBody_notFrontMatter() {
        val raw = "Intro\n\n---\n\nAfter rule"
        val doc = VaultNoteDocument.parse(raw)
        assertEquals(raw, doc.body)
        assertEquals(null, doc.frontMatter.title)
    }

    @Test
    fun preservedForeignYaml_roundTrip() {
        val raw = """
            |---
            |title: "Keep"
            |aliases:
            |  - one
            |  - two
            |foo: bar
            |tags: []
            |---
            |Body
        """.trimMargin()
        val parsed = VaultNoteDocument.parse(raw)
        assertTrue(parsed.frontMatter.preservedYaml.contains("aliases:"))
        val merged = VaultNoteDocument.mergePreservedFromDisk(raw, VaultNoteDocument.fromNote(Note(title = "Keep", content = "Body"), "Keep.md"))
        assertTrue(merged.renderFullText().contains("aliases:"))
        assertTrue(merged.renderFullText().contains("foo: bar"))
    }

    @Test
    fun emptyTags_exportedExplicitly() {
        val doc = VaultNoteDocument.fromNote(Note(title = "A", content = "b", tags = emptyList()), "A.md")
        assertTrue(doc.renderFullText().contains("tags: []"))
    }

    @Test
    fun isList_falseFromYamlDoesNotForceChecklistUi() {
        val raw = """
            |---
            |isList: false
            |---
            |- [ ] not a task note ui contract
        """.trimMargin()
        val doc = VaultNoteDocument.parse(raw)
        val note = VaultNoteDocument.toNote(doc, "x.md", 0L)
        assertEquals(false, note.isList)
        assertEquals("- [ ] not a task note ui contract", note.content)
    }
}
