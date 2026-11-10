package org.qosp.notes.data.sync.fs.vault

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.preferences.SortMethod
import org.qosp.notes.ui.settings.SettingsViewModel
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VaultFolderSwapScenariosTest {

    private val oldTree = "content://com.test.docs/tree/old"
    private val newTree = "content://com.test.docs/tree/new"

    private fun viewModel(h: NoteRepositoryVaultHarness): SettingsViewModel {
        val prefs = mockk<PreferenceRepository>(relaxed = true)
        coEvery { prefs.getEncryptedString(any()) } returns flowOf("3")
        coEvery { prefs.getEncryptedString(PreferenceRepository.STORAGE_LOCATION) } returns flowOf(oldTree)
        return SettingsViewModel(
            h.context,
            prefs,
            h.repository,
            mockk<BackendProvider>(relaxed = true),
            mockk<VaultTreeAccess>(relaxed = true),
            VaultSyncCoordinator(prefs),
            mockk<org.qosp.notes.ui.reminders.ReminderManager>(relaxed = true),
        )
    }

    private suspend fun NoteRepositoryVaultHarness.notes() = noteDao.getAll(SortMethod.TITLE_ASC).first()

    @Test
    fun move_intoFolderWithMatchingFile_linksInsteadOfDuplicating() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        // The old folder's mapping points at a file the new folder does not have under that path.
        val a = h.insertLocal(Note(title = "A", content = "same body", modifiedDate = 5L))
        h.idMappingDao.insert(
            IdMapping(localNoteId = a, remoteNoteId = null, provider = CloudService.FILE_STORAGE, extras = null,
                isDeletedLocally = false, storageUri = "Old/A.md"),
        )
        h.tree().seedNoteFile("A.md", "A", "same body")
        h.tree().seedNoteFile("B.md", "B", "other body")

        val vm = viewModel(h)
        vm.requestVaultFolderChange(newTree, oldTree)
        vm.resolveFolderSwapNow(SettingsViewModel.FolderSwapChoice.MOVE_NOTES)
        h.flushRemoteActions()
        h.syncNotes()

        assertEquals(listOf("A", "B"), h.notes().map { it.title })
        assertEquals(setOf("A.md", "B.md"), h.vaultFs.snapshotPaths())
        assertEquals(a, h.notes().first { it.title == "A" }.id)
    }

    @Test
    fun move_binnedNote_staysBinned_andNewFileWithSameNameImportsSeparately() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val x = h.insertAndSync(Note(title = "X", content = "old x"))
        h.binAndSync(h.getNote(x)!!)
        h.syncNotes()
        val binnedMapping = h.idMappingDao.getByLocalIdAndProvider(x, CloudService.FILE_STORAGE)!!
        assertTrue(binnedMapping.isDeletedLocally)
        // The new folder has its own X.md with different content.
        h.tree().seedNoteFile("X.md", "X", "new folder x")

        val vm = viewModel(h)
        vm.requestVaultFolderChange(newTree, oldTree)
        vm.resolveFolderSwapNow(SettingsViewModel.FolderSwapChoice.MOVE_NOTES)
        h.flushRemoteActions()

        assertNull(h.idMappingDao.getByLocalIdAndProvider(x, CloudService.FILE_STORAGE))
        assertTrue("X stays in the bin", h.getNote(x)!!.isDeleted)
        assertEquals("old x", h.getNote(x)!!.content)
        val live = h.notes().filter { !it.isDeleted }
        assertEquals(1, live.size)
        assertEquals("new folder x", live.single().content.trim())
        assertTrue(live.single().id != x)
        assertTrue(h.vaultFs.isFile("X.md"))
    }

    @Test
    fun openAsIs_onlyNewFolderNotesRemain_oldFolderFilesUnchanged() = runTest {
        val oldVault = InMemoryVaultFs(caseInsensitive = true)
        val oldHarness = NoteRepositoryVaultHarness(this, oldVault)
        oldHarness.insertAndSync(Note(title = "Old1", content = "o1"))
        oldHarness.insertAndSync(Note(title = "Old2", content = "o2"))
        val before = oldVault.snapshotPaths().associateWith { oldVault.readText(it) }
        // Same database, the "new" folder is another file tree: move the harness onto it.
        val newVault = InMemoryVaultFs(caseInsensitive = true)
        VaultFolderTestHarness(newVault).seedNoteFile("New1.md", "New1", "n1")
        val h = NoteRepositoryVaultHarness(this, newVault)
        // Mirror the old harness' notes and mappings into the new harness' database.
        oldHarness.notes().forEach { n ->
            val id = h.insertLocal(n.copy(id = 0))
            val m = oldHarness.idMappingDao.getByLocalIdAndProvider(n.id, CloudService.FILE_STORAGE)!!
            h.idMappingDao.insert(m.copy(mappingId = 0, localNoteId = id))
        }

        val vm = viewModel(h)
        vm.requestVaultFolderChange(newTree, oldTree)
        vm.resolveFolderSwapNow(SettingsViewModel.FolderSwapChoice.OPEN_AS_IS)
        h.flushRemoteActions()

        assertEquals(listOf("New1"), h.notes().map { it.title })
        assertEquals(before, oldVault.snapshotPaths().associateWith { oldVault.readText(it) })
        assertEquals(setOf("New1.md"), newVault.snapshotPaths())
    }

    @Test
    fun formerLocalOnlyNote_isExportedOnSync_andFlagCleared() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertLocal(Note(title = "Secret", content = "was local", isLocalOnly = true))

        h.syncNotes()

        assertFalse(h.getNote(id)!!.isLocalOnly)
        assertNotNull(h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE))
        assertTrue(h.vaultFs.isFile("Secret.md"))
    }
}
