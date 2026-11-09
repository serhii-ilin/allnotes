package org.qosp.notes.data.sync.fs.vault

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Production [VaultRecoveryService] regressions from independent review. */
class VaultRecoveryServiceTest {

    @Before
    fun mockLog() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
    }

    @Test
    fun postPublishCrash_finalAlreadyNew_doesNotRestoreOldBackup() {
        val store = MemoryVaultWriteStore()
        val path = "Work/Note.md"
        val newBody = "NEW"
        val newHash = VaultContentHash.ofText(newBody)
        store.seedVerified(path, newBody)
        val backup = store.saveBackup(path, "OLD")
        store.writeJournal(
            VaultRecoveryJournal(
                vaultScopeId = store.vaultScopeId(),
                vaultGeneration = 1L,
                entries = listOf(
                    VaultRecoveryJournalEntry(
                        notePath = path,
                        stagingToken = "st-gone",
                        backupToken = backup.token,
                        backupStorageName = backup.storageName,
                        expectedContentHash = newHash,
                        phase = VaultRecoveryJournalEntry.PHASE_PUBLISHING,
                    ),
                ),
            ),
        )
        val outcome = VaultRecoveryService.recoverPending(store, 1L)
        assertEquals(newBody, store.readVerified(path))
        assertTrue(path in outcome.recoveredPaths)
        assertFalse(outcome.scanBlocked)
    }

    @Test
    fun staleGenerationSameScope_blocksScanAndKeepsDiskUnchanged() {
        val store = MemoryVaultWriteStore()
        store.seedVerified("Work/Note.md", "PARTIAL")
        store.writeJournal(
            VaultRecoveryJournal(
                vaultScopeId = store.vaultScopeId(),
                vaultGeneration = 1L,
                entries = listOf(
                    VaultRecoveryJournalEntry(
                        notePath = "Work/Note.md",
                        stagingToken = "st-1",
                        expectedContentHash = VaultContentHash.ofText("EXPECTED"),
                        phase = VaultRecoveryJournalEntry.PHASE_STAGED,
                    ),
                ),
            ),
        )
        val outcome = VaultRecoveryService.recoverPending(store, vaultGeneration = 2L)
        assertEquals("PARTIAL", store.readVerified("Work/Note.md"))
        assertTrue(outcome.scanBlocked)
        assertTrue(outcome.writesBlocked)
        assertTrue(outcome.quarantinedPaths.contains("Work/Note.md"))
    }

    @Test
    fun foreignScopeJournal_blocksScan_leavesDiskUntouched() {
        val store = MemoryVaultWriteStore()
        store.seedVerified("Work/Note.md", "PARTIAL")
        store.writeJournal(
            VaultRecoveryJournal(
                vaultScopeId = "other-scope-not-this-tree",
                vaultGeneration = 1L,
                entries = listOf(
                    VaultRecoveryJournalEntry(
                        notePath = "Work/Note.md",
                        stagingToken = "st-1",
                        expectedContentHash = VaultContentHash.ofText("EXPECTED"),
                        phase = VaultRecoveryJournalEntry.PHASE_STAGED,
                    ),
                ),
            ),
        )
        val outcome = VaultRecoveryService.recoverPending(store, 1L)
        assertEquals("PARTIAL", store.readVerified("Work/Note.md"))
        assertTrue(outcome.scanBlocked)
        assertTrue(outcome.writesBlocked)
    }

    @Test
    fun missingTargetEntry_blocksScan() {
        val store = MemoryVaultWriteStore()
        store.writeJournal(
            VaultRecoveryJournal(
                vaultScopeId = store.vaultScopeId(),
                vaultGeneration = 1L,
                entries = listOf(
                    VaultRecoveryJournalEntry(
                        notePath = "Work/Missing.md",
                        stagingToken = "st-1",
                        expectedContentHash = VaultContentHash.ofText("X"),
                        phase = VaultRecoveryJournalEntry.PHASE_STAGED,
                    ),
                ),
            ),
        )
        val outcome = VaultRecoveryService.recoverPending(store, 1L)
        assertTrue(outcome.scanBlocked)
        assertTrue(outcome.quarantinedPaths.contains("Work/Missing.md"))
    }
}
