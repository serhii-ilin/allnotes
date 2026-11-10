package org.qosp.notes.ui.settings

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.msoul.datastore.EnumPreference
import org.qosp.notes.R
import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.core.Success
import org.qosp.notes.data.sync.fs.isSameVaultTree
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
    private val backendProvider: BackendProvider,
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
                // A manual rescan must not trust the (mtime, size) scan cache.
                backendProvider.clearVaultScanCache()
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

    /** A picked folder that is a different tree than the current vault; waits here until the user answers. */
    data class FolderSwapPrompt(val newLocation: String, val previousLocation: String)

    enum class FolderSwapChoice { MOVE_NOTES, OPEN_AS_IS, CANCEL }

    // State, not a one-shot event: a dialog dismissed by rotation or a recreated fragment is shown again until
    // the user actually answers.
    private val pendingFolderSwap = MutableStateFlow<FolderSwapPrompt?>(null)
    val folderSwapPrompts: Flow<FolderSwapPrompt> = pendingFolderSwap.filterNotNull()

    /**
     * Entry point after the user picked a folder. A different tree than the current vault is not applied: it
     * asks the fragment to show the swap dialog and holds the pick until [resolveFolderSwap].
     */
    fun applyVaultFolderChange(newLocation: String, previousLocation: String) {
        viewModelScope.launch(Dispatchers.IO) { requestVaultFolderChange(newLocation, previousLocation) }
    }

    suspend fun requestVaultFolderChange(newLocation: String, previousLocation: String) {
        if (needsFolderSwapDecision(newLocation, previousLocation)) {
            // A newer pick replaces an unanswered one; its permission is released unless it is the vault.
            pendingFolderSwap.value?.let { releasePermissionUnlessCurrent(it.newLocation, previousLocation) }
            pendingFolderSwap.value = FolderSwapPrompt(newLocation, previousLocation)
            return
        }
        applyAndSync(newLocation, previousLocation, openAsIs = false)
    }

    fun resolveFolderSwap(choice: FolderSwapChoice) {
        viewModelScope.launch(Dispatchers.IO) { resolveFolderSwapNow(choice) }
    }

    suspend fun resolveFolderSwapNow(choice: FolderSwapChoice) {
        val prompt = pendingFolderSwap.getAndUpdate { null } ?: return
        when (choice) {
            FolderSwapChoice.MOVE_NOTES -> applyAndSync(prompt.newLocation, prompt.previousLocation, openAsIs = false)
            FolderSwapChoice.OPEN_AS_IS -> applyAndSync(prompt.newLocation, prompt.previousLocation, openAsIs = true)
            FolderSwapChoice.CANCEL -> releasePermissionUnlessCurrent(prompt.newLocation, prompt.previousLocation)
        }
    }

    private suspend fun applyAndSync(newLocation: String, previousLocation: String, openAsIs: Boolean) {
        if (configureVaultFolder(newLocation, previousLocation, openAsIs)) {
            backendProvider.awaitBackendFor(newLocation)
            noteRepository.syncNotes()
        }
    }

    private suspend fun needsFolderSwapDecision(newLocation: String, previousLocation: String): Boolean {
        if (previousLocation.isNotBlank()) {
            return !isSameVaultTree(Uri.parse(newLocation), Uri.parse(previousLocation))
        }
        // Blank prefs with active mappings left over: those notes belong to an unknown folder.
        return noteRepository.getNotesByCloudService(CloudService.FILE_STORAGE).keys.any { !it.isDeletedLocally }
    }

    private suspend fun releasePermissionUnlessCurrent(location: String, previousLocation: String) {
        val current = preferenceRepository.getEncryptedString(PreferenceRepository.STORAGE_LOCATION).first()
            .ifBlank { previousLocation }
        if (current.isNotBlank() && isSameVaultTree(Uri.parse(location), Uri.parse(current))) return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(location), flags) }
    }

    /**
     * Applies the folder configuration under the vault lock and returns whether a sync should follow.
     * Re-selecting the current tree (even through a differently encoded URI) keeps everything and only confirms
     * it when it has no cutoff yet (an install upgraded from the old app). A blank [previousLocation] with
     * mappings left over means the prefs were reset (for example after a backup restore), so those mappings
     * belong to an unknown folder and are wiped like on a folder change.
     *
     * On a different tree every FILE_STORAGE mapping goes, binned rows included: their recorded path points
     * into the old folder and could "restore" a binned note from a same-named file in the new one. After a
     * prefs reset the tree is unknown, so binned rows stay. With [openAsIs] the notes that were backed by the
     * old folder are also dropped from Room (never from the vault), so only the new folder's notes remain.
     */
    suspend fun configureVaultFolder(
        newLocation: String,
        previousLocation: String,
        openAsIs: Boolean = false,
    ): Boolean = vaultSyncCoordinator.withVaultLock {
        val sameFolder = newLocation.isNotBlank() && previousLocation.isNotBlank() &&
            isSameVaultTree(Uri.parse(newLocation), Uri.parse(previousLocation))
        if (sameFolder) {
            val confirmed = preferenceRepository.getEncryptedString(PreferenceRepository.VAULT_CONFIGURED_AT)
                .first().isNotBlank()
            if (!confirmed) {
                vaultSyncCoordinator.bumpGeneration()
                preferenceRepository.putEncryptedStrings(
                    PreferenceRepository.VAULT_CONFIGURED_AT to Instant.now().epochSecond.toString(),
                )
            }
            if (newLocation != previousLocation) {
                preferenceRepository.putEncryptedStrings(PreferenceRepository.STORAGE_LOCATION to newLocation)
            }
            vaultTreeAccess.warmFrom(Uri.parse(newLocation))
            return@withVaultLock true
        }
        val treeKnown = previousLocation.isNotBlank()
        val staleAfterReset = !treeKnown && noteRepository.countIdMappings(CloudService.FILE_STORAGE) > 0
        if (treeKnown || staleAfterReset) {
            // The scan cache belongs to the folder being left.
            backendProvider.clearVaultScanCache()
            if (openAsIs) {
                val mappedNotes = noteRepository.getNotesByCloudService(CloudService.FILE_STORAGE)
                    .filter { (mapping, _) -> !mapping.isDeletedLocally }
                    .values.filterNotNull()
                noteRepository.deleteNotes(*mappedNotes.toTypedArray(), sync = false)
            }
            if (treeKnown) {
                noteRepository.deleteIdMappingsForCloudService(CloudService.FILE_STORAGE)
            } else {
                noteRepository.deleteActiveIdMappingsForCloudService(CloudService.FILE_STORAGE)
            }
        }
        vaultSyncCoordinator.bumpGeneration()
        preferenceRepository.putEncryptedStrings(
            PreferenceRepository.STORAGE_LOCATION to newLocation,
            PreferenceRepository.VAULT_CONFIGURED_AT to Instant.now().epochSecond.toString(),
        )
        vaultTreeAccess.warmFrom(Uri.parse(newLocation))
        true
    }

    /** The user accepted the folder an upgraded install already had; the caller runs the sync. */
    suspend fun confirmExistingVaultFolder(location: String) {
        configureVaultFolder(location, location)
        backendProvider.awaitBackendFor(location)
    }
}
