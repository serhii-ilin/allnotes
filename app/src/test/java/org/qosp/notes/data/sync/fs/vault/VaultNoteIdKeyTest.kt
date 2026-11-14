package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.data.model.Note
import java.util.UUID

class VaultNoteIdKeyTest {

    @Test
    fun parse_readsAllNotesId_andRenderWritesItFirst() {
        val raw = "---\nallnotes-id: \"abc\"\ntitle: \"T\"\n---\nBody\n"
        val parsed = VaultNoteDocument.parse(raw)
        assertEquals("abc", parsed.frontMatter.id)

        val rendered = parsed.renderFullText()
        assertEquals("allnotes-id: \"abc\"", rendered.lines()[1])
        assertFalse(rendered.lines().any { it.startsWith("id:") })
    }

    @Test
    fun foreignId_isKeptVerbatim_andIsNotIdentity() {
        val raw = "---\ntitle: \"T\"\nid: 20240101120000\n---\nBody\n"
        val parsed = VaultNoteDocument.parse(raw)
        assertNull(parsed.frontMatter.id)
        assertTrue(parsed.frontMatter.preservedYaml.contains("id: 20240101120000"))

        val saved = VaultNoteDocument.mergePreservedFromDisk(
            raw,
            VaultNoteDocument.fromNote(Note(title = "T", content = "Body", modifiedDate = 2_000_000_000L), "T.md"),
        ).renderFullText()

        assertEquals(1, saved.lines().count { it == "id: 20240101120000" })
        assertFalse(saved.lines().any { it.startsWith("id: ") && it != "id: 20240101120000" })
        assertNull(VaultNoteDocument.parse(saved).frontMatter.id)
    }

    @Test
    fun foreignIdAndAllNotesId_bothSurvive_identityIsAllNotesId() {
        val raw = "---\nid: x\nallnotes-id: \"y\"\n---\nBody\n"
        assertEquals("y", VaultNoteDocument.parse(raw).frontMatter.id)

        val rendered = VaultNoteDocument.parse(raw).renderFullText()
        assertTrue(rendered.lines().contains("id: x"))
        assertTrue(rendered.lines().contains("allnotes-id: \"y\""))
        assertEquals("y", VaultNoteDocument.parse(rendered).frontMatter.id)
    }

    @Test
    fun withNoteId_insertsAllNotesId_whenFrontMatterExists() {
        val raw = "---\ntitle: \"T\"\nid: foreign\n---\nBody\n"
        val withId = VaultNoteDocument.withNoteId(raw, "abc")
        assertEquals("abc", VaultNoteDocument.parse(withId).frontMatter.id)
        assertEquals("allnotes-id: \"abc\"", withId.lines()[1])
        assertTrue(withId.contains("title: \"T\""))
        assertTrue(withId.contains("id: foreign"))
    }

    @Test
    fun withNoteId_addsFrontMatter_whenMissing() {
        val withId = VaultNoteDocument.withNoteId("Body\n", "abc")
        assertEquals("abc", VaultNoteDocument.parse(withId).frontMatter.id)
        assertTrue(withId.startsWith("---\nallnotes-id: \"abc\"\n---\nBody"))
    }

    @Test
    fun withNoteId_isNoOp_whenAllNotesIdExists() {
        val raw = "---\nallnotes-id: \"abc\"\n---\nBody\n"
        assertEquals(raw, VaultNoteDocument.withNoteId(raw, "other"))
    }

    @Test
    fun replaceNoteId_replacesOnlyTheAllNotesIdLine() {
        val raw = "---\nid: 20240101120000\nallnotes-id: \"old\"\ntitle: \"T\"\nfoo: bar\n---\nBody\n"
        val replaced = VaultNoteDocument.replaceNoteId(raw, "new")
        assertEquals(raw.replace("allnotes-id: \"old\"", "allnotes-id: \"new\""), replaced)
        assertEquals("new", VaultNoteDocument.parse(replaced).frontMatter.id)
        assertTrue(replaced.contains("id: 20240101120000"))
    }

    @Test
    fun replaceNoteId_insertsWhenAbsent() {
        val raw = "---\nid: 20240101120000\ntitle: \"T\"\n---\nBody\n"
        val replaced = VaultNoteDocument.replaceNoteId(raw, "new")
        assertEquals(VaultNoteDocument.withNoteId(raw, "new"), replaced)
        assertEquals("new", VaultNoteDocument.parse(replaced).frontMatter.id)
        assertTrue(replaced.contains("id: 20240101120000"))
    }

    @Test
    fun replaceNoteId_neverTouchesTheBody() {
        val raw = "---\nallnotes-id: \"old\"\n---\nallnotes-id: written in the body\n"
        val replaced = VaultNoteDocument.replaceNoteId(raw, "new")
        assertEquals("---\nallnotes-id: \"new\"\n---\nallnotes-id: written in the body\n", replaced)

        val noFrontMatter = "allnotes-id: written in the body\n"
        val inserted = VaultNoteDocument.replaceNoteId(noFrontMatter, "new")
        assertEquals("new", VaultNoteDocument.parse(inserted).frontMatter.id)
        assertTrue(inserted.endsWith("---\nallnotes-id: written in the body\n"))
    }

    @Test
    fun withNoteId_keepsWindowsLineEndings() {
        val raw = "---\r\ntitle: \"T\"\r\n---\r\nBody\r\n"
        assertEquals("---\r\nallnotes-id: \"abc\"\r\ntitle: \"T\"\r\n---\r\nBody\r\n", VaultNoteDocument.withNoteId(raw, "abc"))
        val bare = "Body\r\n"
        assertEquals("---\r\nallnotes-id: \"abc\"\r\n---\r\nBody\r\n", VaultNoteDocument.withNoteId(bare, "abc"))
    }

    @Test
    fun withNoteId_fillsABlankKey_insteadOfAddingASecondOne() {
        val raw = "---\nallnotes-id:\ntitle: \"T\"\n---\nBody\n"
        val withId = VaultNoteDocument.withNoteId(raw, "abc")
        assertEquals("---\nallnotes-id: \"abc\"\ntitle: \"T\"\n---\nBody\n", withId)
    }

    @Test
    fun forFile_isStableUuid_thatDependsOnPathAndCreated() {
        val id = VaultNoteIds.forFile("Work/Plan.md", 1_700_000_000L)
        assertEquals(id, VaultNoteIds.forFile("Work/Plan.md", 1_700_000_000L))
        assertEquals(id, UUID.fromString(id).toString())
        assertNotEquals(id, VaultNoteIds.forFile("Work/Other.md", 1_700_000_000L))
        assertNotEquals(id, VaultNoteIds.forFile("Work/Plan.md", 1_700_000_001L))
    }

    @Test
    fun forFile_handlesMissingCreated() {
        val id = VaultNoteIds.forFile("Work/Plan.md", null)
        assertEquals(id, VaultNoteIds.forFile("Work/Plan.md", null))
        assertEquals(id, UUID.fromString(id).toString())
        assertNotEquals(id, VaultNoteIds.forFile("Work/Plan.md", 0L))
    }
}
