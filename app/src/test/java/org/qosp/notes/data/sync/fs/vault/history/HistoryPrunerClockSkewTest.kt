package org.qosp.notes.data.sync.fs.vault.history

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** HST-13: retention must survive a device whose wall clock is years ahead of every log it wrote. */
@RunWith(RobolectricTestRunner::class)
class HistoryPrunerClockSkewTest {

    private val today = at("2026-11-11T12:00:00Z")

    @Test
    fun aClockFiveYearsAhead_doesNotDeleteEveryOwnLog() = runTest {
        val access = TestVaultFileAccess()
        access.fs.writeText(eventsPath("phone-a", "2026-01"), eventLine(at("2026-01-05T10:00:00Z"), seq = 1))
        access.fs.writeText(eventsPath("phone-a", "2026-02"), eventLine(at("2026-02-05T10:00:00Z"), seq = 2))
        val ahead = at("2031-01-15T12:00:00Z")

        val outcome = HistoryPruner().pruneOutcome(
            access,
            "phone-a",
            3,
            ahead,
            newestEventT = at("2026-02-05T10:00:00Z"),
        )

        assertEquals(0, outcome.deleted)
        assertNotNull(access.text(eventsPath("phone-a", "2026-01")))
        assertNotNull(access.text(eventsPath("phone-a", "2026-02")))
    }

    @Test
    fun theMonthHoldingThisDevicesNewestOwnEvent_isNeverDeleted() = runTest {
        val access = TestVaultFileAccess()
        access.fs.writeText(eventsPath("phone-a", "2024-12"), eventLine(at("2024-12-05T10:00:00Z"), seq = 1))
        access.fs.writeText(eventsPath("phone-a", "2025-01"), eventLine(at("2025-01-05T10:00:00Z"), seq = 2))

        val outcome = HistoryPruner().pruneOutcome(
            access,
            "phone-a",
            3,
            today,
            newestEventT = today,
            ownNewestEventT = at("2025-01-05T10:00:00Z"),
        )

        assertEquals(1, outcome.deleted)
        assertNull(access.text(eventsPath("phone-a", "2024-12")))
        assertNotNull(access.text(eventsPath("phone-a", "2025-01")))
    }

    private fun eventLine(t: Long, seq: Long = 1): String = HistoryJson.json.encodeToString(
        HistoryEvent(
            eid = "phone-a:$seq",
            dev = "phone-a",
            seq = seq,
            lc = seq,
            t = t,
            op = HistoryOps.NOTE_BIN,
            id = "3f2a",
            path = "Plan.md",
            to = ".trash/Plan.md",
        ),
    ) + "\n"
}
