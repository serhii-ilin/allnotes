package org.qosp.notes.data.sync.fs.vault

import java.io.IOException

/**
 * [VaultWriteStore] backed by a [VaultFs] (tests and direct tree writes).
 */
class FsVaultWriteStore(private val fs: VaultFs) : VaultWriteStore {

    override fun vaultScopeId(): String = fs.scopeId

    override fun readJournal(): VaultRecoveryJournal? = null

    override fun writeJournal(journal: VaultRecoveryJournal?) = Unit

    override fun readVerified(relativePath: String): String? = fs.readText(relativePath)

    override fun writeToStaging(relativePath: String, content: String): VaultWriteStore.RecoveryTicket? {
        if (content.isEmpty()) throw IOException("empty write")
        val token = "st_${VaultContentHash.ofText(relativePath + content.length).take(16)}"
        staging[token] = content
        return VaultWriteStore.RecoveryTicket(relativePath, token, token)
    }

    override fun readStaging(ticket: VaultWriteStore.RecoveryTicket): String? = staging[ticket.storageName]

    override fun publishFromStaging(relativePath: String, ticket: VaultWriteStore.RecoveryTicket) {
        val body = readStaging(ticket) ?: throw IOException("staging unreadable")
        fs.writeText(VaultPaths.normalizeNotePath(relativePath), body)
        staging.remove(ticket.storageName)
    }

    override fun saveBackup(relativePath: String, content: String): VaultWriteStore.RecoveryTicket {
        val token = "bk_${VaultContentHash.ofText(relativePath).take(12)}"
        backups[token] = content
        return VaultWriteStore.RecoveryTicket(relativePath, token, token)
    }

    override fun readBackup(ticket: VaultWriteStore.RecoveryTicket): String? = backups[ticket.storageName]

    override fun readRecovery(ticket: VaultWriteStore.RecoveryTicket): String? = readStaging(ticket)

    override fun clearRecovery(ticket: VaultWriteStore.RecoveryTicket) {
        staging.remove(ticket.storageName)
        backups.remove(ticket.storageName)
    }

    override fun restoreFromBackup(relativePath: String, backup: VaultWriteStore.RecoveryTicket) {
        val body = readBackup(backup) ?: throw IOException("backup unreadable")
        fs.writeText(relativePath, body)
    }

    private val staging = mutableMapOf<String, String>()
    private val backups = mutableMapOf<String, String>()
}
