package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VaultRecoverableWriteTest {

    private fun ctx(store: VaultWriteStore, gen: Long = 1L) =
        VaultWriteContext(store.vaultScopeId(), gen)

    @Test
    fun successfulWrite_publishesVerifiedContent() {
        val store = MemoryVaultWriteStore()
        val outcome = VaultRecoverableWrite(store).write("Work/Note.md", "---\n---\nbody\n", ctx(store))
        assertTrue(outcome.published)
        assertEquals("---\n---\nbody\n", store.readVerified("Work/Note.md"))
        assertTrue(store.readJournal() == null || store.readJournal()!!.entries.isEmpty())
    }

    @Test
    fun publishFailure_restoresPriorVerifiedContent() {
        val store = MemoryVaultWriteStore()
        store.seedVerified("Work/Note.md", "original")
        store.failPublish = true
        assertThrows(IOException::class.java) {
            VaultRecoverableWrite(store).write("Work/Note.md", "replacement", ctx(store))
        }
        assertEquals("original", store.readVerified("Work/Note.md"))
        assertNotNull(store.readJournal())
    }

    @Test
    fun restartRecovery_resumesPendingStaging() {
        val store = MemoryVaultWriteStore()
        store.failPublish = true
        assertThrows(IOException::class.java) {
            VaultRecoverableWrite(store).write("Work/Note.md", "payload", ctx(store))
        }
        store.failPublish = false
        val outcome = VaultRecoveryService.recoverPending(store, 1L)
        assertTrue(outcome.recoveredPaths.contains("Work/Note.md"))
        assertEquals("payload", store.readVerified("Work/Note.md"))
    }

    @Test
    fun truncatedPublish_failsAndRestoresBackup() {
        val store = MemoryVaultWriteStore()
        store.seedVerified("Work/Note.md", "original-body")
        store.truncateOnPublish = true
        assertThrows(IOException::class.java) {
            VaultRecoverableWrite(store).write("Work/Note.md", "replacement-body", ctx(store))
        }
        assertEquals("original-body", store.readVerified("Work/Note.md"))
    }
}
