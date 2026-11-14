package org.qosp.notes.data.sync.fs.vault.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HST-13: the clock that measures retention comes from the other devices' logs. This device's own events carry its
 * own (possibly wrong) clock, so they only decide when no other device has written anything.
 */
class HistoryPruneReferenceTimeTest {

    private fun event(dev: String, t: Long, seq: Long) = HistoryEvent(
        eid = "$dev:$seq", dev = dev, seq = seq, lc = seq, t = t, op = HistoryOps.NOTEBOOK_CREATE, name = "n$seq",
    )

    @Test
    fun aClockAheadOnThisDevice_isOverruledByTheOtherDevices() {
        val events = listOf(
            event("phone-a", at("2031-01-10T00:00:00Z"), 1), // written with this device's wrong clock
            event("phone-b", at("2026-11-10T00:00:00Z"), 1),
        )
        assertEquals(at("2026-11-10T00:00:00Z"), HistoryPruner.referenceTime(events, ownDeviceId = "phone-a"))
    }

    @Test
    fun aLoneDevice_usesItsOwnNewestEvent() {
        val events = listOf(event("phone-a", at("2026-10-01T00:00:00Z"), 1), event("phone-a", at("2026-11-01T00:00:00Z"), 2))
        assertEquals(at("2026-11-01T00:00:00Z"), HistoryPruner.referenceTime(events, ownDeviceId = "phone-a"))
    }

    @Test
    fun noEvents_meansNoReference() {
        assertNull(HistoryPruner.referenceTime(emptyList(), ownDeviceId = "phone-a"))
    }
}
