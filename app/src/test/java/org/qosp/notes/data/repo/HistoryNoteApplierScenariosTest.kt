package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultPortableMetadata
import org.qosp.notes.data.sync.fs.vault.history.DeviceManifest
import org.qosp.notes.data.sync.fs.vault.history.HistoryApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryEvent
import org.qosp.notes.data.sync.fs.vault.history.HistoryJson
import org.qosp.notes.data.sync.fs.vault.history.HistoryNoteApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryOps
import org.qosp.notes.data.sync.fs.vault.history.HistoryPaths
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner

/** Release B: bins, restores and purges recorded elsewhere reach this device without touching files. */
@RunWith(RobolectricTestRunner::class)
class HistoryNoteApplierScenariosTest {

    private fun applierFor(h: NoteRepositoryVaultHarness): HistoryNoteApplier = HistoryNoteApplier(
        h.noteDao,
        h.idMappingDao,
        h.db.reminderDao,
        h.notebookDao,
        VaultPortableMetadata(h.noteDao, h.notebookDao, h.db.tagDao, h.db.noteTagDao, h.db.reminderDao),
        h.backendProvider,
    )

    /** Two or three independent installs, each wired to the real applier. */
    private fun TestScope.vault(count: Int): MultiDeviceVault =
        MultiDeviceVault(this, count) { h -> listOf(applierFor(h)) }

    private fun TestScope.harnessWithApplier(): NoteRepositoryVaultHarness {
        val appliers = mutableListOf<HistoryApplier>()
        val h = NoteRepositoryVaultHarness(this, historyAppliers = appliers)
        appliers += applierFor(h)
        return h
    }

    private suspend fun NoteRepositoryVaultHarness.noteByTitle(title: String): Note =
        noteDao.getAll(SortMethod.TITLE_ASC).first().single { it.title == title }

    private suspend fun NoteRepositoryVaultHarness.mapping(noteId: Long) =
        idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE)

    private suspend fun NoteRepositoryVaultHarness.mappingState(noteId: Long): VaultMappingState =
        VaultMappingState.resolve(mapping(noteId)!!.extras)!!

    private suspend fun NoteRepositoryVaultHarness.uid(noteId: Long): String = mappingState(noteId).noteUid

    private fun NoteRepositoryVaultHarness.noteFiles(): Map<String, String> =
        vaultFs.notePaths().associateWith { vaultFs.readText(it).orEmpty() }

    /** Delivers only the `.allnotes/` logs, as Syncthing does before it moves the note's file. */
    private fun NoteRepositoryVaultHarness.copyHistoryTo(other: NoteRepositoryVaultHarness) {
        vaultFs.snapshotPaths()
            .filter { it.startsWith("${HistoryPaths.ROOT}/") }
            .forEach { path -> other.vaultFs.writeText(path, vaultFs.readText(path).orEmpty()) }
    }

    private suspend fun NoteRepositoryVaultHarness.mismatches() = folderHistory.load().mismatches

    /** Scenario: A bin on one phone bins the note on the other without waiting two scans (HST-09). */
    @Test
    fun aBinOnOnePhone_binsTheNoteOnTheOtherWithoutWaitingTwoScans() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.insertAndSync(Note(title = "Plan", content = "Body"))
            // A second note keeps the folder scan non-empty: an empty scan is treated as a provider glitch.
            a.insertAndSync(Note(title = "Keep", content = "Other"))
            vault.syncthing(0, 1)
            b.syncNotes()
            val bNoteId = b.noteByTitle("Plan").id

            a.binAndSync(a.noteByTitle("Plan"))
            vault.syncthing(0, 1)
            val filesBeforeApply = b.noteFiles()

            b.syncNotes()

            assertTrue("B bins on its next sync, not after two scans", b.getNote(bNoteId)!!.isDeleted)
            val state = b.mappingState(bNoteId)
            assertTrue("the entry is a user bin", state.userBinned)
            assertEquals("Plan.md", state.binnedVaultPath)
            assertEquals(".trash/Plan.md", state.trashedPath)
            assertNull(b.mapping(bNoteId)!!.storageUri)
            assertTrue(b.mapping(bNoteId)!!.isDeletedLocally)
            assertEquals("applying the event wrote, moved or deleted no file", filesBeforeApply, b.noteFiles())
        } finally {
            vault.close()
        }
    }

    /** HST-09 AC1 also holds when that note was the folder's only one, so the scan is legitimately empty. */
    @Test
    fun aBinOnOnePhone_binsTheOnlyNoteWithoutWaitingTwoScans() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.insertAndSync(Note(title = "Plan", content = "Body"))
            vault.syncthing(0, 1)
            b.syncNotes()
            val bNoteId = b.noteByTitle("Plan").id

            a.binAndSync(a.noteByTitle("Plan"))
            vault.syncthing(0, 1)
            b.syncNotes()

            assertTrue(b.getNote(bNoteId)!!.isDeleted)
            assertTrue(b.mappingState(bNoteId).userBinned)
        } finally {
            vault.close()
        }
    }

    /** HST-09: the log can arrive before the moved file; until the file leaves, the note stays live. */
    @Test
    fun theLogArrivesBeforeTheMovedFile_thenBinsWhenTheFileMoveArrives() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.insertAndSync(Note(title = "Plan", content = "Body"))
            // A second note keeps the folder scan non-empty: an empty scan is treated as a provider glitch.
            a.insertAndSync(Note(title = "Keep", content = "Other"))
            vault.syncthing(0, 1)
            b.syncNotes()
            val bNoteId = b.noteByTitle("Plan").id

            a.binAndSync(a.noteByTitle("Plan"))
            a.copyHistoryTo(b)

            b.syncNotes()

            assertFalse("the file is still here, so the bin waits", b.getNote(bNoteId)!!.isDeleted)
            assertTrue("waiting is not a mismatch", b.mismatches().isEmpty())

            vault.syncthing(0, 1)
            b.syncNotes()

            assertTrue(b.getNote(bNoteId)!!.isDeleted)
            assertTrue(b.mappingState(bNoteId).userBinned)
        } finally {
            vault.close()
        }
    }

    /** Scenario: A purge on one phone clears the bin on the other without deleting files (HST-09). */
    @Test
    fun aPurgeOnOnePhone_clearsTheBinOnTheOtherWithoutDeletingFiles() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.insertAndSync(Note(title = "Plan", content = "Body"))
            // A second note keeps the folder scan non-empty: an empty scan is treated as a provider glitch.
            a.insertAndSync(Note(title = "Keep", content = "Other"))
            vault.syncthing(0, 1)
            b.syncNotes()
            val bNoteId = b.noteByTitle("Plan").id

            a.binAndSync(a.noteByTitle("Plan"))
            vault.syncthing(0, 1)
            b.syncNotes()
            assertTrue(b.getNote(bNoteId)!!.isDeleted)

            a.repository.permanentlyDeleteNotesInBin()
            a.folderHistoryService.flush()
            a.copyHistoryTo(b)
            val filesBeforeApply = b.noteFiles()

            b.syncNotes()

            assertNull("the bin entry is gone on B", b.getNote(bNoteId))
            assertNull(b.mapping(bNoteId))
            assertEquals("applying the purge deleted no file", filesBeforeApply, b.noteFiles())
            assertTrue(b.vaultFs.isFile(".trash/Plan.md"))
        } finally {
            vault.close()
        }
    }

    /** Scenario: A bin does not override an unsynced edit (HST-09, HST-11). */
    @Test
    fun aBinDoesNotOverrideAnUnsyncedEdit() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.insertAndSync(Note(title = "Plan", content = "Body"))
            // A second note keeps the folder scan non-empty: an empty scan is treated as a provider glitch.
            a.insertAndSync(Note(title = "Keep", content = "Other"))
            vault.syncthing(0, 1)
            b.syncNotes()
            val bNoteId = b.noteByTitle("Plan").id
            b.repository.updateNotes(
                b.getNote(bNoteId)!!.copy(content = "Edited on B"),
                sync = false,
            )

            a.binAndSync(a.noteByTitle("Plan"))
            vault.syncthing(0, 1)
            b.syncNotes()

            val stillLive = b.getNote(bNoteId)!!
            assertFalse(stillLive.isDeleted)
            assertEquals("Edited on B", stillLive.content)
            val mismatch = b.mismatches().single { it.op == HistoryOps.NOTE_BIN }
            assertEquals("Edited here; kept", mismatch.reason)

            // B's push brings the file back; A's own bin event no longer hides the returned file.
            b.updateAndSync(b.getNote(bNoteId)!!)
            vault.syncthing(1, 0)
            a.syncNotes()

            assertFalse(a.getNote(a.noteByTitle("Plan").id)!!.isDeleted)
            assertEquals("Edited on B", a.noteByTitle("Plan").content.trim())
        } finally {
            vault.close()
        }
    }

    /** HST-09: a restore on one phone restores the note on the other when the file is back. */
    @Test
    fun aRestoreOnOnePhone_restoresTheNoteOnTheOtherWhenTheFileIsBack() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.insertAndSync(Note(title = "Plan", content = "Body"))
            // A second note keeps the folder scan non-empty: an empty scan is treated as a provider glitch.
            a.insertAndSync(Note(title = "Keep", content = "Other"))
            vault.syncthing(0, 1)
            b.syncNotes()
            val bNoteId = b.noteByTitle("Plan").id

            a.binAndSync(a.noteByTitle("Plan"))
            vault.syncthing(0, 1)
            b.syncNotes()
            assertTrue(b.getNote(bNoteId)!!.isDeleted)

            a.restoreAndSync(a.noteByTitle("Plan"))
            vault.syncthing(0, 1)
            b.syncNotes()

            assertFalse(b.getNote(bNoteId)!!.isDeleted)
            val state = b.mappingState(bNoteId)
            assertEquals("Plan.md", b.mapping(bNoteId)!!.storageUri)
            assertFalse(b.mapping(bNoteId)!!.isDeletedLocally)
            assertNull(state.binnedVaultPath)
            assertNull(state.trashedPath)
            assertFalse(state.userBinned)
            assertTrue(b.vaultFs.isFile("Plan.md"))
        } finally {
            vault.close()
        }
    }

    /** Scenario: The feature can be switched off per device (HST-11). */
    @Test
    fun withFolderHistoryForSyncOff_bIgnoresTheBinUntilTheTwoScanRuleBinsIt() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.insertAndSync(Note(title = "Plan", content = "Body"))
            // A second note keeps the folder scan non-empty: an empty scan is treated as a provider glitch.
            a.insertAndSync(Note(title = "Keep", content = "Other"))
            vault.syncthing(0, 1)
            b.syncNotes()
            val bNoteId = b.noteByTitle("Plan").id
            b.historyApplySettings.setEnabled(false)

            a.binAndSync(a.noteByTitle("Plan"))
            vault.syncthing(0, 1)

            b.syncNotes()
            assertFalse("the first scan only counts the missing file", b.getNote(bNoteId)!!.isDeleted)

            b.syncNotes()
            assertTrue(b.getNote(bNoteId)!!.isDeleted)
            assertFalse("the two-scan bin is not a user bin", b.mappingState(bNoteId).userBinned)
        } finally {
            vault.close()
        }
    }

    /** HST-09 AC4: applying the same events twice changes nothing the second time. */
    @Test
    fun syncingAgainAfterApplyingABin_changesNothingTheSecondTime() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.insertAndSync(Note(title = "Plan", content = "Body"))
            // A second note keeps the folder scan non-empty: an empty scan is treated as a provider glitch.
            a.insertAndSync(Note(title = "Keep", content = "Other"))
            vault.syncthing(0, 1)
            b.syncNotes()
            val bNoteId = b.noteByTitle("Plan").id

            a.binAndSync(a.noteByTitle("Plan"))
            vault.syncthing(0, 1)
            b.syncNotes()
            val mappingAfterFirst = b.mapping(bNoteId)!!
            val filesAfterFirst = b.noteFiles()
            val eventsAfterFirst = b.folderHistory.load().entries.size

            b.syncNotes()

            assertEquals(mappingAfterFirst, b.mapping(bNoteId))
            assertEquals(filesAfterFirst, b.noteFiles())
            assertEquals(eventsAfterFirst, b.folderHistory.load().entries.size)
            assertTrue(b.getNote(bNoteId)!!.isDeleted)
        } finally {
            vault.close()
        }
    }

    /** HST-06 AC2: a newer-format event from a device that does not allow partial apply changes nothing. */
    @Test
    fun aNewerFormatEventFromADeviceWithoutPartialApply_isNotApplied() = runTest {
        val h = harnessWithApplier()
        try {
            h.insertAndSync(Note(title = "Plan", content = "Body"))
            h.insertAndSync(Note(title = "Keep", content = "Other"))
            val noteId = h.noteByTitle("Plan").id
            val uid = h.uid(noteId)
            h.writeForeignBinEvent(uid, partialApplyOk = false)
            h.vaultFs.delete("Plan.md")

            h.syncNotes()

            assertFalse("an inapplicable event never bins", h.getNote(noteId)!!.isDeleted)
            assertTrue(h.mismatches().isEmpty())
            assertFalse(h.mappingState(noteId).userBinned)

            h.syncNotes()

            assertTrue("the two-scan rule still applies", h.getNote(noteId)!!.isDeleted)
            assertFalse("but not as a user bin", h.mappingState(noteId).userBinned)
        } finally {
            h.close()
        }
    }

    private fun NoteRepositoryVaultHarness.writeForeignBinEvent(uid: String, partialApplyOk: Boolean) {
        val device = "device-d"
        val t = System.currentTimeMillis()
        vaultFs.writeText(
            HistoryPaths.manifestPath(device),
            HistoryJson.json.encodeToString(
                DeviceManifest(
                    deviceId = device,
                    name = "Other",
                    appVersion = "2.0",
                    writes = HistoryOps.ALL_V1,
                    partialApplyOk = partialApplyOk,
                ),
            ),
        )
        vaultFs.writeText(
            "${HistoryPaths.deviceDir(device)}/${HistoryPaths.monthlyEventsFile(t)}",
            HistoryJson.json.encodeToString(
                HistoryEvent(
                    v = 2,
                    eid = "$device:1",
                    dev = device,
                    seq = 1,
                    lc = 100,
                    t = t,
                    op = HistoryOps.NOTE_BIN,
                    id = uid,
                    path = "Plan.md",
                    to = ".trash/Plan.md",
                ),
            ) + "\n",
        )
    }
}
