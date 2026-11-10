package org.qosp.notes.ui.settings

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.NoteRepositoryImpl
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.fs.vault.InMemoryVaultFs
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.data.sync.fs.vault.VaultTreeAccess
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelFolderSwapTest {

    private val oldTree = "content://com.test.docs/tree/old"
    private val newTree = "content://com.test.docs/tree/new"

    private class Rig(val h: NoteRepositoryVaultHarness, val repo: NoteRepositoryImpl, val vm: SettingsViewModel, val prefs: PreferenceRepository)

    private fun rig(h: NoteRepositoryVaultHarness, currentLocation: String): Rig {
        val prefs = mockk<PreferenceRepository>(relaxed = true)
        coEvery { prefs.getEncryptedString(any()) } returns flowOf("3")
        coEvery { prefs.getEncryptedString(PreferenceRepository.STORAGE_LOCATION) } returns flowOf(currentLocation)
        val repo = spyk(h.repository)
        val vm = SettingsViewModel(
            h.context,
            prefs,
            repo,
            mockk<BackendProvider>(relaxed = true),
            mockk<VaultTreeAccess>(relaxed = true),
            VaultSyncCoordinator(prefs),
            mockk<org.qosp.notes.ui.reminders.ReminderManager>(relaxed = true),
        )
        return Rig(h, repo, vm, prefs)
    }

    private fun mapping(noteId: Long, path: String?, binned: Boolean) = IdMapping(
        localNoteId = noteId,
        remoteNoteId = null,
        provider = CloudService.FILE_STORAGE,
        extras = null,
        isDeletedLocally = binned,
        storageUri = path,
    )

    private suspend fun NoteRepositoryVaultHarness.titles(): List<String> =
        noteDao.getAll(SortMethod.TITLE_ASC).first().map { it.title }

    private suspend fun NoteRepositoryVaultHarness.seedMapped(title: String, path: String, binned: Boolean = false): Long {
        val id = insertLocal(Note(title = title, content = "body $title", isDeleted = binned))
        idMappingDao.insert(mapping(id, if (binned) null else path, binned))
        return id
    }

    @Test
    fun differentTree_withVault_emitsPrompt_andChangesNothingUntilAnswered() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val active = h.seedMapped("Active", "Active.md")
        val r = rig(h, oldTree)

        r.vm.requestVaultFolderChange(newTree, oldTree)

        val prompt = withTimeout(1_000) { r.vm.folderSwapPrompts.first() }
        assertEquals(SettingsViewModel.FolderSwapPrompt(newTree, oldTree), prompt)
        assertNotNull(h.idMappingDao.getByLocalIdAndProvider(active, CloudService.FILE_STORAGE))
        coVerify(exactly = 0) { r.repo.syncNotes() }
        coVerify(exactly = 0) { r.prefs.putEncryptedStrings(*varargAny { true }) }
    }

    @Test
    fun prompt_staysPendingForARecreatedView_untilAnswered() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        h.seedMapped("Active", "Active.md")
        val r = rig(h, oldTree)
        r.vm.requestVaultFolderChange(newTree, oldTree)

        // A view recreated after rotation collects again and must get the same unanswered prompt.
        withTimeout(1_000) { r.vm.folderSwapPrompts.first() }
        val again = withTimeout(1_000) { r.vm.folderSwapPrompts.first() }
        assertEquals(SettingsViewModel.FolderSwapPrompt(newTree, oldTree), again)

        r.vm.resolveFolderSwapNow(SettingsViewModel.FolderSwapChoice.CANCEL)
        assertNull(withTimeoutOrNull(100) { r.vm.folderSwapPrompts.first() })
    }

    @Test
    fun move_wipesAllMappings_includingBinned_andSyncs() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val active = h.seedMapped("Active", "Active.md")
        val binned = h.seedMapped("Binned", "Binned.md", binned = true)
        val r = rig(h, oldTree)
        r.vm.requestVaultFolderChange(newTree, oldTree)

        r.vm.resolveFolderSwapNow(SettingsViewModel.FolderSwapChoice.MOVE_NOTES)

        assertNull(h.idMappingDao.getByLocalIdAndProvider(active, CloudService.FILE_STORAGE))
        assertNull(h.idMappingDao.getByLocalIdAndProvider(binned, CloudService.FILE_STORAGE))
        coVerify(exactly = 1) { r.repo.syncNotes() }
        coVerify {
            r.prefs.putEncryptedStrings(*varargAny { it.first == PreferenceRepository.STORAGE_LOCATION && it.second == newTree })
        }
    }

    @Test
    fun openAsIs_deletesVaultMappedNotes_keepsUnmapped_leavesVaultFilesAlone_andSyncs() = runTest {
        val oldVault = InMemoryVaultFs()
        oldVault.writeText("Active.md", "---\ntitle: Active\n---\nbody Active")
        val h = NoteRepositoryVaultHarness(this)
        h.tree().seedNoteFile("B.md", "B", "from new folder")
        val active = h.seedMapped("Active", "Active.md")
        val binned = h.seedMapped("Binned", "Binned.md", binned = true)
        h.insertLocal(Note(title = "Unmapped", content = "never exported"))
        val r = rig(h, oldTree)
        r.vm.requestVaultFolderChange(newTree, oldTree)

        r.vm.resolveFolderSwapNow(SettingsViewModel.FolderSwapChoice.OPEN_AS_IS)
        h.flushRemoteActions()

        assertNull(h.getNote(active))
        assertNull(h.idMappingDao.getByLocalIdAndProvider(binned, CloudService.FILE_STORAGE))
        assertNotNull("binned notes stay in the bin", h.getNote(binned))
        assertEquals(listOf("B", "Binned", "Unmapped"), h.titles())
        assertEquals(setOf("Active.md"), oldVault.snapshotPaths())
        coVerify(exactly = 1) { r.repo.syncNotes() }
    }

    @Test
    fun cancel_changesNothing_andDoesNotSync() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val active = h.seedMapped("Active", "Active.md")
        val r = rig(h, oldTree)
        r.vm.requestVaultFolderChange(newTree, oldTree)

        r.vm.resolveFolderSwapNow(SettingsViewModel.FolderSwapChoice.CANCEL)

        assertNotNull(h.idMappingDao.getByLocalIdAndProvider(active, CloudService.FILE_STORAGE))
        assertEquals(listOf("Active"), h.titles())
        coVerify(exactly = 0) { r.repo.syncNotes() }
        coVerify(exactly = 0) { r.prefs.putEncryptedStrings(*varargAny { true }) }
    }

    @Test
    fun sameTree_differentUriString_noPrompt_noWipe_savesNewString() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val active = h.seedMapped("Active", "Active.md")
        val r = rig(h, "content://com.test.docs/tree/primary%3ANotes")

        r.vm.requestVaultFolderChange(
            "content://com.test.docs/tree/primary:Notes",
            "content://com.test.docs/tree/primary%3ANotes",
        )

        assertNotNull(h.idMappingDao.getByLocalIdAndProvider(active, CloudService.FILE_STORAGE))
        coVerify {
            r.prefs.putEncryptedStrings(
                *varargAny { it.first == PreferenceRepository.STORAGE_LOCATION && it.second == "content://com.test.docs/tree/primary:Notes" },
            )
        }
        coVerify(exactly = 0) { r.prefs.putEncryptedStrings(*varargAny { it.first == PreferenceRepository.VAULT_GENERATION }) }
    }

    @Test
    fun firstPick_noVault_noMappings_appliesWithoutPrompt() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val r = rig(h, "")

        r.vm.requestVaultFolderChange(newTree, "")

        coVerify(exactly = 1) { r.repo.syncNotes() }
        coVerify {
            r.prefs.putEncryptedStrings(*varargAny { it.first == PreferenceRepository.STORAGE_LOCATION && it.second == newTree })
        }
    }
}
