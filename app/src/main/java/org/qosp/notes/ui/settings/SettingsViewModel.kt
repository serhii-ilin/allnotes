package org.qosp.notes.ui.settings

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.msoul.datastore.EnumPreference
import org.qosp.notes.R
import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.data.sync.core.Success
import org.qosp.notes.data.sync.fs.vault.SafVaultWriteStore
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.data.sync.fs.vault.VaultTreeAccess
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository
import java.time.Instant

class SettingsViewModel(
    private val context: Context,
    private val preferenceRepository: PreferenceRepository,
    private val noteRepository: NoteRepository,
    private val vaultTreeAccess: VaultTreeAccess,
    private val vaultSyncCoordinator: VaultSyncCoordinator,
) : ViewModel() {

    val appPreferences = preferenceRepository.getAll()

    fun <T> setPreference(pref: T) where T : Enum<T>, T : EnumPreference {
        viewModelScope.launch(Dispatchers.IO) {
            preferenceRepository.set(pref)
        }
    }

    suspend fun <T> setPreferenceSuspending(pref: T) where T : Enum<T>, T : EnumPreference {
        preferenceRepository.set(pref)
    }

    fun getEncryptedString(key: String): Flow<String> = preferenceRepository.getEncryptedString(key)

    fun setEncryptedString(key: String, value: String) =
        viewModelScope.launch { preferenceRepository.putEncryptedStrings(key to value) }

    data class VaultUiState(
        val rescanning: Boolean = false,
        val clearing: Boolean = false,
        val recoveryItems: Int? = null,
    )

    private val _vaultUiState = MutableStateFlow(VaultUiState())
    val vaultUiState: StateFlow<VaultUiState> = _vaultUiState.asStateFlow()

    private val _vaultMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val vaultMessages: SharedFlow<String> = _vaultMessages.asSharedFlow()

    private suspend fun resolveVaultStore(): SafVaultWriteStore? {
        val loc = preferenceRepository.getEncryptedString(PreferenceRepository.STORAGE_LOCATION).first()
        if (loc.isBlank()) return null
        val root = DocumentFile.fromTreeUri(context, Uri.parse(loc)) ?: return null
        return SafVaultWriteStore(context, root)
    }

    fun refreshVaultRecoveryCount() {
        viewModelScope.launch(Dispatchers.IO) { updateRecoveryCount() }
    }

    private suspend fun updateRecoveryCount() {
        val count = resolveVaultStore()?.recoveryItemCount()
        _vaultUiState.update { it.copy(recoveryItems = count) }
    }

    fun rescanVault() {
        if (_vaultUiState.value.rescanning) return
        _vaultUiState.update { it.copy(rescanning = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val loc = preferenceRepository.getEncryptedString(PreferenceRepository.STORAGE_LOCATION).first()
                if (loc.isBlank()) {
                    _vaultMessages.emit(context.getString(R.string.vault_choose_location_first))
                    return@launch
                }
                val result = noteRepository.syncNotes()
                val count = noteRepository.getNotesByCloudService(CloudService.FILE_STORAGE).size
                val message = if (result is Success) {
                    context.resources.getQuantityString(R.plurals.vault_rescan_done, count, count)
                } else {
                    result.message?.let { context.getString(R.string.vault_rescan_failed_reason, it) }
                        ?: context.getString(R.string.vault_rescan_failed)
                }
                _vaultMessages.emit(message)
            } finally {
                _vaultUiState.update { it.copy(rescanning = false) }
                updateRecoveryCount()
            }
        }
    }

    fun clearVaultRecoveryState() {
        if (_vaultUiState.value.clearing) return
        viewModelScope.launch(Dispatchers.IO) {
            val store = resolveVaultStore()
            if (store == null) {
                _vaultMessages.emit(context.getString(R.string.vault_choose_location_first))
                return@launch
            }
            _vaultUiState.update { it.copy(clearing = true) }
            try {
                val cleared = vaultSyncCoordinator.withVaultLock { store.clearAllRecoveryState() }
                val message = if (cleared > 0) {
                    context.resources.getQuantityString(R.plurals.vault_recovery_cleared, cleared, cleared)
                } else {
                    context.getString(R.string.vault_nothing_to_clear)
                }
                _vaultMessages.emit(message)
            } finally {
                _vaultUiState.update { it.copy(clearing = false) }
                updateRecoveryCount()
            }
        }
    }

    /**
     * Atomically switch vault folder: URI, cutoff, mapping wipe when path changes, and tree warm-up.
     */
    fun applyVaultFolderChange(newLocation: String, previousLocation: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val triggerSync = vaultSyncCoordinator.withVaultLock {
                if (newLocation == previousLocation && newLocation.isNotBlank()) {
                    vaultTreeAccess.warmFrom(Uri.parse(newLocation))
                    return@withVaultLock true
                }
                vaultSyncCoordinator.bumpGeneration()
                preferenceRepository.putEncryptedStrings(
                    PreferenceRepository.STORAGE_LOCATION to newLocation,
                    PreferenceRepository.VAULT_CONFIGURED_AT to Instant.now().epochSecond.toString(),
                )
                if (previousLocation.isNotBlank() && newLocation != previousLocation) {
                    noteRepository.deleteIdMappingsForCloudService(CloudService.FILE_STORAGE)
                }
                vaultTreeAccess.warmFrom(Uri.parse(newLocation))
                true
            }
            if (triggerSync) {
                noteRepository.syncNotes()
            }
        }
    }
}
