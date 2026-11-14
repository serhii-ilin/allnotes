package org.qosp.notes.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.tfcporciuncula.flow.FlowSharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import me.msoul.datastore.EnumPreference
import me.msoul.datastore.getEnum
import me.msoul.datastore.setEnum
import java.io.IOException

class PreferenceRepository(
    val dataStore: DataStore<Preferences>,
    private val sharedPreferences: FlowSharedPreferences
) {
    fun getEncryptedString(key: String): Flow<String> {
        return sharedPreferences.getString(key, "").asFlow()
    }

    fun getAll(): Flow<AppPreferences> {
        return dataStore.data
            .catch {
                if (it is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw it
                }
            }
            .map { prefs ->
                AppPreferences(
                    layoutMode = prefs.getEnum(),
                    themeMode = prefs.getEnum(),
                    darkThemeMode = prefs.getEnum(),
                    colorScheme = prefs.getEnum(),
                    sortMethod = prefs.getEnum(),
                    sortTagsMethod = prefs.getEnum(),
                    sortNavdrawerNotebooksMethod = prefs.getEnum(),
                    backupStrategy = prefs.getEnum(),
                    noteDeletionTime = prefs.getEnum(),
                    historyRetention = prefs.getEnum(),
                    dateFormat = prefs.getEnum(),
                    timeFormat = prefs.getEnum(),
                    openMediaIn = prefs.getEnum(),
                    showDate = prefs.getEnum(),
                    editorFontSize = prefs.getEnum(),
                    showFabChangeMode = prefs.getEnum(),
                    groupNotesWithoutNotebook = prefs.getEnum(),
                    moveCheckedItems = prefs.getEnum(),
                    defaultEditorMode = prefs.getEnum(),
                )
            }
    }

    inline fun <reified T> get(): Flow<T> where T : Enum<T>, T : EnumPreference = dataStore.getEnum()

    suspend fun putEncryptedStrings(vararg pairs: Pair<String, String>) {
        pairs.forEach { (key, value) ->
            sharedPreferences.getString(key).setAndCommit(value)
        }
    }

    suspend fun <T> set(preference: T) where T : Enum<T>, T : EnumPreference = dataStore.setEnum(preference)

    companion object {
        const val STORAGE_LOCATION = "STORAGE_LOCATION"
        /** Epoch seconds when the vault folder was last selected (fresh vault boundary). */
        const val VAULT_CONFIGURED_AT = "VAULT_CONFIGURED_AT"
        /** The last tree the cloud-folder warning was shown for, so it appears once per picked folder. */
        const val VAULT_CLOUD_WARNED_TREE = "VAULT_CLOUD_WARNED_TREE"
        /** Monotonic fence: bumped on vault folder change; mappings capture generation at last sync. */
        const val VAULT_GENERATION = "VAULT_GENERATION"
        /** This install's folder-history id; app-private and backup-excluded, so a clone never inherits it. */
        const val HISTORY_DEVICE_ID = "HISTORY_DEVICE_ID"
        /** Highest history `seq` this install wrote, used to notice a cloned install. */
        const val HISTORY_LAST_SEQ = "HISTORY_LAST_SEQ"
        /** "off" when the user turned off "Use folder history for sync" on this device; on otherwise. */
        const val HISTORY_APPLY = "HISTORY_APPLY"
        /** UTC day ("YYYY-MM-DD") of this device's last retention prune, so a sync does not list the folder again. */
        const val HISTORY_LAST_PRUNE_DAY = "HISTORY_LAST_PRUNE_DAY"
        /** "1" once existing notebooks were renamed to valid folder names (FMT-11), so the pass runs once. */
        const val NOTEBOOK_NAMES_MIGRATED = "NOTEBOOK_NAMES_MIGRATED"
    }
}
