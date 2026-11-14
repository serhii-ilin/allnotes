package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Notebook
import org.robolectric.RobolectricTestRunner

/** FMT-11 AC7: defence in depth; a name that would not be a valid folder never reaches the database. */
@RunWith(RobolectricTestRunner::class)
class NotebookRepositoryNameGuardTest {

    private suspend fun assertRejected(name: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException for notebook name <$name>")
        } catch (expected: IllegalArgumentException) {
            // The guard sits in the repository, so every writer (dialog, backup, migration) is covered.
        }
    }

    @Test
    fun insert_withAnInvalidName_throws() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            listOf(".trash", "a/b", "CON", "", "Work ").forEach { name ->
                assertRejected(name) { h.notebookRepository.insert(Notebook(name)) }
            }
            assertEquals(emptyList<Notebook>(), h.notebookDao.getAll().first())
        } finally {
            h.close()
        }
    }

    @Test
    fun update_toAnInvalidName_throwsAndKeepsTheOldName() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            h.notebookRepository.insert(Notebook("Work"))
            val work = h.notebookDao.getByName("Work").first()!!

            assertRejected("a/b") { h.notebookRepository.update(work.copy(name = "a/b")) }

            assertEquals("Work", h.notebookDao.getById(work.id).first()!!.name)
        } finally {
            h.close()
        }
    }

    @Test
    fun insertAndUpdate_withValidNames_pass() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            h.notebookRepository.insert(Notebook("Мої нотатки"))
            val notebook = h.notebookDao.getByName("Мої нотатки").first()!!

            h.notebookRepository.update(notebook.copy(name = "Home"))

            assertEquals("Home", h.notebookDao.getById(notebook.id).first()!!.name)
        } finally {
            h.close()
        }
    }
}
