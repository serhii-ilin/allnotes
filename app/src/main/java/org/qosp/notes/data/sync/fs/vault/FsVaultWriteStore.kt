package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * [VaultWriteStore] with the same recovery journal layout as [SafVaultWriteStore], publishing via [VaultFs].
 */
class FsVaultWriteStore(
    context: Context,
    private val fs: VaultFs,
) : VaultWriteStore {

    private val recoveryRoot: File =
        File(context.filesDir, "vault-recovery/${fs.scopeId}").apply { mkdirs() }

    private val pendingDir: File
        get() = File(recoveryRoot, "pending").apply { mkdirs() }

    private val backupDir: File
        get() = File(recoveryRoot, "backup").apply { mkdirs() }

    override fun vaultScopeId(): String = fs.scopeId

    override fun readJournal(): VaultRecoveryJournal? {
        val file = File(recoveryRoot, "journal.json")
        if (!file.exists()) return null
        return VaultRecoveryJournalCodec.decode(file.readText())
    }

    override fun writeJournal(journal: VaultRecoveryJournal?) {
        val file = File(recoveryRoot, "journal.json")
        if (journal == null) {
            file.delete()
            return
        }
        file.writeText(VaultRecoveryJournalCodec.encode(journal))
    }

    override fun readVerified(relativePath: String): String? = fs.readText(relativePath)

    override fun writeToStaging(relativePath: String, content: String): VaultWriteStore.RecoveryTicket? {
        if (content.isEmpty()) throw IOException("empty write")
        val token = "st_${VaultContentHash.ofText(relativePath + content.length).take(16)}"
        File(pendingDir, token).writeText(content)
        return VaultWriteStore.RecoveryTicket(relativePath, token, token)
    }

    override fun readStaging(ticket: VaultWriteStore.RecoveryTicket): String? {
        val file = File(pendingDir, ticket.storageName)
        if (!file.exists()) return null
        return file.readText()
    }

    override fun publishFromStaging(relativePath: String, ticket: VaultWriteStore.RecoveryTicket) {
        val body = readStaging(ticket) ?: throw IOException("staging unreadable")
        fs.writeText(VaultPaths.normalizeNotePath(relativePath), body)
        File(pendingDir, ticket.storageName).delete()
    }

    override fun saveBackup(relativePath: String, content: String): VaultWriteStore.RecoveryTicket {
        val token = "bk_${VaultContentHash.ofText(relativePath + content).take(16)}"
        File(backupDir, token).writeText(content)
        return VaultWriteStore.RecoveryTicket(relativePath, token, token)
    }

    override fun readBackup(ticket: VaultWriteStore.RecoveryTicket): String? {
        val file = File(backupDir, ticket.storageName)
        if (!file.exists()) return null
        return file.readText()
    }

    override fun readRecovery(ticket: VaultWriteStore.RecoveryTicket): String? =
        readStaging(ticket) ?: readBackup(ticket)

    override fun clearRecovery(ticket: VaultWriteStore.RecoveryTicket) {
        File(pendingDir, ticket.storageName).delete()
        File(backupDir, ticket.storageName).delete()
    }

    override fun restoreFromBackup(relativePath: String, backup: VaultWriteStore.RecoveryTicket) {
        val body = readBackup(backup) ?: return
        fs.writeText(relativePath, body)
    }
}
