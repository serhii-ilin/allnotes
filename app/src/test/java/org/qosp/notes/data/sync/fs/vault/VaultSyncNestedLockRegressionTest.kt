package org.qosp.notes.data.sync.fs.vault

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.qosp.notes.preferences.PreferenceRepository
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Documents that [VaultSyncCoordinator.withVaultLock] is not re-entrant and that the sync import
 * path must persist baselines without nesting locks.
 */
class VaultSyncNestedLockRegressionTest {

    private lateinit var coordinator: VaultSyncCoordinator

    @Before
    fun setup() {
        val prefs = mockk<PreferenceRepository>()
        coEvery { prefs.getEncryptedString(PreferenceRepository.VAULT_GENERATION) } returns flowOf("1")
        coordinator = VaultSyncCoordinator(prefs)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @Test
    fun nestedWithVaultLock_blocksSecondAcquire() = runTest {
        var nestedFinished = false
        try {
            withTimeout(400.milliseconds) {
                coordinator.withVaultLock {
                    coordinator.withVaultLock {
                        nestedFinished = true
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            assertTrue(!nestedFinished)
            return@runTest
        }
        error("Expected deadlock timeout on nested withVaultLock")
    }

    @Test
    fun syncShape_singleLock_completesBaselinePersistence() = runTest {
        var baselineWritten = false
        withTimeout(2.seconds) {
            coordinator.withVaultLock {
                // Mirrors syncNotes -> applyLocalUpdates create/pull (no inner withVaultLock).
                persistVaultMappingBaselineForCreate(
                    onPersist = { baselineWritten = true },
                )
            }
        }
        assertTrue(baselineWritten)
    }

    /** Production-shaped helper: metadata first, then baseline (same order as NoteRepositoryImpl). */
    private suspend fun persistVaultMappingBaselineForCreate(onPersist: () -> Unit) {
        applyFromRemoteStub()
        onPersist()
    }

    private suspend fun applyFromRemoteStub() = Unit
}
