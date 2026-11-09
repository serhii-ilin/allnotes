package org.qosp.notes.ui.settings

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.msoul.datastore.EnumPreference
import org.qosp.notes.data.repo.NoteRepository
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

    fun rebuildVaultIndex() = viewModelScope.launch(Dispatchers.IO) {
        noteRepository.syncNotes()
    }

    fun clearVaultRecoveryState() = viewModelScope.launch(Dispatchers.IO) {
        val loc = preferenceRepository.getEncryptedString(PreferenceRepository.STORAGE_LOCATION).first()
        if (loc.isBlank()) return@launch
        val root = DocumentFile.fromTreeUri(context, Uri.parse(loc)) ?: return@launch
        org.qosp.notes.data.sync.fs.vault.SafVaultWriteStore(context, root).clearAllRecoveryState()
    }

    /**
     * Atomically switch vault folder: URI, cutoff, mapping wipe when path changes, and tree warm-up.
     */
    fun applyVaultFolderChange(newLocation: String, previousLocation: String) {
        viewModelScope.launch(Dispatchers.IO) {
            vaultSyncCoordinator.withVaultLock {
                if (newLocation == previousLocation && newLocation.isNotBlank()) {
                    vaultTreeAccess.warmFrom(Uri.parse(newLocation))
                    noteRepository.syncNotes()
                    return@withVaultLock
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
                noteRepository.syncNotes()
            }
        }
    }
}
