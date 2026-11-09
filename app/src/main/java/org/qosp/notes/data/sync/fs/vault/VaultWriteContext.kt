package org.qosp.notes.data.sync.fs.vault

/** Generation + scope captured at start of a vault write (fenced against folder switch). */
data class VaultWriteContext(
    val vaultScopeId: String,
    val vaultGeneration: Long,
)
