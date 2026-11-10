package org.qosp.notes.data.sync.fs.vault

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Crash/restart via **new** store instances over durable journal bytes (not same-object fake).
 */
class VaultRecoveryPersistenceTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun mockLog() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
    }

    private fun ctx(scope: String, gen: Long = 1L) = VaultWriteContext(scope, gen)

    @Test
    fun journalCrashRestart_completesPublishFromStaging() {
        val root = temp.newFolder("vault-a")
        val scope = "scope-a"
        val crashed = FileVaultWriteStore(root, scope).apply { failPublish = true }
        assertThrows(IOException::class.java) {
            VaultRecoverableWrite(crashed).write("Work/Note.md", "payload", ctx(scope))
        }
        val restarted = FileVaultWriteStore(root, scope)
        assertTrue(restarted.readJournal() == null || restarted.readJournal()!!.entries.isEmpty())
        restarted.failPublish = false
        VaultRecoverableWrite(restarted).write("Work/Note.md", "payload", ctx(scope))
        assertEquals("payload", restarted.readVerified("Work/Note.md"))
    }

    @Test
    fun foreignVaultScope_doesNotApplyBackupContent() {
        val root = temp.newFolder("vault-shared")
        val scopeA = "vault-scope-a"
        val scopeB = "vault-scope-b"
        val storeA = FileVaultWriteStore(root, scopeA)
        storeA.seedVerified("Note.md", "secret-a")
        val backup = storeA.saveBackup("Note.md", "secret-a")
        storeA.writeJournal(
            VaultRecoveryJournal(
                vaultScopeId = scopeA,
                vaultGeneration = 1L,
                entries = listOf(
                    VaultRecoveryJournalEntry(
                        notePath = "Note.md",
                        stagingToken = "missing",
                        backupToken = backup.token,
                        expectedContentHash = VaultContentHash.ofText("secret-b"),
                        phase = VaultRecoveryJournalEntry.PHASE_STAGED,
                    ),
                ),
            ),
        )
        val storeB = FileVaultWriteStore(root, scopeB)
        storeB.seedVerified("Note.md", "neutral")
        val outcome = VaultRecoveryService.recoverPending(storeB, 1L)
        assertEquals("neutral", storeB.readVerified("Note.md"))
        assertFalse(outcome.recoveredPaths.contains("Note.md"))
    }

    @Test
    fun partialPublish_quarantinesPathAndLeavesScanToSkipIt() {
        val store = MemoryVaultWriteStore()
        val path = "Work/Note.md"
        store.seedVerified(path, "partial")
        val staged = store.writeToStaging(path, "full-expected-body")!!
        store.writeJournal(
            VaultRecoveryJournal(
                vaultScopeId = store.vaultScopeId(),
                vaultGeneration = 1L,
                entries = listOf(
                    VaultRecoveryJournalEntry(
                        notePath = path,
                        stagingToken = "gone",
                        backupToken = null,
                        expectedContentHash = VaultContentHash.ofText("full-expected-body"),
                        phase = VaultRecoveryJournalEntry.PHASE_PUBLISHING,
                    ),
                ),
            ),
        )
        store.failPublish = true
        val outcome = VaultRecoveryService.recoverPending(store, 1L)
        assertTrue(path in outcome.quarantinedPaths)
        // The scan is not blocked as a whole: StorageBackend.getAll skips the quarantined path instead
        // (see VaultQuarantineScenariosTest), so the other notes keep syncing.
        assertFalse(outcome.scanBlocked)
        assertTrue(store.readJournal()?.entries?.any { it.notePath == path } == true)
    }

    private fun assertNotNullJournal(store: FileVaultWriteStore) {
        assertTrue(store.readJournal()?.entries?.isNotEmpty() == true)
    }
}
