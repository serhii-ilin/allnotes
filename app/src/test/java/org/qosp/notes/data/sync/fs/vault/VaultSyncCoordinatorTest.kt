package org.qosp.notes.data.sync.fs.vault

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.preferences.PreferenceRepository

class VaultSyncCoordinatorTest {

    @Test
    fun withVaultLock_serializesConcurrentBlocks() = runTest {
        val prefs = mockk<PreferenceRepository>()
        coEvery { prefs.getEncryptedString(any()) } returns flowOf("1")
        val coordinator = VaultSyncCoordinator(prefs)
        var firstInside = false
        var overlapped = false
        val j1 = async {
            coordinator.withVaultLock {
                firstInside = true
                kotlinx.coroutines.delay(80)
                firstInside = false
            }
        }
        kotlinx.coroutines.delay(10)
        val j2 = async {
            coordinator.withVaultLock {
                if (firstInside) overlapped = true
            }
        }
        j1.await()
        j2.await()
        assertFalse(overlapped)
    }

    @Test
    fun generationFence_detectsStaleMapping() = runTest {
        val prefs = mockk<PreferenceRepository>()
        coEvery { prefs.getEncryptedString(PreferenceRepository.VAULT_GENERATION) } returns flowOf("3")
        val coordinator = VaultSyncCoordinator(prefs)
        assertFalse(coordinator.isStaleMapping(3))
        assertTrue(coordinator.isStaleMapping(2))
    }
}
