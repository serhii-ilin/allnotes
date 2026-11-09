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
    )

    fun recoverPending(
        store: VaultWriteStore,
        vaultGeneration: Long,
    ): Outcome {
        val journal = store.readJournal()
        if (journal == null || journal.entries.isEmpty()) {
            return Outcome(emptySet(), emptySet(), writesBlocked = false)
        }
        val scope = store.vaultScopeId()
        if (journal.vaultScopeId != scope) {
            Log.w(TAG, "Ignoring recovery journal for foreign vault scope")
            return Outcome(emptySet(), emptySet(), writesBlocked = false)
        }

        val writer = VaultRecoverableWrite(store)
        val quarantined = mutableSetOf<String>()
        val recovered = mutableSetOf<String>()
        val remaining = mutableListOf<VaultRecoveryJournalEntry>()

        if (journal.vaultGeneration != vaultGeneration) {
            Log.i(TAG, "Skipping recovery journal for stale generation ${journal.vaultGeneration} (active=$vaultGeneration)")
            return Outcome(emptySet(), emptySet(), writesBlocked = false)
        }

        for (entry in journal.entries) {
            val staged = VaultWriteStore.RecoveryTicket(entry.notePath, entry.stagingToken)
            val completed = when {
                resumeStaged(writer, store, staged, entry.expectedContentHash, vaultGeneration) -> true
                entry.backupToken != null -> {
                    val backup = VaultWriteStore.RecoveryTicket(entry.notePath, entry.backupToken)
                    restoreVerifiedBackup(store, entry.notePath, backup)
                }
                else -> false
            }
            if (completed) {
                recovered += entry.notePath
                store.clearRecovery(staged)
                entry.backupToken?.let { store.clearRecovery(VaultWriteStore.RecoveryTicket(entry.notePath, it)) }
            } else {
                if (isPartialPublish(store, entry)) {
                    quarantined += entry.notePath
                }
                remaining += entry
            }
        }

        val next = if (remaining.isEmpty()) null else journal.copy(entries = remaining)
        store.writeJournal(next)

        val blockWrites = quarantined.isNotEmpty() &&
            remaining.any { quarantined.contains(it.notePath) }
        return Outcome(quarantined, recovered, writesBlocked = blockWrites)
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
    ): Boolean {
        val prior = store.readBackup(backup) ?: return false
        return runCatching {
            store.restoreFromBackup(notePath, backup)
            val onDisk = store.readVerified(notePath) ?: throw IOException("missing after restore")
            VaultContentHash.ofText(onDisk) == VaultContentHash.ofText(prior)
        }.getOrDefault(false)
    }

    private fun isPartialPublish(store: VaultWriteStore, entry: VaultRecoveryJournalEntry): Boolean {
        val onDisk = store.readVerified(entry.notePath) ?: return false
        val hash = VaultContentHash.ofText(onDisk)
        return hash != entry.expectedContentHash
    }
}
