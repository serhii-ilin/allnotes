package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.model.Tag
import org.qosp.notes.data.sync.fs.vault.history.HistoryEvent
import org.qosp.notes.data.sync.fs.vault.history.HistoryOps
import org.qosp.notes.data.sync.fs.vault.history.HistoryPaths
import org.qosp.notes.data.sync.fs.vault.history.HistoryReader
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner

/** Folder-history recording through the real repository, vault backend and sync paths. */
@RunWith(RobolectricTestRunner::class)
class FolderHistoryScenariosTest {

    private fun TestScope.harness() = NoteRepositoryVaultHarness(this, historyStore = mutableMapOf())

    private suspend fun NoteRepositoryVaultHarness.events(): List<HistoryEvent> =
        HistoryReader(backend).read().events

    private suspend fun NoteRepositoryVaultHarness.mapping(noteId: Long) =
        idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE)!!

    private suspend fun NoteRepositoryVaultHarness.uid(noteId: Long): String =
        VaultMappingState.resolve(mapping(noteId).extras)!!.noteUid

    private suspend fun NoteRepositoryVaultHarness.path(noteId: Long): String = mapping(noteId).storageUri!!

    private fun NoteRepositoryVaultHarness.historyFiles(): Set<String> =
        vaultFs.snapshotPaths().filter { it.startsWith(HistoryPaths.ROOT) }.toSet()

    private fun NoteRepositoryVaultHarness.eventsText(): String =
        vaultFs.snapshotPaths()
            .filter { it.contains("/events-") && it.endsWith(".jsonl") }
            .joinToString("\n") { vaultFs.readText(it).orEmpty() }

    private data class VaultSnapshot(
        val notes: List<Pair<String, String>>,
        val notebooks: List<String>,
        val tags: List<String>,
    )

    private suspend fun NoteRepositoryVaultHarness.snapshot(): VaultSnapshot = VaultSnapshot(
        notes = noteDao.getAll(SortMethod.TITLE_ASC).first().map { it.title to it.content },
        notebooks = notebookDao.getAll().first().map { it.name },
        tags = db.tagDao.getAll().first().map { it.name },
    )

    /** Scenario: A user bin is recorded in the device's own log only (HST-01, HST-04). */
    @Test
    fun aUserBin_isRecordedInTheDevicesOwnLogOnly() = runTest {
        val vault = MultiDeviceVault(this, 2)
        val a = vault[0]
        val b = vault[1]
        try {
            val noteId = a.insertAndSync(Note(title = "Plan", content = "Top secret plan"))
            val uid = a.uid(noteId)
            vault.syncthing(0, 1)
            b.syncNotes()
            // Bring B's own log back so A's bin can be checked against another device's folder too.
            vault.syncthing(1, 0)
            val bId = b.deviceId()
            val bFolder = HistoryPaths.deviceDir(bId)
            val bBefore = a.vaultFs.snapshotPaths()
                .filter { it.startsWith("$bFolder/") }
                .associateWith { a.vaultFs.readText(it) }

            a.binAndSync(a.getNote(noteId)!!)

            val bin = a.events().single { it.op == HistoryOps.NOTE_BIN }
            assertEquals(uid, bin.id)
            assertEquals("Plan.md", bin.path)
            assertEquals(".trash/Plan.md", bin.to)
            assertFalse(a.eventsText().contains("Top secret plan"))

            val bAfter = a.vaultFs.snapshotPaths()
                .filter { it.startsWith("$bFolder/") }
                .associateWith { a.vaultFs.readText(it) }
            assertEquals(bBefore, bAfter)
            assertTrue(a.historyFiles().isNotEmpty())
        } finally {
            vault.close()
        }
    }

    /** Scenario: A bin caused by a missing file records nothing (HST-04 AC3). */
    @Test
    fun aBinCausedByAMissingFile_recordsNothing() = runTest {
        val h = harness()
        try {
            val noteId = h.insertAndSync(Note(title = "Plan", content = "Body"))
            h.insertAndSync(Note(title = "Other", content = "Other body"))
            h.vaultFs.delete(h.path(noteId))

            h.syncNotes()
            h.syncNotes()

            assertTrue(h.getNote(noteId)!!.isDeleted)
            assertTrue(h.events().none { it.op == HistoryOps.NOTE_BIN })
        } finally {
            h.close()
        }
    }

    /** Scenario: Binning while the folder is unavailable records the event later (HST-05 AC1, AC2). */
    @Test
    fun binningWhileTheFolderIsUnavailable_recordsTheEventLater() = runTest {
        val h = harness()
        try {
            val noteId = h.insertAndSync(Note(title = "Plan", content = "Body"))
            val uid = h.uid(noteId)
            val path = h.path(noteId)
            h.vaultFs.writable = false

            h.binAndSync(h.getNote(noteId)!!)

            assertTrue(h.getNote(noteId)!!.isDeleted)
            assertTrue("no event is written before the move", h.historyOutbox.readAll().isEmpty())
            assertTrue(h.events().none { it.op == HistoryOps.NOTE_BIN })
            assertFalse(h.vaultFs.isFile(".trash/Plan.md"))

            h.vaultFs.writable = true
            h.syncNotes()

            val bin = h.events().single { it.op == HistoryOps.NOTE_BIN }
            assertEquals(uid, bin.id)
            assertEquals(path, bin.path)
            assertEquals(".trash/Plan.md", bin.to)
            assertTrue(h.vaultFs.isFile(".trash/Plan.md"))
            assertTrue(h.historyOutbox.readAll().isEmpty())
        } finally {
            h.close()
        }
    }

    /** HST-04/HST-10: user notebook and tag actions record; DAO-level sync imports do not. */
    @Test
    fun notebookAndTagActions_recordTheRightOps_andSyncImportsRecordNone() = runTest {
        val h = harness()
        try {
            h.notebookRepository.insert(Notebook("Trips"))
            h.folderHistoryService.flush()
            val trips = h.notebookDao.getByName("Trips").first()!!
            h.notebookRepository.update(trips.copy(name = "Travel"))
            h.folderHistoryService.flush()
            val travel = h.notebookDao.getByName("Travel").first()!!
            h.notebookRepository.delete(travel)
            h.folderHistoryService.flush()

            h.tagRepository.insert(Tag("work"))
            h.folderHistoryService.flush()
            val work = h.db.tagDao.getByName("work").first()!!
            h.tagRepository.update(work.copy(name = "job"))
            h.folderHistoryService.flush()
            val job = h.db.tagDao.getByName("job").first()!!
            h.tagRepository.delete(job)
            h.folderHistoryService.flush()

            val events = h.events()
            assertEquals(
                listOf(
                    HistoryOps.NOTEBOOK_CREATE,
                    HistoryOps.NOTEBOOK_RENAME,
                    HistoryOps.NOTEBOOK_DELETE,
                    HistoryOps.TAG_CREATE,
                    HistoryOps.TAG_RENAME,
                    HistoryOps.TAG_DELETE,
                ),
                events.map { it.op },
            )
            assertEquals("Trips", events[0].name)
            assertEquals("Trips", events[1].name)
            assertEquals("Travel", events[1].to)
            assertEquals("Travel", events[2].name)
            assertEquals("work", events[3].name)
            assertEquals("work", events[4].name)
            assertEquals("job", events[4].to)
            assertEquals("job", events[5].name)

            // A scan imports a file's notebook and tags through DAOs; that is not a user action.
            val beforeImport = h.events().map { it.eid }
            h.vaultFs.writeText(
                "Imported.md",
                "---\ntitle: Imported\nnotebook: \"ImportedBox\"\ntags: [imported-tag]\n---\nbody\n",
            )
            h.syncNotes()
            assertNotNull(h.notebookDao.getByName("ImportedBox").first())
            assertNotNull(h.db.tagDao.getByName("imported-tag").first())
            assertEquals(beforeImport, h.events().map { it.eid })
        } finally {
            h.close()
        }
    }

    /** HST-04: a restore is recorded where the file comes back. */
    @Test
    fun aRestore_recordsWhereTheFileComesBack() = runTest {
        val h = harness()
        try {
            val noteId = h.insertAndSync(Note(title = "Plan", content = "Body"))
            val uid = h.uid(noteId)
            h.binAndSync(h.getNote(noteId)!!)

            h.restoreAndSync(h.getNote(noteId)!!)

            val restore = h.events().last { it.op == HistoryOps.NOTE_RESTORE }
            assertEquals(uid, restore.id)
            assertEquals("Plan.md", restore.path)
            assertEquals("Plan.md", h.path(noteId))
            assertTrue(h.vaultFs.isFile("Plan.md"))
        } finally {
            h.close()
        }
    }

    /** HST-04: a rename that moves the file records `note.move` with both paths. */
    @Test
    fun aRename_recordsNoteMoveWithBothPaths() = runTest {
        val h = harness()
        try {
            val noteId = h.insertAndSync(Note(title = "Plan", content = "Body"))
            val uid = h.uid(noteId)

            h.updateAndSync(h.getNote(noteId)!!.copy(title = "Renamed"))

            val move = h.events().single { it.op == HistoryOps.NOTE_MOVE }
            assertEquals(uid, move.id)
            assertEquals("Plan.md", move.path)
            assertEquals("Renamed.md", move.to)
            assertEquals("Renamed.md", h.path(noteId))
        } finally {
            h.close()
        }
    }

    /** HST-04: a permanent delete from the bin records `note.purge`. */
    @Test
    fun permanentDeleteFromTheBin_recordsNotePurge() = runTest {
        val h = harness()
        try {
            val noteId = h.insertAndSync(Note(title = "Plan", content = "Body"))
            val uid = h.uid(noteId)
            h.binAndSync(h.getNote(noteId)!!)

            h.repository.deleteNotes(h.getNote(noteId)!!, sync = true)
            h.flushRemoteActions()
            h.folderHistoryService.flush()

            val purge = h.events().single { it.op == HistoryOps.NOTE_PURGE }
            assertEquals(uid, purge.id)
            assertEquals("Plan.md", purge.path)
        } finally {
            h.close()
        }
    }

    /** HST-04: emptying the bin records `note.purge` for each binned note. */
    @Test
    fun emptyingTheBin_recordsNotePurge() = runTest {
        val h = harness()
        try {
            val noteId = h.insertAndSync(Note(title = "Plan", content = "Body"))
            val uid = h.uid(noteId)
            h.binAndSync(h.getNote(noteId)!!)

            h.repository.permanentlyDeleteNotesInBin()
            h.folderHistoryService.flush()

            val purge = h.events().single { it.op == HistoryOps.NOTE_PURGE }
            assertEquals(uid, purge.id)
            assertEquals("Plan.md", purge.path)
        } finally {
            h.close()
        }
    }

    /** Scenario: Deleting the history folder changes no note (HST-08). */
    @Test
    fun deletingTheHistoryFolder_changesNoNote() = runTest {
        val vault = MultiDeviceVault(this, 2)
        val a = vault[0]
        val b = vault[1]
        try {
            val notebookId = a.notebookRepository.insert(Notebook("Work"))
            a.tagRepository.insert(Tag("work"))
            a.insertAndSync(Note(title = "Plan", content = "Body", notebookId = notebookId))
            a.folderHistoryService.flush()
            vault.syncthing(0, 1)
            b.syncNotes()
            a.syncNotes()

            val aBefore = a.snapshot()
            val bBefore = b.snapshot()
            assertTrue(a.historyFiles().isNotEmpty())
            assertTrue(b.historyFiles().isNotEmpty())

            vault.deleteHistoryFolder(0)
            vault.deleteHistoryFolder(1)
            a.syncNotes()
            b.syncNotes()

            assertEquals(aBefore, a.snapshot())
            assertEquals(bBefore, b.snapshot())

            a.notebookRepository.insert(Notebook("AfterDelete"))
            a.folderHistoryService.flush()

            val aId = a.deviceId()
            assertTrue(a.vaultFs.isFile(HistoryPaths.manifestPath(aId)))
            assertTrue(a.vaultFs.isFile(HistoryPaths.README))
        } finally {
            vault.close()
        }
    }
}
