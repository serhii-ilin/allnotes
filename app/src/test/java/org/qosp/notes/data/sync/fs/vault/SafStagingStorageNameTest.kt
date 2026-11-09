package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Provider may change staging document names; [VaultWriteStore.RecoveryTicket.storageName] must match bytes on disk.
 */
class SafStagingStorageNameTest {

    @Test
    fun mutatedStorageName_stillReadableForRecovery() {
        val inner = MemoryVaultWriteStore()
        val store = MutatingNameVaultWriteStore(inner)
        val ctx = VaultWriteContext(store.vaultScopeId(), 1L)
        VaultRecoverableWrite(store).write("Work/Note.md", "payload", ctx)
        assertEquals("payload", store.readVerified("Work/Note.md"))
    }

    private class MutatingNameVaultWriteStore(
        private val inner: MemoryVaultWriteStore,
    ) : VaultWriteStore by inner {
        override fun writeToStaging(relativePath: String, content: String): VaultWriteStore.RecoveryTicket? {
            val base = inner.writeToStaging(relativePath, content) ?: return null
            return base.copy(storageName = "${base.token}.md")
        }

        override fun readStaging(ticket: VaultWriteStore.RecoveryTicket): String? =
            inner.readStaging(
                VaultWriteStore.RecoveryTicket(ticket.relativePath, ticket.token, ticket.token),
            )
    }
}
