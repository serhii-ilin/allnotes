package org.qosp.notes.data.sync.fs.vault

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Filesystem-backed store simulating durable SAF recovery across **new JVM instances**.
 */
class FileVaultWriteStore(
    private val rootDir: File,
    private val scopeId: String,
) : VaultWriteStore {
    private val verifiedDir = File(rootDir, "verified").apply { mkdirs() }
    private val pendingDir = File(rootDir, "pending").apply { mkdirs() }
    private val backupDir = File(rootDir, "backups").apply { mkdirs() }
    private val journalFile = File(rootDir, "journal.json")
    private val idGen = AtomicInteger()

    var failPublish = false

    fun seedVerified(relativePath: String, content: String) {
        fileForNote(relativePath).writeText(content)
    }

    override fun vaultScopeId(): String = scopeId

    override fun readJournal(): VaultRecoveryJournal? {
        if (!journalFile.exists()) return null
        return VaultRecoveryJournalCodec.decode(journalFile.readText())
    }

    override fun writeJournal(journal: VaultRecoveryJournal?) {
        if (journal == null) {
            journalFile.delete()
            return
        }
        journalFile.writeText(VaultRecoveryJournalCodec.encode(journal))
    }

    override fun readVerified(relativePath: String): String? {
        val f = fileForNote(relativePath)
        return if (f.isFile) f.readText() else null
    }

    override fun writeToStaging(relativePath: String, content: String): VaultWriteStore.RecoveryTicket? {
        val token = "st-${idGen.incrementAndGet()}"
        val f = File(pendingDir, token)
        if (!f.writeText(content).let { true }) return null
        return VaultWriteStore.RecoveryTicket(relativePath, token)
    }

    override fun readStaging(ticket: VaultWriteStore.RecoveryTicket): String? {
        val f = File(pendingDir, ticket.token)
        return if (f.isFile) f.readText() else null
    }

    override fun publishFromStaging(relativePath: String, staging: VaultWriteStore.RecoveryTicket) {
        if (failPublish) throw IOException("publish failed")
        val body = readStaging(staging) ?: throw IOException("missing staging")
        fileForNote(relativePath).writeText(body)
        File(pendingDir, staging.token).delete()
    }

    override fun saveBackup(relativePath: String, content: String): VaultWriteStore.RecoveryTicket {
        val token = "bk-${idGen.incrementAndGet()}"
        File(backupDir, token).writeText(content)
        return VaultWriteStore.RecoveryTicket(relativePath, token)
    }

    override fun readBackup(ticket: VaultWriteStore.RecoveryTicket): String? {
        val f = File(backupDir, ticket.token)
        return if (f.isFile) f.readText() else null
    }

    override fun readRecovery(ticket: VaultWriteStore.RecoveryTicket): String? =
        readStaging(ticket) ?: readBackup(ticket)

    override fun clearRecovery(ticket: VaultWriteStore.RecoveryTicket) {
        File(pendingDir, ticket.token).delete()
        File(backupDir, ticket.token).delete()
    }

    override fun restoreFromBackup(relativePath: String, backup: VaultWriteStore.RecoveryTicket) {
        val body = readBackup(backup) ?: return
        fileForNote(relativePath).writeText(body)
    }

    private fun fileForNote(relativePath: String): File {
        val norm = relativePath.replace('\\', '/')
        val f = File(verifiedDir, norm)
        f.parentFile?.mkdirs()
        return f
    }
}
