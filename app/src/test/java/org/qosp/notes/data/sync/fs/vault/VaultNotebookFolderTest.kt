package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.data.repo.NotebookRepository
import org.qosp.notes.preferences.CloudService
import org.robolectric.RobolectricTestRunner

/** Folder = notebook: a note's file lives in the folder named after its notebook. */
@RunWith(RobolectricTestRunner::class)
class VaultNotebookFolderTest {

    private class Fixture(val h: NoteRepositoryVaultHarness) {
        val notebooks = NotebookRepository(h.notebookDao, h.repository)

        suspend fun notebook(name: String): Notebook = Notebook(name, h.notebookDao.insert(Notebook(name)))

        suspend fun mappingPath(id: Long) =
            h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri

        fun folders(): Set<String> =
            h.vaultFs.listChildren("").filter { it.isDirectory }.map { it.name }.toSet()
    }

    private fun TestScope.fixture() = Fixture(NoteRepositoryVaultHarness(this))

    @Test
    fun newNoteInNotebook_isCreatedInNotebookFolder() = runTest {
        val f = fixture()
        val work = f.notebook("Work")
        val id = f.h.insertAndSync(Note(title = "Plan", content = "x", notebookId = work.id))
        assertEquals("Work/Plan.md", f.mappingPath(id))
        assertTrue(f.h.vaultFs.isFile("Work/Plan.md"))
        assertFalse(f.h.vaultFs.isFile("Plan.md"))
    }

    @Test
    fun noteWithoutNotebook_staysAtRoot() = runTest {
        val f = fixture()
        val id = f.h.insertAndSync(Note(title = "Plain", content = "x"))
        assertEquals("Plain.md", f.mappingPath(id))
    }

    @Test
    fun changingNotebook_movesFileAndAssets_andRemovesEmptyFolder() = runTest {
        val f = fixture()
        val work = f.notebook("Work")
        val home = f.notebook("Home")
        val id = f.h.insertAndSync(Note(title = "Plan", content = "x", notebookId = work.id))
        val att = VaultPaths.vaultRelative("Work/Plan/pic.png")
        f.h.vaultFs.writeBytes("Work/Plan/pic.png", byteArrayOf(1, 2, 3), "image/png")
        f.h.updateAndSync(
            f.h.getNote(id)!!.copy(
                attachments = listOf(Attachment(Attachment.Type.IMAGE, att, "pic.png")),
                modifiedDate = 10L,
            ),
        )

        f.h.updateAndSync(f.h.getNote(id)!!.copy(notebookId = home.id, modifiedDate = 20L))

        assertEquals("Home/Plan.md", f.mappingPath(id))
        assertTrue(f.h.vaultFs.isFile("Home/Plan.md"))
        assertTrue(f.h.vaultFs.isFile("Home/Plan/pic.png"))
        assertFalse(f.h.vaultFs.isFile("Work/Plan.md"))
        assertFalse(f.h.vaultFs.isFile("Work/Plan/pic.png"))
        assertFalse("emptied folder must go", "Work" in f.folders())
        val state = VaultMappingState.resolve(
            f.h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.extras,
        )!!
        assertEquals(listOf(VaultPaths.vaultRelative("Home/Plan/pic.png")), state.vaultAttachmentPaths)
        assertEquals(
            listOf(VaultPaths.vaultRelative("Home/Plan/pic.png")),
            f.h.getNote(id)!!.attachments.map { it.path },
        )
    }

    @Test
    fun movingBetweenNotebooks_keepsFolderThatStillHoldsOtherFiles() = runTest {
        val f = fixture()
        val work = f.notebook("Work")
        val home = f.notebook("Home")
        val id = f.h.insertAndSync(Note(title = "Plan", content = "x", notebookId = work.id))
        f.h.vaultFs.writeText("Work/Other.txt", "foreign")

        f.h.updateAndSync(f.h.getNote(id)!!.copy(notebookId = home.id, modifiedDate = 20L))

        assertEquals("Home/Plan.md", f.mappingPath(id))
        assertTrue(f.h.vaultFs.isFile("Work/Other.txt"))
    }

    @Test
    fun removingNotebook_movesNoteToRoot() = runTest {
        val f = fixture()
        val work = f.notebook("Work")
        val id = f.h.insertAndSync(Note(title = "Plan", content = "x", notebookId = work.id))

        f.h.updateAndSync(f.h.getNote(id)!!.copy(notebookId = null, modifiedDate = 20L))

        assertEquals("Plan.md", f.mappingPath(id))
        assertFalse("Work" in f.folders())
    }

    @Test
    fun noteInNestedFolderMatchingNotebook_staysInPlace() = runTest {
        val f = fixture()
        f.h.vaultFs.writeText("Work/Projects/x.md", "Body")
        f.h.syncNotes()
        val id = f.h.noteDao.getAll(org.qosp.notes.preferences.SortMethod.TITLE_ASC).first().single().id
        assertEquals("Projects", f.h.notebookDao.getById(f.h.getNote(id)!!.notebookId!!).first()!!.name)

        f.h.updateAndSync(f.h.getNote(id)!!.copy(content = "Edited", modifiedDate = 30L))

        assertEquals("Work/Projects/x.md", f.mappingPath(id))
        assertTrue(f.h.vaultFs.isFile("Work/Projects/x.md"))
    }

    @Test
    fun nestedFolderMatchIgnoresCase() {
        assertEquals("Work/projects", VaultPaths.parentForNotebook("PROJECTS", "Work/projects"))
        assertEquals("Home", VaultPaths.parentForNotebook("Home", "Work/Projects"))
        assertEquals("", VaultPaths.parentForNotebook(null, "Work"))
        assertEquals("", VaultPaths.parentForNotebook("  ", "Work"))
        assertEquals("a_b", VaultPaths.parentForNotebook("a/b", ""))
    }

    @Test
    fun renamingNotebook_movesNotesToRenamedFolder_andNoOldNotebookAfterSync() = runTest {
        val f = fixture()
        val work = f.notebook("Work")
        val a = f.h.insertAndSync(Note(title = "A", content = "x", notebookId = work.id))
        val b = f.h.insertAndSync(Note(title = "B", content = "y", notebookId = work.id))

        f.notebooks.update(work.copy(name = "Job"))
        // A sync landing between the Room change and the queued push must not bring "Work" back.
        f.h.repository.syncNotes()
        f.h.flushRemoteActions()
        f.h.syncNotes()

        assertEquals("Job/A.md", f.mappingPath(a))
        assertEquals("Job/B.md", f.mappingPath(b))
        assertFalse("Work" in f.folders())
        assertNull(f.h.notebookDao.getByName("Work").first())
        assertEquals("Job", f.h.notebookDao.getById(f.h.getNote(a)!!.notebookId!!).first()!!.name)
        assertTrue(f.h.vaultFs.readText("Job/A.md")!!.contains("Job"))
    }

    @Test
    fun deletingNotebook_movesNotesToRoot_andNotebookIsNotRecreated() = runTest {
        val f = fixture()
        val work = f.notebook("Work")
        val a = f.h.insertAndSync(Note(title = "A", content = "x", notebookId = work.id))

        f.notebooks.delete(work)
        f.h.repository.syncNotes()
        f.h.flushRemoteActions()
        f.h.syncNotes()

        assertEquals("A.md", f.mappingPath(a))
        assertFalse("Work" in f.folders())
        assertFalse(f.h.vaultFs.readText("A.md")!!.contains("Work"))
        assertNull(f.h.getNote(a)!!.notebookId)
        assertNull(f.h.notebookDao.getByName("Work").first())
        assertEquals(1, f.h.localNoteCount())
    }
}
