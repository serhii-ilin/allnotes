package org.qosp.notes.data.sync.core

import android.content.Context
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.sync.fs.StorageBackend
import org.qosp.notes.data.sync.fs.StorageConfig
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.data.sync.fs.vault.VaultWriteClock
import org.qosp.notes.di.SyncScope
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository

class BackendProvider(
    private val context: Context,
    preferenceRepository: PreferenceRepository,
    syncingScope: SyncScope,
    private val notebookDao: NotebookDao,
    private val vaultSyncCoordinator: VaultSyncCoordinator,
    private val vaultWriteClock: VaultWriteClock,
) {
    val syncProvider: StateFlow<ISyncBackend?> = combine(
        preferenceRepository.getAll().map { it.cloudService },
        StorageConfig.storageLocation(preferenceRepository),
    ) { _, storageConfig ->
        storageConfig?.let {
            StorageBackend(context, it, notebookDao, vaultSyncCoordinator, vaultWriteClock = vaultWriteClock)
        }
    }.stateIn(syncingScope, SharingStarted.Eagerly, null)

    val isSyncing: Boolean
        get() = syncProvider.value != null
}
