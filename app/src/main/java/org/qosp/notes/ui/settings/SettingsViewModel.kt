package org.qosp.notes.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.msoul.datastore.EnumPreference
import android.net.Uri
import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.data.sync.fs.vault.VaultTreeAccess
import org.qosp.notes.data.sync.nextcloud.NextcloudConfig
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.preferences.SyncMode
import java.time.Instant

class SettingsViewModel(
    private val preferenceRepository: PreferenceRepository,
    private val noteRepository: NoteRepository,
    private val vaultTreeAccess: VaultTreeAccess,
    private val vaultSyncCoordinator: VaultSyncCoordinator,
) : ViewModel() {

    val appPreferences = preferenceRepository.getAll()
    val loggedInUsername = NextcloudConfig.fromPreferences(preferenceRepository).map { it?.username }

    fun <T> setPreference(pref: T) where T : Enum<T>, T : EnumPreference {
        when (pref) {
            is CloudService -> {
                if (pref in listOf(CloudService.FILE_STORAGE, CloudService.DISABLED)) {
                    setPreference(SyncMode.ALWAYS)
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            preferenceRepository.set(pref)
        }
    }

    suspend fun <T> setPreferenceSuspending(pref: T) where T : Enum<T>, T : EnumPreference {
        preferenceRepository.set(pref)
    }

    fun getEncryptedString(key: String): Flow<String> {
        return preferenceRepository.getEncryptedString(key)
    }

    fun setEncryptedString(key: String, value: String) =
        viewModelScope.launch { preferenceRepository.putEncryptedStrings(key to value) }

    fun clearNextcloudCredentials() = viewModelScope.launch {
        preferenceRepository.putEncryptedStrings(
            PreferenceRepository.NEXTCLOUD_INSTANCE_URL to "",
            PreferenceRepository.NEXTCLOUD_PASSWORD to "",
            PreferenceRepository.NEXTCLOUD_USERNAME to "",
        )
        noteRepository.deleteIdMappingsForCloudService(CloudService.NEXTCLOUD)
    }

    /**
     * Removes all IdMappings for file storage if the current cloud service is FILE_STORAGE
     * and the new storage location is different from the previous one.
     *
     * @param newLocation The new storage location URI as a string
     * @param previousLocation The previous storage location URI as a string
     */
    fun rebuildVaultIndex() = viewModelScope.launch(Dispatchers.IO) {
        noteRepository.syncNotes()
    }

    /**
     * Atomically switch vault folder: URI, cutoff, mapping wipe, and tree cache warm-up.
     */
    fun applyVaultFolderChange(newLocation: String, previousLocation: String) {
        if (newLocation == previousLocation) return
        viewModelScope.launch(Dispatchers.IO) {
            vaultSyncCoordinator.withVaultLock {
                val currentCloudService = preferenceRepository.getAll().first().cloudService
                vaultSyncCoordinator.bumpGeneration()
                preferenceRepository.putEncryptedStrings(
                    PreferenceRepository.STORAGE_LOCATION to newLocation,
                    PreferenceRepository.VAULT_CONFIGURED_AT to Instant.now().epochSecond.toString(),
                )
                if (currentCloudService == CloudService.FILE_STORAGE) {
                    noteRepository.deleteIdMappingsForCloudService(CloudService.FILE_STORAGE)
                }
                vaultTreeAccess.warmFrom(Uri.parse(newLocation))
            }
        }
    }
}
