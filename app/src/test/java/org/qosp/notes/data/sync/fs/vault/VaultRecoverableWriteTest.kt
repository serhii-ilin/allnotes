package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VaultRecoverableWriteTest {

    @Test
    fun successfulWrite_publishesVerifiedContent() {
        val store = MemoryVaultWriteStore()
        val outcome = VaultRecoverableWrite(store).write("Work/Note.md", "---\n---\nbody\n")
        assertTrue(outcome.published)
        assertEquals("---\n---\nbody\n", store.readVerified("Work/Note.md"))
    }

    @Test
    fun publishFailure_restoresPriorVerifiedContent() {
        val store = MemoryVaultWriteStore()
        store.seedVerified("Work/Note.md", "original")
        store.failPublish = true
        assertThrows(IOException::class.java) {
            VaultRecoverableWrite(store).write("Work/Note.md", "replacement")
        }
        assertEquals("original", store.readVerified("Work/Note.md"))
    }

    @Test
    fun stagingOpenFailure_leavesPriorContent() {
        val store = MemoryVaultWriteStore()
        store.seedVerified("Work/Note.md", "original")
        store.failStagingOpen = true
        assertThrows(IOException::class.java) {
            VaultRecoverableWrite(store).write("Work/Note.md", "replacement")
        }
        assertEquals("original", store.readVerified("Work/Note.md"))
    }

    @Test
    fun restartRecovery_resumesPendingStaging() {
        val store = MemoryVaultWriteStore()
        store.failPublish = true
        assertThrows(IOException::class.java) {
            VaultRecoverableWrite(store).write("Work/Note.md", "payload")
        }
        store.failPublish = false
        val stagingTicket = VaultWriteStore.RecoveryTicket("Work/Note.md", "st-1")
        assertTrue(VaultRecoverableWrite(store).resumePending(stagingTicket))
        assertEquals("payload", store.readVerified("Work/Note.md"))
    }

    @Test
    fun truncatedPublish_failsAndRestoresBackup() {
        val store = MemoryVaultWriteStore()
        store.seedVerified("Work/Note.md", "original-body")
        store.truncateOnPublish = true
        assertThrows(IOException::class.java) {
            VaultRecoverableWrite(store).write("Work/Note.md", "replacement-body")
        }
        assertEquals("original-body", store.readVerified("Work/Note.md"))
    }
}
