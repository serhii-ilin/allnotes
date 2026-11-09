package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule: `.md`/`.txt` under a note asset sibling folder are omitted only when referenced in that note's body.
 * Unreferenced nested notes (e.g. `Work/Meeting/agenda.md` without `Work/Meeting.md`) still import.
 */
class VaultReferencedAttachmentMdIndexTest {

    @Test
    fun unreferencedAgendaMd_inNestedFolder_isIndexed() {
        val fs = InMemoryVaultFs()
        fs.writeText("Work/agenda.md", "---\n---\nAgenda body")
        val indexed = VaultDocumentTree.indexNotes(fs).map { it.relativePath }
        assertEquals(listOf("Work/agenda.md"), indexed)
    }

    @Test
    fun referencedAttachmentMarkdown_besideNote_isExcludedFromIndex() {
        val fs = InMemoryVaultFs()
        fs.writeText(
            "Work/Meeting.md",
            "---\n---\nSee minutes\n\n[minutes](Meeting/minutes.md)",
        )
        fs.writeText("Work/Meeting/minutes.md", "---\n---\nMinutes")
        val indexed = VaultDocumentTree.indexNotes(fs).map { it.relativePath }
        assertEquals(listOf("Work/Meeting.md"), indexed)
        assertTrue(indexed.none { it.endsWith("minutes.md") })
    }
}
