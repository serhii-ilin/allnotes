package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.preferences.CloudService
import org.robolectric.RobolectricTestRunner

/**
 * A rename or move made on another device is a pull, not a conflict, even when the file's
 * front matter changes because its path changed.
 */
@RunWith(RobolectricTestRunner::class)
class VaultRemoteRenameNoConflictTest {

    private suspend fun NoteRepositoryVaultHarness.pathOf(id: Long) =
        idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!

    private suspend fun NoteRepositoryVaultHarness.mapping(id: Long) =
        idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!

    private fun String.withModified(epoch: Long) =
        replace(Regex("(?m)^modified: \\d+$"), "modified: $epoch")

    /** The export pass records the file mtime on the note; settle it so the file and Room agree before a change. */
    private suspend fun NoteRepositoryVaultHarness.settleModifiedDate(id: Long, epoch: Long) {
        repository.updateNotes(getNote(id)!!.copy(modifiedDate = epoch), sync = false)
    }

    /**
     * A file manager rename keeps the bytes and (within a second) the mtime, so neither the hash nor the mtime
     * says "pull". The title must still follow the file name, and later syncs must not write the old title back.
     */
    @Test
    fun remoteRenameWithSameBytesAndMtime_titleFollowsTheFileAndNothingIsWrittenBack() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Plan", content = "Body"))
        // Let the export settle: the next sync re-pushes once after the mtime stamp, then the note is clean.
        h.syncNotes()
        h.syncNotes()
        val path = h.pathOf(id)
        val before = h.vaultFs.readText(path)!!
        h.vaultFs.renameFile(path, "Ideas.md")

        h.syncNotes()
        h.syncNotes()
        h.syncNotes()

        assertEquals("Ideas.md", h.pathOf(id))
        assertEquals("Ideas", h.getNote(id)?.title)
        assertEquals(1, h.localNoteCount())
        assertEquals(emptyList<String>(), h.conflictFiles())
        val after = h.vaultFs.readText("Ideas.md")!!
        assertTrue("no title line pushed back: $after", after.lines().none { it.startsWith("title:") })
        assertEquals(VaultNoteDocument.parse(before).body, VaultNoteDocument.parse(after).body)
    }

    @Test
    fun remoteTitleRename_pullsWithoutConflictCopy() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Plan", content = "Body", modifiedDate = 1_000))
        h.settleModifiedDate(id, 1_000)
        val path = h.pathOf(id)
        val renamed = h.vaultFs.readText(path)!!.withModified(2_000)
        h.vaultFs.delete(path)
        h.vaultFs.writeText("Ideas.md", renamed)

        h.syncNotes()
        h.syncNotes()

        val mapping = h.mapping(id)
        assertEquals("Ideas.md", mapping.storageUri)
        assertEquals("Ideas", h.getNote(id)?.title)
        assertEquals(1, h.localNoteCount())
        assertEquals(emptyList<String>(), h.conflictFiles())
        val state = VaultMappingState.resolve(mapping.extras)
        assertNull(state?.conflictCopyPath)
        assertNull(state?.conflictRemoteHash)
    }

    @Test
    fun remoteNotebookMove_pullsWithoutConflictCopy() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val workId = h.notebookDao.insert(Notebook("Work"))
        val id = h.insertAndSync(
            Note(title = "Plan", content = "Body", notebookId = workId, modifiedDate = 1_000),
        )
        h.settleModifiedDate(id, 1_000)
        val path = h.pathOf(id)
        val moved = h.vaultFs.readText(path)!!
            .replace("notebook: \"Work\"", "notebook: \"Job\"")
            .withModified(2_000)
        h.vaultFs.delete(path)
        h.vaultFs.writeText("Job/Plan.md", moved)

        h.syncNotes()
        h.syncNotes()

        assertEquals("Job/Plan.md", h.pathOf(id))
        assertEquals("Job", h.notebookDao.getById(h.getNote(id)!!.notebookId!!).first()!!.name)
        assertEquals(1, h.localNoteCount())
        assertEquals(emptyList<String>(), h.conflictFiles())
    }

    @Test
    fun remoteRenameWithLocalEdit_keepsBothSides() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Plan", content = "Body", modifiedDate = 1_000))
        h.settleModifiedDate(id, 1_000)
        val path = h.pathOf(id)
        h.repository.updateNotes(
            h.getNote(id)!!.copy(content = "local edit", modifiedDate = 1_500),
            sync = false,
        )
        val renamed = h.vaultFs.readText(path)!!.withModified(2_000)
        h.vaultFs.delete(path)
        h.vaultFs.writeText("Ideas.md", renamed)

        h.syncNotes()
        h.syncNotes()

        val localText = h.getNote(id)?.content.orEmpty()
        val vaultText = h.vaultFs.snapshotPaths().joinToString("\n") { h.vaultFs.readText(it).orEmpty() }
        val localSurvives = localText.contains("local edit") || vaultText.contains("local edit")
        assertTrue("the local edit must survive", localSurvives)
        assertTrue("the remote text must survive", localText.contains("Body") || vaultText.contains("Body"))
        // The conflict copy is its own note now: a shared id never hides a file (AUD-5).
        assertEquals(2, h.localNoteCount())
        val conflictCopy = h.conflictFiles().single()
        assertTrue(
            "the conflict copy must be mapped to its own note: $conflictCopy",
            h.idMappingDao.getAllByCloudService(CloudService.FILE_STORAGE).any { it.storageUri == conflictCopy },
        )
    }
}
