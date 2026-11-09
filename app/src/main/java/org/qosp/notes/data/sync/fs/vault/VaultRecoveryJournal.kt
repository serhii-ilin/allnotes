package org.qosp.notes.data.sync.fs.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Durable vault-scoped recovery journal (lives under [.quillpad-staging/recovery]).
 * Entries from another [vaultScopeId] or stale [vaultGeneration] are never applied (no cross-vault leak).
 */
@Serializable
data class VaultRecoveryJournal(
    val vaultScopeId: String,
    val vaultGeneration: Long,
    val entries: List<VaultRecoveryJournalEntry> = emptyList(),
)

@Serializable
data class VaultRecoveryJournalEntry(
    val notePath: String,
    val stagingToken: String,
    val stagingStorageName: String = stagingToken,
    val backupToken: String? = null,
    val backupStorageName: String? = null,
    val expectedContentHash: String,
    val phase: String,
) {
    companion object {
        const val PHASE_STAGED = "STAGED"
        const val PHASE_PUBLISHING = "PUBLISHING"
    }
}

object VaultRecoveryJournalCodec {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun encode(journal: VaultRecoveryJournal): String = json.encodeToString(journal)

    fun decode(raw: String?): VaultRecoveryJournal? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<VaultRecoveryJournal>(raw) }.getOrNull()
    }
}
