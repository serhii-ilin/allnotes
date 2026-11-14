package org.qosp.notes.data.repo

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.fs.vault.history.HistoryOps
import org.robolectric.RobolectricTestRunner

/**
 * M5: retention is a per-device preference, so the bin cleaner's deletions stay local. Only an explicit user
 * permanent delete records `note.purge` and therefore travels to other devices.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryRetentionPurgeTest {

    /** HST-04 AC3: the retention cleaner empties the bin without recording a purge. */
    @Test
    fun theRetentionCleaner_doesNotRecordAPurge() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            val id = h.insertAndSync(Note(title = "Plan", content = "Body"))
            h.binAndSync(h.getNote(id)!!)
            h.historyOutbox.clear()

            h.repository.deleteNotes(h.getNote(id)!!, sync = true, recordPurge = false)

            assertFalse(
                "the retention worker's delete stays device-local",
                h.historyOutbox.readAll().any { it.op == HistoryOps.NOTE_PURGE },
            )
        } finally {
            h.close()
        }
    }

    /** HST-04 AC3: deleting from the bin is a user action and still travels. */
    @Test
    fun anExplicitPermanentDelete_stillRecordsAPurge() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            val id = h.insertAndSync(Note(title = "Plan", content = "Body"))
            h.binAndSync(h.getNote(id)!!)
            h.historyOutbox.clear()

            h.repository.deleteNotes(h.getNote(id)!!, sync = true)

            assertEquals(listOf(HistoryOps.NOTE_PURGE), h.historyOutbox.readAll().map { it.op })
        } finally {
            h.close()
        }
    }
}
