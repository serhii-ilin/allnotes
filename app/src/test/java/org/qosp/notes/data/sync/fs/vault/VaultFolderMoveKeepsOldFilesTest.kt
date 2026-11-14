package org.qosp.notes.data.sync.fs.vault

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.R
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.ui.settings.SettingsViewModel
import org.robolectric.RobolectricTestRunner

/**
 * "Move my notes here" only copies into the new folder; the old folder, which may contain the new one, keeps its
 * files.
 */
@RunWith(RobolectricTestRunner::class)
class VaultFolderMoveKeepsOldFilesTest {

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

    private suspend fun NoteRepositoryVaultHarness.seedMappedNote(title: String, body: String, oldPath: String): Long {
        val id = insertLocal(Note(title = title, content = body))
        idMappingDao.insert(
            IdMapping(
                localNoteId = id,
                remoteNoteId = null,
                provider = CloudService.FILE_STORAGE,
                extras = null,
                isDeletedLocally = false,
                storageUri = oldPath,
            ),
        )
        return id
    }

    @Test
    fun move_intoFolderThatContainsOldOne_keepsFilesAndTheNote() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        // The new folder is the old folder's parent: Notes/Plan.md is the same physical file as old Plan.md.
        h.tree().seedNoteFile(
            "Notes/Plan.md",
            "Plan",
            "plan body",
            attachments = listOf("photo.jpg" to byteArrayOf(1, 2, 3)),
        )
        val id = h.seedMappedNote("Plan", "plan body", oldPath = "Plan.md")

        val vm = viewModel(h)
        vm.requestVaultFolderChange(newTree, oldTree)
        vm.resolveFolderSwapNow(SettingsViewModel.FolderSwapChoice.MOVE_NOTES)
        h.flushRemoteActions()

        assertTrue("the note file is untouched", h.vaultFs.isFile("Notes/Plan.md"))
        assertTrue("the attachments are untouched", h.vaultFs.isFile("Notes/Plan/photo.jpg"))
        val note = h.getNote(id)
        assertNotNull("the note stays live", note)
        assertFalse(note!!.isDeleted)
        assertEquals("Plan", note.title)
        val mapping = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)
        assertNotNull("the note is mapped to the new folder", mapping)
        assertTrue(h.vaultFs.isFile(mapping!!.storageUri!!))
        assertNotNull("the mapped file reads back", h.vaultFs.readText(mapping.storageUri!!))
    }

    @Test
    fun move_emitsOldFilesKeptMessage_andNoRemovalFailure() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        h.tree().seedNoteFile("Notes/Plan.md", "Plan", "plan body")
        h.seedMappedNote("Plan", "plan body", oldPath = "Plan.md")
        val messages = mutableListOf<String>()
        val vm = viewModel(h)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.vaultMessages.collect { messages.add(it) }
        }

        vm.requestVaultFolderChange(newTree, oldTree)
        vm.resolveFolderSwapNow(SettingsViewModel.FolderSwapChoice.MOVE_NOTES)
        h.flushRemoteActions()

        assertTrue(
            "the user is told the old files were kept",
            messages.contains(h.context.getString(R.string.vault_move_old_files_kept)),
        )
        assertFalse("nothing was deleted from the old folder", messages.any { it.contains("Could not remove") })
    }

    @Test
    fun move_whenTheSyncFails_doesNotClaimTheNotesWereCopied() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        h.seedMappedNote("Plan", "plan body", oldPath = "Plan.md")
        h.vaultFs.writable = false
        val messages = mutableListOf<String>()
        val vm = viewModel(h)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.vaultMessages.collect { messages.add(it) }
        }

        vm.requestVaultFolderChange(newTree, oldTree)
        vm.resolveFolderSwapNow(SettingsViewModel.FolderSwapChoice.MOVE_NOTES)
        h.flushRemoteActions()

        assertFalse(messages.contains(h.context.getString(R.string.vault_move_old_files_kept)))
    }
}
