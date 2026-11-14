package org.qosp.notes.data.sync.fs.vault

/** Captured when a vault remote op is scheduled; validated inside [org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator.withVaultLock]. */
data class VaultFileOpFence(
    val vaultGeneration: Long,
    val vaultTreeUri: String,
) {
    fun isStale(currentGeneration: Long, currentTreeUri: String): Boolean =
        vaultGeneration != currentGeneration || vaultTreeUri != currentTreeUri
}
