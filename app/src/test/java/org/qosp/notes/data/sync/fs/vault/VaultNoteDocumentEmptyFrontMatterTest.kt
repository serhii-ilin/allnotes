package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/** Empty front matter is still front matter, and a quoted key is still a key. */
class VaultNoteDocumentEmptyFrontMatterTest {

    private companion object {
        const val CREATED = 1_700_000_000L
        const val MODIFIED = 1_700_000_500L
    }

    @Test
    fun withMissingDates_insertsInsideAnEmptyBlock_LF() {
        val raw = "---\n---\nbody\n"

        val updated = VaultNoteDocument.withMissingDates(raw, CREATED, MODIFIED)

        assertEquals("---\ncreated: $CREATED\nmodified: $MODIFIED\n---\nbody\n", updated)
    }

    @Test
    fun withMissingDates_insertsInsideAnEmptyBlock_CRLF() {
        val raw = "---\r\n---\r\nbody\r\n"

        val updated = VaultNoteDocument.withMissingDates(raw, CREATED, MODIFIED)

        assertEquals("---\r\ncreated: $CREATED\r\nmodified: $MODIFIED\r\n---\r\nbody\r\n", updated)
    }

    @Test
    fun replaceNoteId_insertsInsideAnEmptyBlock_LF() {
        val raw = "---\n---\nbody\n"

        val updated = VaultNoteDocument.replaceNoteId(raw, "new-id")

        assertEquals("---\nallnotes-id: \"new-id\"\n---\nbody\n", updated)
    }

    @Test
    fun replaceNoteId_insertsInsideAnEmptyBlock_CRLF() {
        val raw = "---\r\n---\r\nbody\r\n"

        val updated = VaultNoteDocument.replaceNoteId(raw, "new-id")

        assertEquals("---\r\nallnotes-id: \"new-id\"\r\n---\r\nbody\r\n", updated)
    }

    @Test
    fun withMissingDates_doesNotDuplicateAQuotedCreated() {
        val raw = "---\n\"created\": 5\n'modified': 6\n---\nbody\n"

        assertEquals(raw, VaultNoteDocument.withMissingDates(raw, CREATED, MODIFIED))
    }

    @Test
    fun withMissingDates_addsOnlyTheMissingKey_whenTheOtherIsQuoted() {
        val raw = "---\n'created': 5\n---\nbody\n"

        val updated = VaultNoteDocument.withMissingDates(raw, CREATED, MODIFIED)

        assertEquals("---\n'created': 5\nmodified: $MODIFIED\n---\nbody\n", updated)
    }
}
