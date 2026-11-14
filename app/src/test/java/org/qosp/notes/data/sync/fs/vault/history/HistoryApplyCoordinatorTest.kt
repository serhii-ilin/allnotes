package org.qosp.notes.data.sync.fs.vault.history

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The shared Release B hook: settings switch, history read, appliers, mismatch list. */
class HistoryApplyCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun mockLog() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun unmockLog() {
        unmockkStatic(Log::class)
    }

    private class RecordingApplier(private val outcome: ApplyOutcome = ApplyOutcome()) : HistoryApplier {
        val calls = mutableListOf<Triple<HistoryReadResult, String, Set<String>>>()

        override suspend fun apply(history: HistoryReadResult, ownDeviceId: String, scannedPaths: Set<String>) =
            outcome.also { calls += Triple(history, ownDeviceId, scannedPaths) }
    }

    private class ThrowingApplier : HistoryApplier {
        override suspend fun apply(history: HistoryReadResult, ownDeviceId: String, scannedPaths: Set<String>) =
            throw IllegalStateException("boom")
    }

    private fun mismatch(eid: String) = StoredMismatch(eid = eid, t = 1L, dev = "b", op = HistoryOps.NOTE_BIN, reason = "edited here")

    private fun store() = HistoryMismatchStore(File(tmp.root, "mismatches.json"))

    @Test
    fun appliersSeeTheMergedHistory_ownId_andScannedPaths() = runTest {
        val access = TestVaultFileAccess()
        val other = TestHistoryDevice(access, TestHistoryPrefs(), tmp.newFolder())
        other.record(HistoryOps.NOTEBOOK_CREATE, name = "Trips")
        other.flush()
        val prefs = TestHistoryPrefs()
        val me = DeviceIdentity(prefs.repository, model = "Pixel 9")
        val applier = RecordingApplier(ApplyOutcome(changed = true, mismatches = listOf(mismatch("b:1"))))
        val mismatches = store()

        val changed = HistoryApplyCoordinator(me, HistoryApplySettings(prefs.repository), mismatches, listOf(applier))
            .apply(access, setOf("Plan.md"))

        assertTrue(changed)
        val (history, ownId, scanned) = applier.calls.single()
        assertEquals(listOf(HistoryOps.NOTEBOOK_CREATE), history.events.map { it.op })
        assertEquals(me.deviceId(access.historyScope), ownId)
        assertEquals(setOf("Plan.md"), scanned)
        assertEquals(listOf("b:1"), mismatches.readAll().map { it.eid })
    }

    @Test
    fun switchedOff_runsNoApplier_andClearsTheMismatches() = runTest {
        val prefs = TestHistoryPrefs()
        val settings = HistoryApplySettings(prefs.repository)
        val mismatches = store().apply { replace(listOf(mismatch("b:1"))) }
        val applier = RecordingApplier(ApplyOutcome(changed = true))
        settings.setEnabled(false)

        val changed = HistoryApplyCoordinator(DeviceIdentity(prefs.repository), settings, mismatches, listOf(applier))
            .apply(TestVaultFileAccess(), emptySet())

        assertFalse(changed)
        assertTrue(applier.calls.isEmpty())
        assertTrue(mismatches.readAll().isEmpty())
        assertFalse(settings.enabled())
        settings.setEnabled(true)
        assertTrue(settings.enabled())
    }

    @Test
    fun onByDefault() = runTest {
        assertTrue(HistoryApplySettings(TestHistoryPrefs().repository).enabled())
    }

    @Test
    fun aFailingApplier_doesNotStopTheOthers() = runTest {
        val prefs = TestHistoryPrefs()
        val after = RecordingApplier(ApplyOutcome(mismatches = listOf(mismatch("b:2"))))
        val mismatches = store()

        HistoryApplyCoordinator(
            DeviceIdentity(prefs.repository),
            HistoryApplySettings(prefs.repository),
            mismatches,
            listOf(ThrowingApplier(), after),
        ).apply(TestVaultFileAccess(), emptySet())

        assertEquals(1, after.calls.size)
        assertEquals(listOf("b:2"), mismatches.readAll().map { it.eid })
    }

    @Test
    fun theMismatchListIsReplacedEachSync_soAnAppliedEventDropsOut() = runTest {
        val prefs = TestHistoryPrefs()
        val mismatches = store()
        val first = HistoryApplyCoordinator(
            DeviceIdentity(prefs.repository),
            HistoryApplySettings(prefs.repository),
            mismatches,
            listOf(RecordingApplier(ApplyOutcome(mismatches = listOf(mismatch("b:1"), mismatch("b:1"))))),
        )
        first.apply(TestVaultFileAccess(), emptySet())
        assertEquals(listOf("b:1"), mismatches.readAll().map { it.eid })

        HistoryApplyCoordinator(
            DeviceIdentity(prefs.repository),
            HistoryApplySettings(prefs.repository),
            mismatches,
            listOf(RecordingApplier()),
        ).apply(TestVaultFileAccess(), emptySet())

        assertTrue(mismatches.readAll().isEmpty())
    }
}
