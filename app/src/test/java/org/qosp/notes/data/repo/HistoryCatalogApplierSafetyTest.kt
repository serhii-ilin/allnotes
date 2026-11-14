package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.model.Tag
import org.qosp.notes.data.sync.fs.vault.history.HistoryCatalogApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryEvent
import org.qosp.notes.data.sync.fs.vault.history.HistoryOps
import org.qosp.notes.data.sync.fs.vault.history.HistoryPaths
import org.qosp.notes.data.sync.fs.vault.history.HistoryReadResult
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner

/**
 * Review finding on HST-10: the files carry a used notebook's or tag's new name. Renaming the row before they arrive
 * would make this device push its notes and race the other device's writes into conflict copies.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryCatalogApplierSafetyTest {

    private fun TestScope.vault(count: Int) = MultiDeviceVault(this, count) { h ->
        listOf(HistoryCatalogApplier(h.notebookDao, h.noteDao, h.db.tagDao, h.db.noteTagDao))
    }

    private fun NoteRepositoryVaultHarness.copyHistoryTo(other: NoteRepositoryVaultHarness) {
        vaultFs.snapshotPaths()
            .filter { it.startsWith("${HistoryPaths.ROOT}/") }
            .forEach { path -> other.vaultFs.writeText(path, vaultFs.readText(path).orEmpty()) }
    }

    private fun NoteRepositoryVaultHarness.noteFiles(): Map<String, String> =
        vaultFs.notePaths().associateWith { vaultFs.readText(it).orEmpty() }

    @Test
    fun aUsedNotebookRename_whoseLogArrivesFirst_waitsForTheFiles_andNothingConflicts() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.notebookRepository.insert(Notebook("Work"))
            val work = a.notebookDao.getByName("Work").first()!!
            a.insertAndSync(Note(title = "Plan", content = "Body", notebookId = work.id))
            a.folderHistoryService.flush()
            vault.syncthing(0, 1)
            b.syncNotes()

            a.notebookRepository.update(work.copy(name = "Travel"))
            a.flushRemoteActions()
            a.folderHistoryService.flush()
            a.copyHistoryTo(b)
            val bFilesBefore = b.noteFiles()

            b.syncNotes()

            assertEquals("B pushed nothing while A's moved files are on their way", bFilesBefore, b.noteFiles())
            // The target new name is created; the used old row waits for its files to leave.
            assertEquals(listOf("Travel", "Work"), b.notebookDao.getAll().first().map { it.name }.sorted())

            vault.syncthing(0, 1)
            b.syncNotes()
            b.syncNotes()
            vault.syncthing(1, 0)
            a.syncNotes()

            for (h in listOf(a, b)) {
                assertEquals(listOf("Travel"), h.notebookDao.getAll().first().map { it.name })
                assertEquals(setOf("Travel/Plan.md"), h.vaultFs.notePaths())
                assertTrue(h.conflictFiles().isEmpty())
                assertEquals(1, h.localNoteCount())
            }
        } finally {
            vault.close()
        }
    }

    @Test
    fun aUsedTagRename_whoseLogArrivesFirst_waitsForTheFiles_andNothingConflicts() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            val tagId = a.tagRepository.insert(Tag("work"))
            val noteId = a.insertAndSync(Note(title = "Plan", content = "Body"))
            a.tagRepository.addTagToNote(tagId, noteId)
            a.flushRemoteActions()
            a.folderHistoryService.flush()
            vault.syncthing(0, 1)
            b.syncNotes()

            a.tagRepository.update(a.db.tagDao.getByName("work").first()!!.copy(name = "job"))
            // A's next sync writes the new tag name into the note's file.
            a.syncNotes()
            a.folderHistoryService.flush()
            a.copyHistoryTo(b)
            val bFilesBefore = b.noteFiles()

            b.syncNotes()

            assertEquals("B pushed nothing while A's rewritten files are on their way", bFilesBefore, b.noteFiles())

            vault.syncthing(0, 1)
            b.syncNotes()
            b.syncNotes()
            vault.syncthing(1, 0)
            a.syncNotes()

            for (h in listOf(a, b)) {
                assertTrue(h.conflictFiles().isEmpty())
                assertEquals(1, h.localNoteCount())
                val note = h.noteDao.getAll(SortMethod.TITLE_ASC).first().single()
                assertEquals(listOf("job"), h.db.noteTagDao.getByNoteId(note.id).first().map { it.name })
                assertEquals(listOf("job"), h.db.tagDao.getAll().first().map { it.name })
            }
        } finally {
            vault.close()
        }
    }

    private fun history(op: String, name: String?, to: String? = null) = HistoryReadResult(
        items = listOf(
            HistoryReadResult.Item(
                event = HistoryEvent(
                    eid = "other:1",
                    dev = "other",
                    seq = 1,
                    lc = 1,
                    t = 1,
                    op = op,
                    name = name,
                    to = to,
                ),
                applicable = true,
                knownOp = true,
            ),
        ),
        manifests = emptyMap(),
        skippedLines = 0,
    )

    /** FMT-11 AC7: a name that cannot be a folder never arrives as a notebook through the history log. */
    @Test
    fun aNotebookCreateWithAnInvalidName_isIgnored() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            val applier = HistoryCatalogApplier(h.notebookDao, h.noteDao, h.db.tagDao, h.db.noteTagDao)

            val outcome = applier.apply(history(HistoryOps.NOTEBOOK_CREATE, ".trash"), h.deviceId(), emptySet())

            assertFalse(outcome.changed)
            assertTrue(h.notebookDao.getAll().first().isEmpty())
        } finally {
            h.close()
        }
    }

    @Test
    fun aNotebookRenameToAnInvalidName_isIgnored() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            h.notebookDao.insert(Notebook("Work"))
            val applier = HistoryCatalogApplier(h.notebookDao, h.noteDao, h.db.tagDao, h.db.noteTagDao)

            val outcome = applier.apply(
                history(HistoryOps.NOTEBOOK_RENAME, "Work", to = "a/b"),
                h.deviceId(),
                emptySet(),
            )

            assertFalse(outcome.changed)
            assertEquals(listOf("Work"), h.notebookDao.getAll().first().map { it.name })
        } finally {
            h.close()
        }
    }
}
