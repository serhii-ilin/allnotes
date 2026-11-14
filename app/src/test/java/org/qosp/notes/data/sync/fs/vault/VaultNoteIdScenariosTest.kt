package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.preferences.CloudService
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** A renamed file stays the same note, and files that have no id yet gain one. */
@RunWith(RobolectricTestRunner::class)
class VaultNoteIdScenariosTest {

    private suspend fun NoteRepositoryVaultHarness.pathOf(id: Long) =
        idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!

    private suspend fun NoteRepositoryVaultHarness.mapping(id: Long) =
        idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!

    @Test
    fun newFile_gainsAnId_andASecondSyncDoesNotRewriteIt() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        h.vaultFs.writeText("Plain.md", "---\ntitle: \"Plain\"\n---\nhello\n")
        h.syncNotes()
        val raw = h.vaultFs.readText("Plain.md").orEmpty()
        val id = VaultNoteDocument.parse(raw).frontMatter.id
        assertNotNull(id)
        assertTrue(id!!.isNotBlank())
        h.syncNotes()
        assertEquals(raw, h.vaultFs.readText("Plain.md"))
        assertEquals(1, h.localNoteCount())
    }

    @Test
    fun renamedFile_keepsTheNoteAndFollowsTheNotebook() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val workId = h.notebookDao.insert(Notebook("Work"))
        val id = h.insertAndSync(Note(title = "Plan", content = "Body", notebookId = workId))
        val raw = h.vaultFs.readText(h.pathOf(id))!!
        h.vaultFs.delete(h.pathOf(id))
        h.vaultFs.writeText("Job/Plan.md", raw.replace("notebook: \"Work\"", "notebook: \"Job\""))

        h.syncNotes()

        assertEquals(1, h.localNoteCount())
        assertFalse(h.getNote(id)!!.isDeleted)
        assertEquals("Job/Plan.md", h.pathOf(id))
        assertEquals("Job", h.notebookDao.getById(h.getNote(id)!!.notebookId!!).first()!!.name)
        assertTrue(h.conflictFiles().isEmpty())
    }

    @Test
    fun renamedFile_withLocalEdit_isPushedAtTheNewPath() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val workId = h.notebookDao.insert(Notebook("Work"))
        val id = h.insertAndSync(Note(title = "Plan", content = "old", notebookId = workId))
        h.repository.updateNotes(
            h.getNote(id)!!.copy(content = "new", modifiedDate = Instant.now().epochSecond + 10),
            sync = false,
        )
        val raw = h.vaultFs.readText(h.pathOf(id))!!
        h.vaultFs.delete(h.pathOf(id))
        // Same bytes, new folder: the local edit is not a conflict with the move.
        h.vaultFs.writeText("Job/Plan.md", raw)

        h.syncNotes()

        assertEquals("Job/Plan.md", h.pathOf(id))
        assertFalse(h.vaultFs.isFile("Work/Plan.md"))
        assertTrue(h.vaultFs.readText("Job/Plan.md").orEmpty().contains("new"))
        assertEquals(1, h.localNoteCount())
    }

    @Test
    fun fileWithoutId_matchesAUniqueBody() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Plan", content = "Only this"))
        val path = h.pathOf(id)
        val stripped = h.vaultFs.readText(path)!!.lines().filterNot { it.trim().startsWith("allnotes-id:") }.joinToString("\n")
        assertFalse(stripped.contains("allnotes-id:"))
        val state = VaultMappingState.resolve(h.mapping(id).extras)!!.copy(noteUid = "")
        h.idMappingDao.update(h.mapping(id).copy(extras = VaultMappingState.encode(state)))
        h.vaultFs.delete(path)
        h.vaultFs.writeText("Renamed.md", stripped)

        h.syncNotes()

        assertEquals(1, h.localNoteCount())
        assertFalse(h.getNote(id)!!.isDeleted)
        assertEquals("Renamed.md", h.pathOf(id))
        assertEquals("Renamed", h.getNote(id)!!.title)
        assertFalse(VaultNoteDocument.parse(h.vaultFs.readText("Renamed.md")!!).frontMatter.id.isNullOrBlank())
    }

    @Test
    fun twoCopiesOfTheSameId_becomeTwoNotesWithDistinctIds() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Plan", content = "Body"))
        val raw = h.vaultFs.readText(h.pathOf(id))!!
        h.vaultFs.writeText("Plan-copy.md", raw.replace("Body", "Body from elsewhere"))

        h.syncNotes()
        h.syncNotes()

        assertEquals(2, h.localNoteCount())
        assertEquals("Plan.md", h.pathOf(id))
        assertTrue(h.vaultFs.isFile("Plan-copy.md"))
        val copy = h.noteDao.getAll(org.qosp.notes.preferences.SortMethod.TITLE_ASC).first()
            .single { it.id != id }
        val copyPath = h.idMappingDao.getByLocalIdAndProvider(copy.id, CloudService.FILE_STORAGE)!!.storageUri
        assertEquals("Plan-copy.md", copyPath)
        assertFalse(copy.isDeleted)
        assertEquals("Body from elsewhere", copy.content.trim())
        val planId = VaultNoteDocument.parse(h.vaultFs.readText("Plan.md").orEmpty()).frontMatter.id
        val copyId = VaultNoteDocument.parse(h.vaultFs.readText("Plan-copy.md").orEmpty()).frontMatter.id
        assertNotNull(planId)
        assertNotNull(copyId)
        assertNotEquals(planId, copyId)
    }
}
