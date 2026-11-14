package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Backfill adds only the dates a foreign file lacks, without rewriting what another tool wrote. */
class VaultNoteDocumentMissingDatesTest {

    private companion object {
        const val CREATED = 1_700_000_000L
        const val MODIFIED = 1_700_000_100L
    }

    @Test
    fun withMissingDates_addsBothKeys_whenTheFileHasNoFrontMatter() {
        val raw = "# Groceries\n\n- milk\n- eggs\n"

        val updated = VaultNoteDocument.withMissingDates(raw, CREATED, MODIFIED)

        assertEquals("---\ncreated: $CREATED\nmodified: $MODIFIED\n---\n$raw", updated)
        assertEquals(raw, VaultNoteDocument.parse(updated).body)
    }

    @Test
    fun withMissingDates_combinedWithAnId_yieldsOneFrontMatterBlock() {
        val raw = "# Groceries\n"

        val withId = VaultNoteDocument.withNoteId(raw, "abc")
        val updated = VaultNoteDocument.withMissingDates(withId, CREATED, MODIFIED)

        assertEquals(
            "---\nallnotes-id: \"abc\"\ncreated: $CREATED\nmodified: $MODIFIED\n---\n$raw",
            updated,
        )
        assertEquals("one front-matter block", 2, updated.lines().count { it == "---" })
        assertEquals(raw, VaultNoteDocument.parse(updated).body)
    }

    @Test
    fun withMissingDates_keepsAnExistingCreated_andOnlyAddsModified() {
        val raw = "---\naliases: [x]\ncreated: 2024-01-15\n---\nBody\n"

        val updated = VaultNoteDocument.withMissingDates(raw, CREATED, MODIFIED)

        assertEquals(
            "---\naliases: [x]\ncreated: 2024-01-15\nmodified: $MODIFIED\n---\nBody\n",
            updated,
        )
    }

    @Test
    fun withMissingDates_keepsAnUnparseableCreated() {
        val raw = "---\ncreated: someday\n---\nBody\n"

        val updated = VaultNoteDocument.withMissingDates(raw, CREATED, MODIFIED)

        assertTrue(updated, updated.contains("created: someday"))
        assertTrue(updated, updated.contains("modified: $MODIFIED"))
        assertEquals("someday", VaultNoteDocument.parse(updated).frontMatter.knownKeysVerbatim.trim().substringAfter(": "))
    }

    @Test
    fun withMissingDates_keepsAPresentEmptyKey() {
        val raw = "---\ncreated:\n---\nBody\n"

        val updated = VaultNoteDocument.withMissingDates(raw, CREATED, MODIFIED)

        assertEquals("---\ncreated:\nmodified: $MODIFIED\n---\nBody\n", updated)
    }

    @Test
    fun withMissingDates_isNoOp_whenBothKeysExist() {
        val raw = "---\ncreated: someday\nmodified: whenever\n---\nBody\n"

        assertEquals(raw, VaultNoteDocument.withMissingDates(raw, CREATED, MODIFIED))
    }

    @Test
    fun withMissingDates_onlyAddsTheMissingKey() {
        val raw = "---\ntitle: \"T\"\nmodified: 2024-01-15T00:00:00Z\n---\nBody\n"

        val updated = VaultNoteDocument.withMissingDates(raw, CREATED, MODIFIED)

        assertEquals(
            "---\ntitle: \"T\"\nmodified: 2024-01-15T00:00:00Z\ncreated: $CREATED\n---\nBody\n",
            updated,
        )
    }

    @Test
    fun withMissingDates_keepsWindowsLineEndings() {
        val raw = "---\r\ntitle: \"T\"\r\n---\r\nBody\r\n"

        val updated = VaultNoteDocument.withMissingDates(raw, CREATED, MODIFIED)

        assertEquals(
            "---\r\ntitle: \"T\"\r\ncreated: $CREATED\r\nmodified: $MODIFIED\r\n---\r\nBody\r\n",
            updated,
        )
    }

    @Test
    fun withMissingDates_neverTouchesTheBody() {
        val raw = "---\ntitle: \"T\"\n---\ncreated: written in the body\n"

        val updated = VaultNoteDocument.withMissingDates(raw, CREATED, MODIFIED)

        assertTrue(updated, updated.endsWith("---\ncreated: written in the body\n"))
        assertNotNull(VaultNoteDocument.parse(updated).frontMatter.created)
        assertEquals(
            "created: written in the body",
            VaultNoteDocument.parse(updated).body.trim(),
        )
    }
}
