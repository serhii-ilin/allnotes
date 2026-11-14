package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.data.sync.core.Success
import org.qosp.notes.preferences.CloudService
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VaultCaseMismatchScenariosTest {

    @Test
    fun externalCaseOnlyRename_keepsNoteLiveAndMapped() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            val id = h.insertAndSync(Note(title = "Note", content = "body"))
            val mapped = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!
            val raw = h.vaultFs.readText(mapped)!!
            // The provider lists the file under a different case than the mapping remembers.
            h.vaultFs.delete(mapped)
            val renamed = mapped.lowercase()
            assertTrue(renamed != mapped)
            h.vaultFs.writeText(renamed, raw)

            repeat(2) {
                assertEquals(Success, h.repository.syncNotes())
                h.flushRemoteActions()
            }

            val note = h.getNote(id)!!
            assertFalse(note.isDeleted)
            assertEquals("body", note.content.trim())
            assertEquals(1, h.localNoteCount())
            assertEquals(1, h.mappingCountFor(id))
            assertEquals(mapped, h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri)
        } finally {
            h.close()
        }
    }

    private fun listed(vararg paths: String) = paths.map { VaultDocumentTree.IndexedVaultFile(it) }

    @Test
    fun reportedPaths_exactCaseKeepsTheMapping_otherSpellingKeepsItsOwn() {
        val fs = InMemoryVaultFs(caseInsensitive = true)
        val reported = VaultDocumentTree.reportedPaths(fs, listed("Note.md", "note.md"), setOf("Note.md"))
        assertEquals("Note.md", reported["Note.md"])
        assertEquals("note.md", reported["note.md"])
    }

    @Test
    fun reportedPaths_caseSensitiveVaultReportsListedSpelling() {
        val fs = InMemoryVaultFs(caseInsensitive = false)
        val reported = VaultDocumentTree.reportedPaths(fs, listed("note.md"), setOf("Note.md"))
        assertEquals("note.md", reported["note.md"])
    }
}
