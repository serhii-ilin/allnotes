package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class SafStagingRenameFailTest {

    @Test
    fun renameFails_usesActualProviderName() {
        val inner = MemoryVaultWriteStore()
        val store = RenameFailingVaultWriteStore(inner)
        val ticket = store.writeToStaging("Work/Note.md", "payload")!!
        assertEquals("st-1.md", ticket.storageName)
        assertEquals("payload", store.readStaging(ticket))
    }

    private class RenameFailingVaultWriteStore(
        private val inner: MemoryVaultWriteStore,
    ) : VaultWriteStore by inner {
        override fun writeToStaging(relativePath: String, content: String): VaultWriteStore.RecoveryTicket? {
            val base = inner.writeToStaging(relativePath, content) ?: return null
            return base.copy(storageName = "${base.token}.md")
        }

        override fun readStaging(ticket: VaultWriteStore.RecoveryTicket): String? {
            val actual = ticket.storageName
            return inner.readStaging(
                VaultWriteStore.RecoveryTicket(ticket.relativePath, ticket.token, ticket.token),
            ) ?: inner.readStaging(
                VaultWriteStore.RecoveryTicket(ticket.relativePath, ticket.token, actual),
            )
        }
    }
}
