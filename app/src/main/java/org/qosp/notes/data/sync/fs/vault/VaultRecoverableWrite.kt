package org.qosp.notes.data.sync.fs.vault

import java.io.IOException

/**
 * Provider-agnostic recoverable write: never discard last verified content until a new
 * verified version is published. SAF does not guarantee atomic rename; a durable
 * [VaultRecoveryJournal] survives process restart.
 */
class VaultRecoverableWrite(private val store: VaultWriteStore) {

    data class Outcome(
        val published: Boolean,
        val recovery: VaultWriteStore.RecoveryTicket?,
    )

    fun write(relativePath: String, newText: String, context: VaultWriteContext): Outcome {
        require(context.vaultScopeId == store.vaultScopeId()) {
            "Vault write context scope mismatch"
        }
        val expectedHash = VaultContentHash.ofText(newText)
        val bytes = newText.encodeToByteArray()
        if (bytes.isEmpty()) throw IOException("Refusing to write empty content to $relativePath")

        val prior = store.readVerified(relativePath)
        var backup: VaultWriteStore.RecoveryTicket? = null
        if (prior != null) {
            backup = store.saveBackup(relativePath, prior)
        }

        val staged = store.writeToStaging(relativePath, newText)
            ?: throw IOException("Staging write failed for $relativePath")
        val stagedBody = store.readStaging(staged)
            ?: throw IOException("Cannot read staging for $relativePath")
        if (VaultContentHash.ofText(stagedBody) != expectedHash) {
            throw IOException("Staging verify failed for $relativePath")
        }
        if (stagedBody.encodeToByteArray().size != bytes.size) {
            throw IOException("Staging short write for $relativePath")
        }

        val journalEntry = VaultRecoveryJournalEntry(
            notePath = relativePath,
            stagingToken = staged.token,
            stagingStorageName = staged.storageName,
            backupToken = backup?.token,
            backupStorageName = backup?.storageName,
            expectedContentHash = expectedHash,
            phase = VaultRecoveryJournalEntry.PHASE_STAGED,
        )
        persistJournal(journalEntry, context)

        return try {
            persistJournal(journalEntry.copy(phase = VaultRecoveryJournalEntry.PHASE_PUBLISHING), context)
            store.publishFromStaging(relativePath, staged)
            val onDisk = store.readVerified(relativePath)
                ?: throw IOException("Missing after publish: $relativePath")
            if (VaultContentHash.ofText(onDisk) != expectedHash) {
                throw IOException("Publish verify failed for $relativePath")
            }
            backup?.let { store.clearRecovery(it) }
            store.clearRecovery(staged)
            clearJournalEntry(relativePath, context)
            Outcome(published = true, recovery = null)
        } catch (e: IOException) {
            store.clearRecovery(staged)
            backup?.let { store.clearRecovery(it) }
            if (prior != null && backup != null) {
                store.restoreFromBackup(relativePath, backup)
            }
            clearJournalEntry(relativePath, context)
            throw e
        }
    }

    fun resumePending(ticket: VaultWriteStore.RecoveryTicket, context: VaultWriteContext): Boolean {
        val body = store.readStaging(ticket) ?: return false
        val expectedHash = VaultContentHash.ofText(body)
        return runCatching {
            persistJournal(
                VaultRecoveryJournalEntry(
                    notePath = ticket.relativePath,
                    stagingToken = ticket.token,
                    stagingStorageName = ticket.storageName,
                    expectedContentHash = expectedHash,
                    phase = VaultRecoveryJournalEntry.PHASE_PUBLISHING,
                ),
                context,
            )
            store.publishFromStaging(ticket.relativePath, ticket)
            val onDisk = store.readVerified(ticket.relativePath)
                ?: throw IOException("Missing after resume publish")
            if (VaultContentHash.ofText(onDisk) != expectedHash) {
                throw IOException("Resume publish verify failed")
            }
            store.clearRecovery(ticket)
            clearJournalEntry(ticket.relativePath, context)
            true
        }.getOrDefault(false)
    }

    private fun persistJournal(entry: VaultRecoveryJournalEntry, context: VaultWriteContext) {
        val existing = store.readJournal()
        val base = when {
            existing == null -> VaultRecoveryJournal(context.vaultScopeId, context.vaultGeneration)
            existing.vaultScopeId != context.vaultScopeId ->
                VaultRecoveryJournal(context.vaultScopeId, context.vaultGeneration)
            else -> existing.copy(vaultGeneration = context.vaultGeneration)
        }
        val merged = base.copy(
            entries = base.entries.filterNot { it.notePath == entry.notePath } + entry,
        )
        store.writeJournal(merged)
    }

    private fun clearJournalEntry(notePath: String, context: VaultWriteContext) {
        val existing = store.readJournal() ?: return
        if (existing.vaultScopeId != context.vaultScopeId) return
        val next = existing.copy(entries = existing.entries.filterNot { it.notePath == notePath })
        store.writeJournal(if (next.entries.isEmpty()) null else next)
    }
}

/** Testable storage surface; [org.qosp.notes.data.sync.fs.StorageBackend] adapts SAF. */
interface VaultWriteStore {
    data class RecoveryTicket(
        val relativePath: String,
        val token: String,
        /** Actual SAF document name (may differ from [token] when provider appends an extension). */
        val storageName: String = token,
    )

    fun vaultScopeId(): String
    fun readJournal(): VaultRecoveryJournal?
    fun writeJournal(journal: VaultRecoveryJournal?)

    fun readVerified(relativePath: String): String?
    fun writeToStaging(relativePath: String, content: String): RecoveryTicket?
    fun readStaging(ticket: RecoveryTicket): String?
    fun publishFromStaging(relativePath: String, staging: RecoveryTicket)
    fun saveBackup(relativePath: String, content: String): RecoveryTicket
    fun readBackup(ticket: RecoveryTicket): String?
    fun readRecovery(ticket: RecoveryTicket): String?
    fun clearRecovery(ticket: RecoveryTicket)
    fun restoreFromBackup(relativePath: String, backup: RecoveryTicket)
}
