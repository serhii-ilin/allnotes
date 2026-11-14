package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qosp.notes.preferences.PreferenceRepository

/**
 * Single serialization point for vault pull/push/refresh vs folder reconfiguration.
 * **Not re-entrant:** callers must not invoke [withVaultLock] while already inside [withVaultLock]
 * (e.g. [org.qosp.notes.data.repo.NoteRepositoryImpl.syncNotes] holds the lock while applying imports).
 */
class VaultSyncCoordinator(
    private val preferenceRepository: PreferenceRepository,
) {
    private val vaultOpsMutex = Mutex()

    suspend fun <T> withVaultLock(block: suspend () -> T): T = vaultOpsMutex.withLock { block() }

    suspend fun currentGeneration(): Long =
        preferenceRepository.getEncryptedString(PreferenceRepository.VAULT_GENERATION)
            .first()
            .toLongOrNull() ?: 0L

    suspend fun bumpGeneration(): Long {
        val next = currentGeneration() + 1L
        preferenceRepository.putEncryptedStrings(
            PreferenceRepository.VAULT_GENERATION to next.toString(),
        )
        return next
    }

    suspend fun isStaleMapping(mappingGeneration: Long): Boolean =
        mappingGeneration != currentGeneration()
}
