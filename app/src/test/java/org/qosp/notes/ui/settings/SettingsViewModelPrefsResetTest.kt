package org.qosp.notes.ui.settings

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.data.sync.fs.vault.VaultTreeAccess
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.ui.needsVaultUpgradeConfirmation
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelPrefsResetTest {

    private fun mapping(noteId: Long, path: String?, binned: Boolean) = IdMapping(
        localNoteId = noteId,
        remoteNoteId = null,
        provider = CloudService.FILE_STORAGE,
        extras = null,
        isDeletedLocally = binned,
        storageUri = path,
    )

    private fun viewModel(h: NoteRepositoryVaultHarness, prefs: PreferenceRepository): SettingsViewModel =
        SettingsViewModel(
            h.context,
            prefs,
            h.repository,
            mockk<BackendProvider>(relaxed = true),
            mockk<VaultTreeAccess>(relaxed = true),
            VaultSyncCoordinator(prefs),
        )

    @Test
    fun blankPreviousLocation_withStaleMappings_wipesActiveKeepsBinned() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val active = h.insertLocal(Note(title = "Active", content = "a"))
        val binned = h.insertLocal(Note(title = "Binned", content = "b", isDeleted = true))
        h.idMappingDao.insert(mapping(active, "Active.md", binned = false))
        h.idMappingDao.insert(mapping(binned, null, binned = true))
        val prefs = mockk<PreferenceRepository>(relaxed = true)
        coEvery { prefs.getEncryptedString(any()) } returns flowOf("3")

        viewModel(h, prefs).configureVaultFolder("content://other/folder", "")

        assertNull(h.idMappingDao.getByLocalIdAndProvider(active, CloudService.FILE_STORAGE))
        assertNotNull(h.idMappingDao.getByLocalIdAndProvider(binned, CloudService.FILE_STORAGE))
        coVerify {
            prefs.putEncryptedStrings(*varargAny { it.first == PreferenceRepository.VAULT_CONFIGURED_AT })
        }
    }

    @Test
    fun blankPreviousLocation_withoutMappings_doesNotTouchMappings() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val prefs = mockk<PreferenceRepository>(relaxed = true)
        coEvery { prefs.getEncryptedString(any()) } returns flowOf("3")

        assertTrue(viewModel(h, prefs).configureVaultFolder("content://other/folder", ""))
        assertEquals(0, h.idMappingDao.getCountByCloudService(CloudService.FILE_STORAGE))
    }

    @Test
    fun upgradeConfirmationNeeded_onlyWhenLocationSetAndCutoffBlank() {
        assertTrue(needsVaultUpgradeConfirmation("content://vault", ""))
        assertEquals(false, needsVaultUpgradeConfirmation("", ""))
        assertEquals(false, needsVaultUpgradeConfirmation("content://vault", "1700000000"))
        assertEquals(false, needsVaultUpgradeConfirmation("", "1700000000"))
    }
}
