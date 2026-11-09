package org.qosp.notes.data.sync.fs.vault

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** In-memory [VaultWriteStore] with injectable fault points and durable journal snapshot. */
class MemoryVaultWriteStore(
    private val scopeId: String = "test-scope",
) : VaultWriteStore {
    val files = mutableMapOf<String, String>()
    private val staging = mutableMapOf<String, String>()
    private val backups = mutableMapOf<String, String>()
    private val idGen = AtomicInteger()
    private var journal: VaultRecoveryJournal? = null

    var failStagingOpen = false
    var failPublish = false
    var truncateOnPublish = false
    var failPostPublishRead = false

    fun seedVerified(relativePath: String, content: String) {
        files[relativePath] = content
    }

    override fun vaultScopeId(): String = scopeId

    override fun readJournal(): VaultRecoveryJournal? = journal

    override fun writeJournal(journal: VaultRecoveryJournal?) {
        this.journal = journal
    }

    override fun readVerified(relativePath: String): String? = files[relativePath]

    override fun writeToStaging(relativePath: String, content: String): VaultWriteStore.RecoveryTicket? {
        if (failStagingOpen) return null
        val token = "st-${idGen.incrementAndGet()}"
        staging[token] = content
        return VaultWriteStore.RecoveryTicket(relativePath, token)
    }

    override fun readStaging(ticket: VaultWriteStore.RecoveryTicket): String? = staging[ticket.token]

    override fun publishFromStaging(relativePath: String, staging: VaultWriteStore.RecoveryTicket) {
        if (failPublish) throw IOException("publish failed")
        val body = this.staging[staging.token] ?: throw IOException("missing staging")
        files[relativePath] = if (truncateOnPublish) "" else body
        this.staging.remove(staging.token)
        if (failPostPublishRead) files.remove(relativePath)
    }

    override fun saveBackup(relativePath: String, content: String): VaultWriteStore.RecoveryTicket {
        val token = "bk-${idGen.incrementAndGet()}"
        backups[token] = content
        return VaultWriteStore.RecoveryTicket(relativePath, token)
    }

    override fun readBackup(ticket: VaultWriteStore.RecoveryTicket): String? = backups[ticket.token]

    override fun readRecovery(ticket: VaultWriteStore.RecoveryTicket): String? =
        readStaging(ticket) ?: readBackup(ticket)

    override fun clearRecovery(ticket: VaultWriteStore.RecoveryTicket) {
        staging.remove(ticket.token)
        backups.remove(ticket.token)
    }

    override fun restoreFromBackup(relativePath: String, backup: VaultWriteStore.RecoveryTicket) {
        val body = backups[backup.token] ?: return
        files[relativePath] = body
    }
}
