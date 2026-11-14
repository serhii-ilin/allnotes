package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.BaseResult
import org.qosp.notes.data.sync.core.GenericError
import org.qosp.notes.data.sync.core.Success
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultPortableMetadata
import org.qosp.notes.data.sync.fs.vault.history.HistoryNoteApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryPaths
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner

/**
 * M1: an empty scan is only trusted once the history explains every mapped file. The check runs before any
 * applier, so a provider glitch cannot bin notes whose files are still there.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryEmptyScanExplainedTest {

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

    /** Delivers only `.allnotes/`, as Syncthing may do before moving the note files. */
    private fun NoteRepositoryVaultHarness.copyHistoryTo(other: NoteRepositoryVaultHarness) {
        vaultFs.snapshotPaths()
            .filter { it.startsWith("${HistoryPaths.ROOT}/") }
            .forEach { path -> other.vaultFs.writeText(path, vaultFs.readText(path).orEmpty()) }
    }

    private suspend fun NoteRepositoryVaultHarness.mappingState(noteId: Long): VaultMappingState? =
        idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE)
            ?.let { VaultMappingState.resolve(it.extras) }

    /** Scenario: A bin waits for the moved file, and an unexplained empty scan stays a glitch (HST-09). */
    @Test
    fun anEmptyScanExplainedOnlyInPart_changesNothingAndFails() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.insertAndSync(Note(title = "Plan", content = "Body"))
            a.insertAndSync(Note(title = "Keep", content = "Other"))
            vault.syncthing(0, 1)
            b.syncNotes()
            val planOnB = b.noteByTitle("Plan")
            val keepOnB = b.noteByTitle("Keep")

            a.binAndSync(a.noteByTitle("Plan"))
            a.copyHistoryTo(b)
            // The provider answers with an empty listing: A's bin explains Plan, nothing explains Keep.
            b.vaultFs.notePaths().forEach { b.vaultFs.delete(it) }

            val result: BaseResult = b.repository.syncNotes()

            assertTrue("an unexplained empty scan stays a failed scan", result is GenericError)
            assertFalse("Plan was not binned by the glitch pass", b.getNote(planOnB.id)!!.isDeleted)
            assertFalse("Keep was not binned either", b.getNote(keepOnB.id)!!.isDeleted)
            assertTrue(
                "the bin event is not remembered as a mismatch from a pass that did nothing",
                b.folderHistory.load().mismatches.isEmpty(),
            )
            assertTrue("Plan's mapping is untouched", b.mappingState(planOnB.id)?.userBinned == false)
        } finally {
            vault.close()
        }
    }

    /** HST-09 AC1: when every missing file has a bin event from another device, the empty scan is real. */
    @Test
    fun anEmptyScanExplainedForEveryNote_appliesTheBinsAndSucceeds() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.insertAndSync(Note(title = "Plan", content = "Body"))
            vault.syncthing(0, 1)
            b.syncNotes()
            val planOnB = b.noteByTitle("Plan")

            a.binAndSync(a.noteByTitle("Plan"))
            a.copyHistoryTo(b)
            b.vaultFs.notePaths().forEach { b.vaultFs.delete(it) }

            val result: BaseResult = b.repository.syncNotes()

            assertTrue("the history explains the empty folder", result is Success)
            assertTrue(b.getNote(planOnB.id)!!.isDeleted)
            assertTrue(b.mappingState(planOnB.id)!!.userBinned)
        } finally {
            vault.close()
        }
    }
}
