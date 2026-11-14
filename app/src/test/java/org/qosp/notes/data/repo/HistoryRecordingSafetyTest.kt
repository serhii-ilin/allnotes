package org.qosp.notes.data.repo

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.history.HistoryOps
import org.qosp.notes.data.sync.fs.vault.history.HistoryOutbox
import org.qosp.notes.preferences.CloudService
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Recording must never fail a user action, and purges only describe bins the user asked for. */
@RunWith(RobolectricTestRunner::class)
class HistoryRecordingSafetyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** HST-05 AC1: a full app-private disk does not stop the bin or its mapping. */
    @Test
    fun anUnwritableOutbox_doesNotFailTheBin_andItsMappingStillHasTheTrashedPath() = runTest {
        val historyDir = tmp.newFolder("history")
        // The outbox path is a directory, so every append throws like a full disk would.
        assertTrue(File(historyDir, HistoryOutbox.FILE_NAME).mkdirs())
        val h = NoteRepositoryVaultHarness(this, historyDir = historyDir)
        try {
            val id = h.insertAndSync(Note(title = "Plan", content = "Body"))

            h.binAndSync(h.getNote(id)!!)

            val binned = h.getNote(id)!!
            assertTrue(binned.isDeleted)
            val state = h.mappingState(id)
            assertNotNull(state)
            assertTrue(state!!.userBinned)
            assertNotNull(state.trashedPath)
        } finally {
            h.close()
        }
    }

    /** HST-04 AC3: only a user's own bin may produce a `note.purge`. */
    @Test
    fun deleteNotes_recordsPurgeOnlyForAUserBin() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            val userBinned = h.insertAndSync(Note(title = "User bin", content = "Body"))
            h.binAndSync(h.getNote(userBinned)!!)
            h.historyOutbox.clear()

            h.repository.deleteNotes(h.getNote(userBinned)!!, sync = true)
            assertEquals(listOf(HistoryOps.NOTE_PURGE), h.historyOutbox.readAll().map { it.op })

            h.insertAndSync(Note(title = "Keep", content = "Body"))
            val missing = h.insertAndSync(Note(title = "Missing file", content = "Body"))
            h.binBecauseTheFileIsMissing(missing)
            val missingNote = h.getNote(missing)!!
            assertTrue(missingNote.isDeleted)
            assertFalse(h.mappingState(missing)!!.userBinned)
            h.historyOutbox.clear()

            h.repository.deleteNotes(missingNote, sync = true)
            assertTrue(h.historyOutbox.readAll().none { it.op == HistoryOps.NOTE_PURGE })
        } finally {
            h.close()
        }
    }

    /** HST-04 AC3: emptying the bin (or the retention worker) records purges only for user bins. */
    @Test
    fun permanentlyDeleteNotesInBin_recordsPurgeOnlyForUserBins() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            val userBinned = h.insertAndSync(Note(title = "User bin", content = "Body"))
            h.binAndSync(h.getNote(userBinned)!!)
            h.historyOutbox.clear()

            h.repository.permanentlyDeleteNotesInBin()
            assertEquals(listOf(HistoryOps.NOTE_PURGE), h.historyOutbox.readAll().map { it.op })

            h.insertAndSync(Note(title = "Keep", content = "Body"))
            val missing = h.insertAndSync(Note(title = "Missing file", content = "Body"))
            h.binBecauseTheFileIsMissing(missing)
            assertFalse(h.mappingState(missing)!!.userBinned)
            h.historyOutbox.clear()

            h.repository.permanentlyDeleteNotesInBin()
            assertTrue(h.historyOutbox.readAll().none { it.op == HistoryOps.NOTE_PURGE })
        } finally {
            h.close()
        }
    }

    private suspend fun NoteRepositoryVaultHarness.mappingState(noteId: Long): VaultMappingState? =
        idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE)
            ?.let { VaultMappingState.resolve(it.extras) }

    /** The file disappears under the app, so the two-scan rule bins the note without a user action. */
    private suspend fun NoteRepositoryVaultHarness.binBecauseTheFileIsMissing(noteId: Long) {
        val path = idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE)!!.storageUri!!
        vaultFs.delete(path)
        syncNotes()
        syncNotes()
    }
}
