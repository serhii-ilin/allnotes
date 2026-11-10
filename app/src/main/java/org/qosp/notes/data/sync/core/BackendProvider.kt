package org.qosp.notes.data.sync.core

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import org.qosp.notes.data.dao.IdMappingDao
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.sync.fs.StorageBackend
import org.qosp.notes.data.sync.fs.StorageConfig
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.data.sync.fs.vault.VaultWriteClock
import org.qosp.notes.di.SyncScope
import org.qosp.notes.preferences.PreferenceRepository

class BackendProvider(
    private val context: Context,
    preferenceRepository: PreferenceRepository,
    syncingScope: SyncScope,
    private val notebookDao: NotebookDao,
    private val idMappingDao: IdMappingDao,
    private val vaultSyncCoordinator: VaultSyncCoordinator,
    private val vaultWriteClock: VaultWriteClock,
) {
    /**
     * A backend exists only for a folder the user has confirmed (`VAULT_CONFIGURED_AT` set). An install upgraded
     * from the old app keeps its folder unconfirmed until the user agrees, and until then neither sync nor note
     * saves may write to it.
     */
    val syncProvider: StateFlow<ISyncBackend?> = combine(
        preferenceRepository.getEncryptedString(PreferenceRepository.STORAGE_LOCATION),
        preferenceRepository.getEncryptedString(PreferenceRepository.VAULT_CONFIGURED_AT),
    ) { location, configuredAt -> location.takeIf { isConfirmedVaultFolder(it, configuredAt) } }
        .distinctUntilChanged()
        .map { location ->
            location?.let { StorageConfig(it.toUri()) }?.let {
                StorageBackend(context, it, notebookDao, idMappingDao, vaultSyncCoordinator, vaultWriteClock = vaultWriteClock)
            }
        }
        .stateIn(syncingScope, SharingStarted.Eagerly, null)

    val isSyncing: Boolean
        get() = syncProvider.value != null

    /** Forces the next vault scan to re-read every note (manual "rescan vault now"). */
    fun clearVaultScanCache() {
        (syncProvider.value as? StorageBackend)?.clearScanCache()
    }

    /**
     * Waits until [syncProvider] serves [location]. The backend is rebuilt from preference flows, so a sync
     * started right after saving a folder could otherwise run with no backend, or with the previous folder's.
     */
    suspend fun awaitBackendFor(location: String) {
        withTimeoutOrNull(BACKEND_SWITCH_TIMEOUT_MS) {
            syncProvider.first { (it as? StorageBackend)?.config?.location?.toString() == location }
        }
    }

    private companion object {
        const val BACKEND_SWITCH_TIMEOUT_MS = 5_000L
    }
}

fun isConfirmedVaultFolder(location: String, configuredAt: String): Boolean =
    location.isNotBlank() && configuredAt.isNotBlank()
