package org.qosp.notes.data.repo

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.sync.fs.vault.history.HistoryOps
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository
import org.robolectric.RobolectricTestRunner

/** FMT-11 AC7: existing notebooks with a name no file system accepts are renamed once, collision-safely. */
@RunWith(RobolectricTestRunner::class)
class NotebookNameMigrationTest {

    /** The encrypted-preferences store [NotebookNameMigration] reads its one-time flag from. */
    private fun fakePreferences(): PreferenceRepository {
        val values = mutableMapOf<String, String>()
        val prefs = mockk<PreferenceRepository>(relaxed = true)
        coEvery { prefs.getEncryptedString(any()) } answers { flowOf(values[firstArg<String>()] ?: "") }
        coEvery { prefs.putEncryptedStrings(*anyVararg()) } answers {
            args.forEach { arg ->
                when (arg) {
                    is Pair<*, *> -> values[arg.first as String] = arg.second as String
                    is Array<*> -> arg.forEach { item ->
                        if (item is Pair<*, *>) values[item.first as String] = item.second as String
                    }
                }
            }
        }
        return prefs
    }

    @Test
    fun invalidNames_areRenamedSafely_andTheirNotesFollow() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val migration = NotebookNameMigration(h.notebookRepository, fakePreferences())
        try {
            h.notebookRepository.insert(Notebook("Work"))
            val trailingSpace = h.notebookDao.insert(Notebook("Work "))
            val trailingDot = h.notebookDao.insert(Notebook("Work."))
            h.notebookDao.insert(Notebook(".hidden"))
            val noteId = h.insertAndSync(Note(title = "Plan", content = "Body", notebookId = trailingSpace))

            assertEquals("Plan.md", mappingPath(h, noteId))

            val renames = migration.run()
            assertTrue(
                "the rename is recorded for other devices",
                h.historyOutbox.readAll().any {
                    it.op == HistoryOps.NOTEBOOK_RENAME && it.name == "Work " && it.to == "Work-2"
                },
            )
            h.flushRemoteActions()
            h.syncNotes()

            assertEquals(
                setOf(
                    NotebookNameRename("Work ", "Work-2"),
                    NotebookNameRename("Work.", "Work-3"),
                    NotebookNameRename(".hidden", "_hidden"),
                ),
                renames.toSet(),
            )
            assertEquals(
                listOf("Work", "Work-2", "Work-3", "_hidden"),
                h.notebookDao.getAll().first().map { it.name }.sorted(),
            )
            assertEquals("Work-2/Plan.md", mappingPath(h, noteId))
            assertTrue(h.vaultFs.isFile("Work-2/Plan.md"))

            assertTrue("a second pass finds nothing to rename", migration.migrate().isEmpty())
            assertTrue("the one-time flag stops a rerun", migration.run().isEmpty())
            assertEquals(4, h.notebookDao.getAll().first().size)
            assertEquals(trailingDot, h.notebookDao.getByName("Work-3").first()!!.id)
        } finally {
            h.close()
        }
    }

    @Test
    fun aValidSetOfNames_isLeftUntouched_andTheRunIsMarkedDone() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val prefs = fakePreferences()
        val migration = NotebookNameMigration(h.notebookRepository, prefs)
        try {
            h.notebookRepository.insert(Notebook("Work"))
            h.notebookRepository.insert(Notebook("Мої нотатки"))

            assertTrue(migration.run().isEmpty())
            assertEquals(listOf("Work", "Мої нотатки"), h.notebookDao.getAll().first().map { it.name }.sorted())
            assertEquals(
                "1",
                prefs.getEncryptedString(PreferenceRepository.NOTEBOOK_NAMES_MIGRATED).first(),
            )
        } finally {
            h.close()
        }
    }

    private suspend fun mappingPath(h: NoteRepositoryVaultHarness, noteId: Long): String? =
        h.idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE)?.storageUri
}
