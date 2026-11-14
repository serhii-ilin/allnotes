package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultPortableMetadata
import org.qosp.notes.data.sync.fs.vault.history.DeviceManifest
import org.qosp.notes.data.sync.fs.vault.history.HistoryEvent
import org.qosp.notes.data.sync.fs.vault.history.HistoryJson
import org.qosp.notes.data.sync.fs.vault.history.HistoryNoteApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryOps
import org.qosp.notes.data.sync.fs.vault.history.HistoryPaths
import org.qosp.notes.data.sync.fs.vault.history.HistoryReader
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner

/**
 * M4: a bin of a note this device has never seen waits while the event's original path is still scanned.
 * Importing it straight into the bin would make the note flip between live and binned when the move arrives.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryNoteBinImportBeforeMoveTest {

    private fun applierFor(h: NoteRepositoryVaultHarness): HistoryNoteApplier = HistoryNoteApplier(
        h.noteDao,
        h.idMappingDao,
        h.db.reminderDao,
        h.notebookDao,
        VaultPortableMetadata(h.noteDao, h.notebookDao, h.db.tagDao, h.db.noteTagDao, h.db.reminderDao),
        h.backendProvider,
    )

    private suspend fun NoteRepositoryVaultHarness.planNotes(): List<Note> =
        noteDao.getAll(SortMethod.TITLE_ASC).first().filter { it.title == "Plan" }

    private suspend fun NoteRepositoryVaultHarness.mappingState(noteId: Long): VaultMappingState? =
        idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE)
            ?.let { VaultMappingState.resolve(it.extras) }

    private fun NoteRepositoryVaultHarness.writeForeignBinEvent(uid: String, path: String, to: String) {
        val device = "device-d"
        val t = System.currentTimeMillis()
        vaultFs.writeText(
            HistoryPaths.manifestPath(device),
            HistoryJson.json.encodeToString(
                DeviceManifest(
                    deviceId = device,
                    name = "Other",
                    appVersion = "1.0",
                    writes = HistoryOps.ALL_V1,
                    partialApplyOk = true,
                ),
            ),
        )
        vaultFs.writeText(
            "${HistoryPaths.deviceDir(device)}/${HistoryPaths.monthlyEventsFile(t)}",
            HistoryJson.json.encodeToString(
                HistoryEvent(
                    v = 1,
                    eid = "$device:1",
                    dev = device,
                    seq = 1,
                    lc = 1,
                    t = t,
                    op = HistoryOps.NOTE_BIN,
                    id = uid,
                    path = path,
                    to = to,
                ),
            ) + "\n",
        )
    }

    /** HST-09: the log and the trashed copy arrive before the live file leaves; the import waits. */
    @Test
    fun aBinArrivingBeforeTheMovedFile_waitsWhileTheLiveFileIsStillScanned() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            // The live file and the trashed copy are both here, but no Room row ever saw the note.
            h.vaultFs.writeText("Plan.md", "Body")
            h.vaultFs.writeText(".trash/Plan.md", "Body")
            h.writeForeignBinEvent(uid = "uid-plan", path = "Plan.md", to = ".trash/Plan.md")
            val history = HistoryReader(h.backend).read()
            val applier = applierFor(h)
            val ownId = h.deviceId()

            val whilePresent = applier.apply(history, ownId, setOf("Plan.md"))

            assertFalse("nothing is imported while the live file is still scanned", whilePresent.changed)
            assertTrue("no bin entry is invented", h.planNotes().isEmpty())

            // The move arrives: the live path leaves the scan, so the trashed copy becomes the bin entry.
            val afterMove = applier.apply(history, ownId, emptySet())

            assertTrue(afterMove.changed)
            val imported = h.planNotes().single()
            assertTrue(imported.isDeleted)
            assertTrue(h.mappingState(imported.id)!!.userBinned)
            assertEquals(".trash/Plan.md", h.mappingState(imported.id)!!.trashedPath)
            assertNotNull(imported.deletionDate)

            // Applying the same event again changes nothing.
            val again = applier.apply(history, ownId, emptySet())
            assertFalse(again.changed)
            assertEquals("imported exactly once", 1, h.planNotes().size)
            assertNull(h.noteDao.getAll(SortMethod.TITLE_ASC).first().singleOrNull { !it.isDeleted && it.title == "Plan" })
        } finally {
            h.close()
        }
    }
}
