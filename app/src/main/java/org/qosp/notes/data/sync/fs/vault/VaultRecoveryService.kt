package org.qosp.notes.data.sync.fs.vault

import android.util.Log
import java.io.IOException

/**
 * Discovers durable staging/backup tickets from the vault journal and completes or quarantines them
 * **before** scan or publish treats note files as authoritative.
 */
object VaultRecoveryService {
    private const val TAG = "VaultRecovery"

    data class Outcome(
        val quarantinedPaths: Set<String>,
        val recoveredPaths: Set<String>,
        val writesBlocked: Boolean,
        val scanBlocked: Boolean,
    )

    fun recoverPending(
        store: VaultWriteStore,
        vaultGeneration: Long,
    ): Outcome {
        val journal = store.readJournal()
        if (journal == null || journal.entries.isEmpty()) {
            return Outcome(emptySet(), emptySet(), writesBlocked = false, scanBlocked = false)
        }
        val scope = store.vaultScopeId()
        if (journal.vaultScopeId != scope) {
            Log.w(TAG, "Foreign-scope recovery journal; clearing (scope=$scope)")
            store.writeJournal(null)
            return Outcome(emptySet(), emptySet(), writesBlocked = false, scanBlocked = false)
        }

        if (journal.vaultGeneration != vaultGeneration) {
            // Re-picking the same folder bumps the generation, but a write killed mid-publish may have left
            // the note truncated, with the only good copy in the backup. Keep what can still be recovered
            // (same scope, so same folder) and fence its paths until a later run settles them.
            val recoverable = journal.entries.filter { store.hasRecoveryFiles(it) }
            if (recoverable.isEmpty()) {
                Log.w(TAG, "Stale recovery journal gen=${journal.vaultGeneration} active=$vaultGeneration; nothing recoverable, clearing")
                store.writeJournal(null)
                return Outcome(emptySet(), emptySet(), writesBlocked = false, scanBlocked = false)
            }
            val paths = recoverable.map { it.notePath }
            Log.w(TAG, "Stale recovery journal gen=${journal.vaultGeneration} active=$vaultGeneration; quarantining $paths")
            store.writeJournal(journal.copy(entries = recoverable))
            return Outcome(paths.toSet(), emptySet(), writesBlocked = false, scanBlocked = false)
        }

        val writer = VaultRecoverableWrite(store)
        val quarantined = mutableSetOf<String>()
        val recovered = mutableSetOf<String>()
        val remaining = mutableListOf<VaultRecoveryJournalEntry>()

        for (entry in journal.entries) {
            val ticketStaging = entry.toStagingTicket()
            val onDisk = store.readVerified(entry.notePath)
            val onDiskHash = onDisk?.let { VaultContentHash.ofText(it) }

            if (onDiskHash == entry.expectedContentHash) {
                finalizeEntry(store, entry)
                recovered += entry.notePath
                continue
            }

            val stagingBody = store.readStaging(ticketStaging)
            val completed = when {
                stagingBody != null &&
                    VaultContentHash.ofText(stagingBody) == entry.expectedContentHash &&
                    resumeStaged(writer, store, ticketStaging, entry.expectedContentHash, vaultGeneration) -> true
                onDisk == null -> false
                entry.backupToken != null -> {
                    val backup = entry.toBackupTicket()
                    restoreVerifiedBackup(store, entry.notePath, backup, entry.expectedContentHash)
                }
                else -> false
            }

            if (completed) {
                finalizeEntry(store, entry)
                recovered += entry.notePath
            } else {
                quarantined += entry.notePath
                remaining += entry
            }
        }

        val next = if (remaining.isEmpty()) null else journal.copy(entries = remaining)
        store.writeJournal(next)

        return Outcome(
            quarantinedPaths = quarantined,
            recoveredPaths = recovered,
            writesBlocked = false,
            scanBlocked = false,
        )
    }

    private fun VaultWriteStore.hasRecoveryFiles(entry: VaultRecoveryJournalEntry): Boolean =
        readStaging(entry.toStagingTicket()) != null ||
            (entry.backupToken != null && readBackup(entry.toBackupTicket()) != null)

    private fun finalizeEntry(store: VaultWriteStore, entry: VaultRecoveryJournalEntry) {
        store.clearRecovery(entry.toStagingTicket())
        entry.backupToken?.let { store.clearRecovery(entry.toBackupTicket()) }
    }

    private fun resumeStaged(
        writer: VaultRecoverableWrite,
        store: VaultWriteStore,
        staged: VaultWriteStore.RecoveryTicket,
        expectedHash: String,
        vaultGeneration: Long,
    ): Boolean {
        val body = store.readStaging(staged) ?: return false
        if (VaultContentHash.ofText(body) != expectedHash) return false
        return runCatching {
            writer.resumePending(staged, VaultWriteContext(store.vaultScopeId(), vaultGeneration))
        }.getOrDefault(false)
    }

    private fun restoreVerifiedBackup(
        store: VaultWriteStore,
        notePath: String,
        backup: VaultWriteStore.RecoveryTicket,
        expectedHash: String,
    ): Boolean {
        val onDisk = store.readVerified(notePath)
        if (onDisk != null && VaultContentHash.ofText(onDisk) == expectedHash) {
            return true
        }
        val prior = store.readBackup(backup) ?: return false
        return runCatching {
            store.restoreFromBackup(notePath, backup)
            val after = store.readVerified(notePath) ?: throw IOException("missing after restore")
            VaultContentHash.ofText(after) == VaultContentHash.ofText(prior)
        }.getOrDefault(false)
    }

    private fun VaultRecoveryJournalEntry.toStagingTicket() =
        VaultWriteStore.RecoveryTicket(notePath, stagingToken, stagingStorageName)

    private fun VaultRecoveryJournalEntry.toBackupTicket() =
        VaultWriteStore.RecoveryTicket(notePath, backupToken!!, backupStorageName ?: backupToken!!)
}
