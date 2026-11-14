package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.fs.vault.FsVaultWriteStore
import org.qosp.notes.data.sync.fs.vault.VaultContentHash
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultPortableMetadata
import org.qosp.notes.data.sync.fs.vault.VaultRecoveryJournal
import org.qosp.notes.data.sync.fs.vault.VaultRecoveryJournalEntry
import org.qosp.notes.data.sync.fs.vault.history.HistoryNoteApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryPaths
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner

/**
 * M2: the scan skips quarantined files, but Room keeps their notes. Their paths count as present, so a bin
 * from another device waits instead of binning a note whose file is still in the folder.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryQuarantinedFileTest {

    private fun applierFor(h: NoteRepositoryVaultHarness): HistoryNoteApplier = HistoryNoteApplier(
        h.noteDao,
        h.idMappingDao,
        h.db.reminderDao,
        h.notebookDao,
        VaultPortableMetadata(h.noteDao, h.notebookDao, h.db.tagDao, h.db.noteTagDao, h.db.reminderDao),
        h.backendProvider,
    )

    private fun TestScope.vault(count: Int): MultiDeviceVault =
        MultiDeviceVault(this, count) { h -> listOf(applierFor(h)) }

    private suspend fun NoteRepositoryVaultHarness.noteByTitle(title: String): Note =
        noteDao.getAll(SortMethod.TITLE_ASC).first().single { it.title == title }

    private suspend fun NoteRepositoryVaultHarness.mappingState(noteId: Long): VaultMappingState? =
        idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE)
            ?.let { VaultMappingState.resolve(it.extras) }

    private fun NoteRepositoryVaultHarness.noteFiles(): Map<String, String> =
        vaultFs.notePaths().associateWith { vaultFs.readText(it).orEmpty() }

    /** Delivers only `.allnotes/`, as Syncthing may do before moving the note files. */
    private fun NoteRepositoryVaultHarness.copyHistoryTo(other: NoteRepositoryVaultHarness) {
        vaultFs.snapshotPaths()
            .filter { it.startsWith("${HistoryPaths.ROOT}/") }
            .forEach { path -> other.vaultFs.writeText(path, vaultFs.readText(path).orEmpty()) }
    }

    private fun quarantine(h: NoteRepositoryVaultHarness, path: String) {
        val journal = VaultRecoveryJournal(
            vaultScopeId = h.vaultFs.scopeId,
            vaultGeneration = 1L,
            entries = listOf(
                VaultRecoveryJournalEntry(
                    notePath = path,
                    stagingToken = "st-gone",
                    backupToken = null,
                    expectedContentHash = VaultContentHash.ofText("never written"),
                    phase = VaultRecoveryJournalEntry.PHASE_PUBLISHING,
                ),
            ),
        )
        FsVaultWriteStore(h.context, h.vaultFs).writeJournal(journal)
    }

    /** HST-09: a quarantined file is present, so a bin for it waits instead of hiding the file's note. */
    @Test
    fun aQuarantinedMappedFile_countsAsPresent_soTheBinWaits() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.insertAndSync(Note(title = "Plan", content = "Body"))
            // A second note keeps the folder scan non-empty.
            a.insertAndSync(Note(title = "Keep", content = "Other"))
            vault.syncthing(0, 1)
            b.syncNotes()
            val planOnB = b.noteByTitle("Plan")

            a.binAndSync(a.noteByTitle("Plan"))
            a.copyHistoryTo(b)
            quarantine(b, "Plan.md")
            val filesBefore = b.noteFiles()

            b.syncNotes()

            assertFalse("the quarantined file is still in the folder, so the bin waits", b.getNote(planOnB.id)!!.isDeleted)
            assertEquals("nothing was binned or moved by the glitch", filesBefore, b.noteFiles())
            assertTrue("waiting is not a mismatch", b.folderHistory.load().mismatches.isEmpty())

            // The move arrives, the quarantine clears and the note then bins.
            FsVaultWriteStore(b.context, b.vaultFs).writeJournal(null)
            vault.syncthing(0, 1)
            b.syncNotes()

            assertTrue(b.getNote(planOnB.id)!!.isDeleted)
            assertTrue(b.mappingState(planOnB.id)!!.userBinned)
        } finally {
            vault.close()
        }
    }
}
