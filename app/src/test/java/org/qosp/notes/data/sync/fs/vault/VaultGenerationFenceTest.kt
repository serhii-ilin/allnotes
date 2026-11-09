package org.qosp.notes.data.sync.fs.vault

import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Before
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.preferences.PreferenceRepository

/**
 * Generation fencing mirrors app usage: capture at write start vs coordinator after folder change.
 */
class VaultGenerationFenceTest {

    @Before
    fun mockLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @Test
    fun storageWriteContext_invalidAfterFolderBump() = runTest {
        val prefs = mockk<PreferenceRepository>()
        coEvery { prefs.getEncryptedString(PreferenceRepository.VAULT_GENERATION) } returnsMany listOf(
            flowOf("3"),
            flowOf("4"),
        )
        coEvery { prefs.putEncryptedStrings(any()) } returns Unit
        val coordinator = VaultSyncCoordinator(prefs)
        val capturedGen = coordinator.currentGeneration()
        org.junit.Assert.assertEquals(3L, capturedGen)
        coordinator.bumpGeneration()
        assertTrue(coordinator.isStaleMapping(capturedGen))
        assertFalse(coordinator.isStaleMapping(4L))
    }

    @Test
    fun journalEntryFromOldGeneration_notAppliedAfterSwitch() {
        val store = MemoryVaultWriteStore(scopeId = "scope")
        store.seedVerified("Work/Note.md", "old")
        store.writeJournal(
            VaultRecoveryJournal(
                vaultScopeId = store.vaultScopeId(),
                vaultGeneration = 1L,
                entries = listOf(
                    VaultRecoveryJournalEntry(
                        notePath = "Work/Note.md",
                        stagingToken = "st-999",
                        backupToken = null,
                        expectedContentHash = VaultContentHash.ofText("new"),
                        phase = VaultRecoveryJournalEntry.PHASE_STAGED,
                    ),
                ),
            ),
        )
        val outcome = VaultRecoveryService.recoverPending(store, vaultGeneration = 2L)
        org.junit.Assert.assertEquals("old", store.readVerified("Work/Note.md"))
        assertTrue(outcome.recoveredPaths.isEmpty())
    }

}
