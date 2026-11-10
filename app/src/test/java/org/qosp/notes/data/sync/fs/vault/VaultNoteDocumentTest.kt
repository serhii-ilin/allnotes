package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Reminder
import org.qosp.notes.data.model.Tag
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
    fun mergePreserved_dropsIsoModifiedWhenAppModifiedChanges() {
        val raw = """
            |---
            |title: "T"
            |modified: 2024-07-01T00:00:00Z
            |---
            |Body
        """.trimMargin()
        val updated = VaultNoteDocument.fromNote(
            Note(title = "T", content = "Body", modifiedDate = 2_000_000_000L),
            "T.md",
        )
        val out = VaultNoteDocument.mergePreservedFromDisk(raw, updated).renderFullText()
        assertTrue(out.contains("modified: 2000000000"))
        assertTrue(!out.contains("2024-07-01T00:00:00Z"))
    }

    @Test
    fun mergePreserved_dropsScalarTagsWhenAppTagsChange() {
        val raw = """
            |---
            |title: "T"
            |tags: work
            |---
            |Body
        """.trimMargin()
        val updated = VaultNoteDocument.fromNote(
            Note(title = "T", content = "Body", tags = listOf(org.qosp.notes.data.model.Tag("work"), org.qosp.notes.data.model.Tag("edited"))),
            "T.md",
        )
        val merged = VaultNoteDocument.mergePreservedFromDisk(raw, updated)
        val out = merged.renderFullText()
        assertTrue(out.contains("\"edited\""))
        assertTrue(!out.matches(Regex("(?m)^tags: work\\s*$")))
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

    @Test
    fun parseCreated_localDateTimeAndOffsetFormats_resolveToUtcEpoch() {
        val expected = 1718452800L // 2024-06-15T12:00:00Z
        listOf(
            "2024-06-15T12:00:00",
            "2024-06-15T12:00",
            "2024-06-15 12:00:00",
            "2024-06-15 12:00",
            "2024-06-15T14:00:00+02:00",
        ).forEach { value ->
            val doc = VaultNoteDocument.parse("---\ncreated: $value\n---\nBody\n")
            assertEquals("created: $value", expected, doc.frontMatter.created)
        }
    }

    @Test
    fun unparseableCreated_isKeptVerbatimAcrossSave() {
        val raw = "---\ntitle: \"T\"\ncreated: sometime in June\n---\nBody\n"
        val updated = VaultNoteDocument.fromNote(
            Note(title = "T", content = "Body", creationDate = 1_000L, modifiedDate = 2_000_000_000L),
            "T.md",
        )
        val out = VaultNoteDocument.mergePreservedFromDisk(raw, updated).renderFullText()
        assertTrue(out.contains("created: sometime in June"))
        assertEquals(1, out.lines().count { it.startsWith("created:") })
    }

    @Test
    fun mergePreserved_keepsIsoModifiedWhenAppModifiedUnchanged() {
        val raw = "---\ntitle: \"T\"\nmodified: 2024-07-01T00:00:00Z\n---\nBody\n"
        val updated = VaultNoteDocument.fromNote(
            Note(title = "T", content = "Body", modifiedDate = 1_719_792_000L),
            "T.md",
        )
        val out = VaultNoteDocument.mergePreservedFromDisk(raw, updated).renderFullText()
        assertTrue(out.contains("modified: 2024-07-01T00:00:00Z"))
    }

    @Test
    fun mergePreserved_blockListTagsUnchanged_keepsWholeBlock() {
        val raw = "---\ntitle: \"T\"\ntags:\n  - a\n  - b\n---\nBody\n"
        val updated = VaultNoteDocument.fromNote(
            Note(title = "T", content = "Body", tags = listOf(Tag("a"), Tag("b"))),
            "T.md",
        )
        val out = VaultNoteDocument.mergePreservedFromDisk(raw, updated).renderFullText()
        assertTrue(out.contains("tags:\n  - a\n  - b\n"))
    }

    @Test
    fun mergePreserved_blockListTagsChange_dropsWholeBlockNoOrphanItems() {
        val raw = "---\ntitle: \"T\"\ntags:\n  - a\n---\nBody\n"
        val updated = VaultNoteDocument.fromNote(
            Note(title = "T", content = "Body", tags = listOf(Tag("a"), Tag("b"))),
            "T.md",
        )
        val out = VaultNoteDocument.mergePreservedFromDisk(raw, updated).renderFullText()
        assertFalse(out.lines().any { it.startsWith("  - ") })
        assertEquals(listOf("a", "b"), VaultNoteDocument.parse(out).frontMatter.tags)
    }

    @Test
    fun reminders_roundTrip_asIsoUtcList() {
        val at = java.time.Instant.parse("2099-11-12T09:00:00Z").epochSecond
        val note = Note(title = "R", content = "b", reminders = listOf(Reminder("Call \"Bob\"", 0L, at)))
        val rendered = VaultNoteDocument.fromNote(note, "R.md").renderFullText()
        assertTrue(rendered, rendered.contains("reminders:\n  - name: \"Call \\\"Bob\\\"\"\n    at: 2099-11-12T09:00:00Z\n"))
        val parsed = VaultNoteDocument.parse(rendered).frontMatter.reminders
        assertEquals(listOf(VaultReminder("Call \"Bob\"", at)), parsed)
    }

    @Test
    fun reminders_parseEpochSecondsAndKeepOtherKeys() {
        val raw = "---\ntitle: \"T\"\nreminders:\n  - name: A\n    at: 4102444800\n  - name: B\n    at: 2100-01-01T00:00:00Z\nfoo: bar\n---\nbody\n"
        val doc = VaultNoteDocument.parse(raw)
        assertEquals(listOf(VaultReminder("A", 4102444800L), VaultReminder("B", 4102444800L)), doc.frontMatter.reminders)
        assertEquals("foo: bar", doc.frontMatter.preservedYaml)
        assertTrue(doc.renderFullText().contains("foo: bar"))
    }

    @Test
    fun reminders_emptyKeyIsEmptyList_absentKeyIsNull() {
        assertEquals(emptyList<VaultReminder>(), VaultNoteDocument.parse("---\nreminders: []\n---\nb\n").frontMatter.reminders)
        assertEquals(null, VaultNoteDocument.parse("---\ntitle: x\n---\nb\n").frontMatter.reminders)
    }

    @Test
    fun noteWithoutReminders_writesNoKey_andExpiredAreDropped() {
        val none = VaultNoteDocument.fromNote(Note(title = "N", content = "b"), "N.md").renderFullText()
        assertFalse(none.contains("reminders"))
        val expired = Note(title = "N", content = "b", reminders = listOf(Reminder("old", 0L, 1000L)))
        assertEquals(none, VaultNoteDocument.fromNote(expired, "N.md").renderFullText())
    }

    @Test
    fun noteWithoutReminders_hashIsTheLegacyHash() {
        // Frozen from the output before reminders existed: an upgrade must not make notes look modified.
        val note = Note(title = "Hello", content = "World", creationDate = 1L, modifiedDate = 2L)
        val legacy = "---\npinned: false\narchived: false\nhidden: false\nisList: false\nmarkdown: true\n" +
            "color: \"Default\"\nnotebook: \"\"\ntags: []\ncreated: 1\nmodified: 2\n---\nWorld\n"
        assertEquals(VaultContentHash.ofText(legacy), VaultSyncImport.canonicalFileHash(note, "Hello.md", null, null))
    }
}
