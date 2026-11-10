package org.qosp.notes.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tfcporciuncula.flow.FlowSharedPreferences
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Upgrades from old installs keep legacy sync keys in DataStore. They are no longer read, but they
 * must not break preference decoding (unknown enum names would otherwise throw).
 */
class PreferenceRepositoryLegacyKeysTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun legacySyncKeys_doNotBreakDefaults() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(tmp.root, "legacy.preferences_pb") },
        )
        try {
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("cloud_service")] = "NEXTCLOUD"
                prefs[stringPreferencesKey("sync_mode")] = "WIFI"
                prefs[stringPreferencesKey("trust_self_signed_certificate")] = "YES"
            }

            val repository = PreferenceRepository(dataStore, mockk<FlowSharedPreferences>(relaxed = true))

            assertEquals(AppPreferences(), repository.getAll().first())
        } finally {
            scope.cancel()
        }
    }
}
