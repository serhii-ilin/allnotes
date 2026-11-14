package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultPortableMetadata
import org.qosp.notes.data.sync.fs.vault.history.HistoryNoteApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryPaths
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner

/**
 * HST-09: a note binned before its live file ever reached a device still belongs in that device's bin.
 * The bin event and `.trash/` file are enough; the applier imports the trashed file without touching it.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryNoteBinImportTest {

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

    private suspend fun NoteRepositoryVaultHarness.binnedByTitle(title: String): Note? =
        noteDao.getDeleted(SortMethod.TITLE_ASC).first().singleOrNull { it.title == title }

    private suspend fun NoteRepositoryVaultHarness.mapping(noteId: Long) =
        idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE)

    private suspend fun NoteRepositoryVaultHarness.mappingState(noteId: Long): VaultMappingState =
        VaultMappingState.resolve(mapping(noteId)!!.extras)!!

    private fun NoteRepositoryVaultHarness.noteFiles(): Map<String, String> =
        vaultFs.notePaths().associateWith { vaultFs.readText(it).orEmpty() }

    private suspend fun NoteRepositoryVaultHarness.mismatches() = folderHistory.load().mismatches

    /** Delivers only the `.allnotes/` logs, as Syncthing does before it moves the note's file. */
    private fun NoteRepositoryVaultHarness.copyHistoryTo(other: NoteRepositoryVaultHarness) {
        vaultFs.snapshotPaths()
            .filter { it.startsWith("${HistoryPaths.ROOT}/") }
            .forEach { path -> other.vaultFs.writeText(path, vaultFs.readText(path).orEmpty()) }
    }

    /** A creates and bins "Plan" before B has ever seen its live file. */
    private suspend fun binBeforeDelivery(a: NoteRepositoryVaultHarness) {
        a.insertAndSync(Note(title = "Keep", content = "Other"))
        a.insertAndSync(Note(title = "Plan", content = "Body"))
        a.binAndSync(a.noteByTitle("Plan"))
    }

    /** Scenario: A bin on one phone bins the note on the other without waiting two scans (HST-09 AC1). */
    @Test
    fun aBinOfANoteThatNeverReachedTheDevice_importsItIntoTheBin() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            binBeforeDelivery(a)
            vault.syncthing(0, 1)
            val filesBeforeApply = b.noteFiles()

            b.syncNotes()

            val imported = b.binnedByTitle("Plan")!!
            assertTrue("the note is a user bin", imported.isDeleted)
            assertNotNull(imported.deletionDate)
            assertEquals("Body", imported.content.trim())
            val state = b.mappingState(imported.id)
            assertTrue(state.userBinned)
            assertEquals("Plan.md", state.binnedVaultPath)
            assertEquals(".trash/Plan.md", state.trashedPath)
            assertNull(b.mapping(imported.id)!!.storageUri)
            assertTrue(b.mapping(imported.id)!!.isDeletedLocally)
            assertEquals("applying the event wrote, moved or deleted no file", filesBeforeApply, b.noteFiles())
            assertTrue(b.vaultFs.isFile(".trash/Plan.md"))
            assertTrue(b.mismatches().isEmpty())

            b.syncNotes()
            assertEquals(
                "a second sync does not import it again",
                1,
                b.noteDao.getDeleted(SortMethod.TITLE_ASC).first().count { it.title == "Plan" },
            )
        } finally {
            vault.close()
        }
    }

    /** HST-09: an imported bin is restored here when the file returns through the sync tool. */
    @Test
    fun aRestoreOnTheOtherPhone_restoresANoteImportedIntoTheBin() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            binBeforeDelivery(a)
            vault.syncthing(0, 1)
            b.syncNotes()
            val imported = b.binnedByTitle("Plan")!!

            a.restoreAndSync(a.noteByTitle("Plan"))
            a.folderHistoryService.flush()
            vault.syncthing(0, 1)
            b.syncNotes()

            val restored = b.getNote(imported.id)!!
            assertFalse(restored.isDeleted)
            assertEquals("Plan.md", b.mapping(imported.id)!!.storageUri)
            assertFalse(b.mapping(imported.id)!!.isDeletedLocally)
            val state = b.mappingState(imported.id)
            assertFalse(state.userBinned)
            assertNull(state.binnedVaultPath)
            assertNull(state.trashedPath)
            assertTrue(b.vaultFs.isFile("Plan.md"))
        } finally {
            vault.close()
        }
    }

    /** Scenario: A purge on one phone clears the bin on the other without deleting files (HST-09 AC2). */
    @Test
    fun aPurgeOnTheOtherPhone_removesANoteImportedIntoTheBin() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            binBeforeDelivery(a)
            vault.syncthing(0, 1)
            b.syncNotes()
            val imported = b.binnedByTitle("Plan")!!

            a.repository.permanentlyDeleteNotesInBin()
            a.folderHistoryService.flush()
            a.copyHistoryTo(b)
            val filesBeforeApply = b.noteFiles()

            b.syncNotes()

            assertNull("the imported bin entry is gone", b.getNote(imported.id))
            assertNull(b.mapping(imported.id))
            assertEquals("applying the purge deleted no file", filesBeforeApply, b.noteFiles())
            assertTrue("the trashed file stays on this device", b.vaultFs.isFile(".trash/Plan.md"))
        } finally {
            vault.close()
        }
    }

    /** HST-09: without the trashed file there is nothing to import, and nothing is invented. */
    @Test
    fun aBinWithoutTheTrashedFile_isNotImported() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.insertAndSync(Note(title = "Keep", content = "Other"))
            vault.syncthing(0, 1)
            b.syncNotes()

            a.insertAndSync(Note(title = "Plan", content = "Body"))
            a.binAndSync(a.noteByTitle("Plan"))
            a.copyHistoryTo(b)

            b.syncNotes()

            assertTrue("no note is invented", b.noteDao.getAll(SortMethod.TITLE_ASC).first().none { it.title == "Plan" })
            assertNull(b.binnedByTitle("Plan"))
            assertTrue(b.mismatches().isEmpty())

            vault.syncthing(0, 1)
            b.syncNotes()

            val imported = b.binnedByTitle("Plan")!!
            assertTrue(b.mappingState(imported.id).userBinned)
        } finally {
            vault.close()
        }
    }
}
