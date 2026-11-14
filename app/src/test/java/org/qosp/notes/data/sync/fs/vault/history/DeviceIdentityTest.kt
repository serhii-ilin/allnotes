package org.qosp.notes.data.sync.fs.vault.history

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.preferences.PreferenceRepository

/** The install's identity: created once, shown as model · id-prefix, and replaced when a clone is detected. */
class DeviceIdentityTest {

    @Test
    fun aDeviceId_isCreatedOnce_andSurvivesRestarts() = runTest {
        val prefs = TestHistoryPrefs()
        val id = DeviceIdentity(prefs.repository, model = "Pixel 9").deviceId()

        assertTrue(id.isNotBlank())
        assertEquals(id, prefs.values[PreferenceRepository.HISTORY_DEVICE_ID])
        assertEquals(id, DeviceIdentity(prefs.repository, model = "Pixel 9").deviceId())
    }

    @Test
    fun theLabel_isTheModelAndTheFirstSixCharactersOfTheId() {
        val identity = DeviceIdentity(TestHistoryPrefs().repository, model = "Pixel 9")

        assertEquals("Pixel 9 · a1b2c3", identity.label("a1b2c3d4-e5f6-7890"))
    }

    @Test
    fun lastWrittenSeq_startsAtZero_andSurvivesRestarts() = runTest {
        val prefs = TestHistoryPrefs()
        val identity = DeviceIdentity(prefs.repository, model = "Pixel 9")

        assertEquals(0L, identity.lastWrittenSeq())
        identity.setLastWrittenSeq(42)
        assertEquals(42L, DeviceIdentity(prefs.repository, model = "Pixel 9").lastWrittenSeq())
    }

    @Test
    fun rotate_switchesTheIdAndResetsTheSeq() = runTest {
        val prefs = TestHistoryPrefs()
        val identity = DeviceIdentity(prefs.repository, model = "Pixel 9")
        val old = identity.deviceId()
        identity.setLastWrittenSeq(7)

        val fresh = identity.rotate()

        assertNotEquals(old, fresh)
        assertEquals(fresh, identity.deviceId())
        assertEquals(0L, identity.lastWrittenSeq())
    }
}
