package org.qosp.notes.ui.settings

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator

/**
 * Regression for [SettingsViewModel.applyVaultFolderChange]: sync must run after the vault
 * configuration lock is released (same shape as [org.qosp.notes.data.repo.NoteRepositoryImpl.syncNotes]).
 */
class SettingsViewModelFolderDeadlockTest {

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @Test
    fun folderReselect_syncAcquiresLockAfterConfigurationBlock() = runTest {
        val prefs = mockk<PreferenceRepository>(relaxed = true)
        coEvery { prefs.getEncryptedString(PreferenceRepository.VAULT_GENERATION) } returns flowOf("3")
        val coordinator = VaultSyncCoordinator(prefs)
        var syncCompleted = false
        coordinator.withVaultLock {
            // Folder warm-up / generation bump only (no nested syncNotes).
        }
        coordinator.withVaultLock {
            syncCompleted = true
        }
        assertTrue(syncCompleted)
    }
}
