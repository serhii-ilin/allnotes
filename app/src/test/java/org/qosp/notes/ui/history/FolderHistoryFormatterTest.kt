package org.qosp.notes.ui.history

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.sync.fs.vault.history.HistoryEntry
import org.qosp.notes.data.sync.fs.vault.history.HistoryMismatch
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FolderHistoryFormatterTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val formatter = FolderHistoryFormatter(context, now = { 1_700_000_000_000L })

    private fun entry(
        op: String,
        title: String? = null,
        path: String? = null,
        to: String? = null,
        name: String? = null,
        knownOp: Boolean = true,
    ) = HistoryEntry(
        timeMs = 1_700_000_000_000L,
        deviceLabel = "Pixel 9 · a1b2c3",
        op = op,
        knownOp = knownOp,
        noteTitle = title,
        path = path,
        to = to,
        name = name,
    )

    @Test
    fun `note ops read as sentences`() {
        assertEquals("Binned “Plan”", formatter.format(entry("note.bin", title = "Plan")).action)
        assertEquals("Restored “Plan”", formatter.format(entry("note.restore", title = "Plan")).action)
        assertEquals("Permanently deleted “Plan”", formatter.format(entry("note.purge", title = "Plan")).action)
        assertEquals(
            "Moved “Plan” to Work/Plan.md",
            formatter.format(entry("note.move", title = "Plan", to = "Work/Plan.md")).action,
        )
    }

    @Test
    fun `notebook ops read as sentences`() {
        assertEquals(
            "Created notebook “Trips”",
            formatter.format(entry("notebook.create", name = "Trips")).action,
        )
        assertEquals(
            "Renamed notebook “Trips” to Travel",
            formatter.format(entry("notebook.rename", name = "Trips", to = "Travel")).action,
        )
        assertEquals(
            "Deleted notebook “Trips”",
            formatter.format(entry("notebook.delete", name = "Trips")).action,
        )
    }

    @Test
    fun `tag ops read as sentences`() {
        assertEquals("Created tag “urgent”", formatter.format(entry("tag.create", name = "urgent")).action)
        assertEquals(
            "Renamed tag “urgent” to later",
            formatter.format(entry("tag.rename", name = "urgent", to = "later")).action,
        )
        assertEquals("Deleted tag “urgent”", formatter.format(entry("tag.delete", name = "urgent")).action)
    }

    @Test
    fun `device reid has no subject`() {
        assertEquals("Device switched to a new id", formatter.format(entry("device.reid")).action)
    }

    @Test
    fun `unknown op is labelled as from a newer version`() {
        val row = formatter.format(entry("note.pin", knownOp = false))

        assertEquals("From a newer version (note.pin)", row.action)
        assertTrue(row.unknownOp)
    }

    @Test
    fun `subject falls back from title to path to name`() {
        assertEquals("Binned “Plan”", formatter.format(entry("note.bin", title = "Plan", path = "Plan.md")).action)
        assertEquals("Binned “Plan.md”", formatter.format(entry("note.bin", path = "Plan.md")).action)
        assertEquals("Binned “Plan note”", formatter.format(entry("note.bin", name = "Plan note")).action)
        assertEquals("Binned “Untitled”", formatter.format(entry("note.bin")).action)
    }

    @Test
    fun `row carries the device label and a time`() {
        val row = formatter.format(entry("note.bin", title = "Plan"))

        assertEquals("Pixel 9 · a1b2c3", row.device)
        assertTrue(row.time.isNotBlank())
        assertFalse(row.unknownOp)
    }

    @Test
    fun `mismatch keeps its reason`() {
        val mismatch = HistoryMismatch(
            timeMs = 1_700_000_000_000L,
            deviceLabel = "Pixel 9 · a1b2c3",
            op = "note.bin",
            reason = "The note has unsynced changes here",
            noteTitle = "Plan",
        )

        val row = formatter.format(mismatch)

        assertEquals("Binned “Plan”", row.action)
        assertEquals("The note has unsynced changes here", row.reason)
    }
}
