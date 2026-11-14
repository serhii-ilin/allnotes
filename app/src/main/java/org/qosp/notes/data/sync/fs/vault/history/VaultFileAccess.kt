package org.qosp.notes.data.sync.fs.vault.history

import org.qosp.notes.data.sync.fs.vault.VaultFs

/**
 * The vault tree as the folder history sees it: plain relative paths, plain text.
 * Kept this small so the history engine runs against [VaultFs] in tests and SAF in the app.
 */
interface VaultFileAccess {
    /** Identifies this notes folder, so identity, sequence and queued actions never cross folder boundaries. */
    val historyScope: String

    /** File text, or null when the file does not exist. */
    suspend fun readVaultFile(relativePath: String): String?

    /** Children of a folder; empty when the folder does not exist; throws when the listing fails. */
    suspend fun listVaultDir(relativePath: String): List<VaultFs.VaultFsEntry>

    /** Writes through the crash-safe vault write path. */
    suspend fun writeVaultFile(relativePath: String, text: String)

    /** Deletes one file, as folder-history retention does with its own old month logs; false when it stayed. */
    suspend fun deleteVaultFile(relativePath: String): Boolean
}
