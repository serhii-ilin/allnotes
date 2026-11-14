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
import org.qosp.notes.data.sync.core.GenericError
import org.qosp.notes.data.sync.fs.vault.VaultPortableMetadata
import org.qosp.notes.data.sync.fs.vault.history.HistoryNoteApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryPaths
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner

/** Review findings on HST-09: a recorded bin never bins a note whose own file is still here. */
@RunWith(RobolectricTestRunner::class)
class HistoryNoteApplierSafetyTest {

    private fun TestScope.vault(count: Int): MultiDeviceVault =
        MultiDeviceVault(this, count) { h ->
            listOf(
                HistoryNoteApplier(
                    h.noteDao,
                    h.idMappingDao,
                    h.db.reminderDao,
                    h.notebookDao,
                    VaultPortableMetadata(h.noteDao, h.notebookDao, h.db.tagDao, h.db.noteTagDao, h.db.reminderDao),
                    h.backendProvider,
                ),
            )
        }

    private suspend fun NoteRepositoryVaultHarness.noteByTitle(title: String): Note =
        noteDao.getAll(SortMethod.TITLE_ASC).first().single { it.title == title }

    /** Only the `.allnotes/` logs, as Syncthing may deliver them before any note file. */
    private fun NoteRepositoryVaultHarness.copyHistoryTo(other: NoteRepositoryVaultHarness) {
        vaultFs.snapshotPaths()
            .filter { it.startsWith("${HistoryPaths.ROOT}/") }
            .forEach { path -> other.vaultFs.writeText(path, vaultFs.readText(path).orEmpty()) }
    }

    @Test
    fun aBinOfTheOldPath_keepsANoteRenamedHereMeanwhile() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.insertAndSync(Note(title = "Plan", content = "Body"))
            a.insertAndSync(Note(title = "Keep", content = "Other"))
            vault.syncthing(0, 1)
            b.syncNotes()
            val bPlan = b.noteByTitle("Plan")

            a.binAndSync(a.noteByTitle("Plan"))
            // B renamed the note before A's bin reached it: B's file now lives at Ideas.md.
            b.updateAndSync(bPlan.copy(title = "Ideas", modifiedDate = bPlan.modifiedDate + 10))
            assertEquals("Ideas.md", b.idMappingDao.getByLocalIdAndProvider(bPlan.id, CloudService.FILE_STORAGE)!!.storageUri)
            a.copyHistoryTo(b)

            b.syncNotes()

            assertFalse("B's own file is still here, so the note stays live", b.getNote(bPlan.id)!!.isDeleted)
            assertTrue(b.vaultFs.isFile("Ideas.md"))
            assertEquals(
                listOf("Renamed or moved here; kept"),
                b.folderHistory.load().mismatches.map { it.reason },
            )
        } finally {
            vault.close()
        }
    }

    @Test
    fun anEmptyScan_explainedOnlyInPart_isStillAGlitch() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.insertAndSync(Note(title = "Plan", content = "Body"))
            a.insertAndSync(Note(title = "Keep", content = "Other"))
            vault.syncthing(0, 1)
            b.syncNotes()
            val bKeep = b.noteByTitle("Keep")

            a.binAndSync(a.noteByTitle("Plan"))
            a.copyHistoryTo(b)
            // The provider returns an empty listing on B: Plan is explained by A's bin, Keep is not.
            b.vaultFs.notePaths().forEach { b.vaultFs.delete(it) }

            val result = b.repository.syncNotes()

            assertTrue("an unexplained empty scan stays a failed scan", result is GenericError)
            assertFalse("Keep is neither binned nor counted", b.getNote(bKeep.id)!!.isDeleted)
            assertEquals("Keep.md", b.idMappingDao.getByLocalIdAndProvider(bKeep.id, CloudService.FILE_STORAGE)!!.storageUri)
        } finally {
            vault.close()
        }
    }
}
