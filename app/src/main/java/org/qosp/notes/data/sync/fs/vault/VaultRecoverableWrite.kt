package org.qosp.notes.data.sync.fs.vault

import java.io.IOException

/**
 * Provider-agnostic recoverable write: never discard last verified content until a new
 * verified version is published. SAF does not guarantee atomic rename; callers may leave
 * [VaultWriteStore.RecoveryTicket] for restart.
 */
class VaultRecoverableWrite(private val store: VaultWriteStore) {

    data class Outcome(
        val published: Boolean,
        val recovery: VaultWriteStore.RecoveryTicket?,
    )

    fun write(relativePath: String, newText: String): Outcome {
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

        return try {
            store.publishFromStaging(relativePath, staged)
            val onDisk = store.readVerified(relativePath)
                ?: throw IOException("Missing after publish: $relativePath")
            if (VaultContentHash.ofText(onDisk) != expectedHash) {
                throw IOException("Publish verify failed for $relativePath")
            }
            backup?.let { store.clearRecovery(it) }
            Outcome(published = true, recovery = null)
        } catch (e: IOException) {
            if (prior != null && backup != null) {
                store.restoreFromBackup(relativePath, backup)
            }
            throw e
        }
    }

    fun resumePending(recovery: VaultWriteStore.RecoveryTicket): Boolean {
        val body = store.readRecovery(recovery) ?: return false
        return runCatching {
            write(recovery.relativePath, body)
            true
        }.getOrDefault(false)
    }
}

/** Testable storage surface; [org.qosp.notes.data.sync.fs.StorageBackend] adapts SAF. */
interface VaultWriteStore {
    data class RecoveryTicket(val relativePath: String, val token: String)

    fun readVerified(relativePath: String): String?
    fun writeToStaging(relativePath: String, content: String): RecoveryTicket?
    fun readStaging(ticket: RecoveryTicket): String?
    fun publishFromStaging(relativePath: String, staging: RecoveryTicket)
    fun saveBackup(relativePath: String, content: String): RecoveryTicket
    fun readRecovery(ticket: RecoveryTicket): String?
    fun clearRecovery(ticket: RecoveryTicket)
    fun restoreFromBackup(relativePath: String, backup: RecoveryTicket)
}
