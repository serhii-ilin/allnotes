package org.qosp.notes.data.sync.core

import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.sync.fs.StorageBackend
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.data.sync.fs.vault.VaultWriteClock
import org.qosp.notes.di.SyncScope
import org.qosp.notes.preferences.PreferenceRepository
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackendProviderTest {

    private val location = MutableStateFlow("")
    private val configuredAt = MutableStateFlow("")

    private fun provider(scope: CoroutineScope): BackendProvider {
        val prefs = mockk<PreferenceRepository>(relaxed = true)
        every { prefs.getEncryptedString(PreferenceRepository.STORAGE_LOCATION) } returns location
        every { prefs.getEncryptedString(PreferenceRepository.VAULT_CONFIGURED_AT) } returns configuredAt
        return BackendProvider(
            ApplicationProvider.getApplicationContext(),
            prefs,
            SyncScope(scope),
            mockk(relaxed = true),
            VaultSyncCoordinator(prefs),
            VaultWriteClock(),
        )
    }

    @Test
    fun unconfirmedFolder_hasNoBackend_untilConfirmed() = runTest {
        val backendProvider = provider(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        location.value = "content://com.android.externalstorage.documents/tree/primary%3ANotes"

        // An upgraded install has a folder but no cutoff: nothing may sync or push to it yet.
        assertNull(backendProvider.syncProvider.value)
        assertEquals(false, backendProvider.isSyncing)

        configuredAt.value = "1700000000"
        backendProvider.awaitBackendFor(location.value)
        assertEquals(
            location.value,
            (backendProvider.syncProvider.value as StorageBackend).config.location.toString(),
        )
    }

    @Test
    fun folderChange_awaitBackendFor_returnsTheNewFoldersBackend() = runTest {
        val backendProvider = provider(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        location.value = "content://com.android.externalstorage.documents/tree/primary%3AOld"
        configuredAt.value = "1"
        val newFolder = "content://com.android.externalstorage.documents/tree/primary%3ANew"

        location.value = newFolder
        configuredAt.value = "2"
        backendProvider.awaitBackendFor(newFolder)

        assertEquals(newFolder, (backendProvider.syncProvider.value as StorageBackend).config.location.toString())
    }
}
