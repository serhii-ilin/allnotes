package org.qosp.notes.components.backup

import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.Backup
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.IdMappingRepository
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupRestoreVaultTest {

    private fun manager(h: NoteRepositoryVaultHarness) = BackupManager(
        currentVersion = 1,
        noteRepository = h.repository,
        notebookRepository = mockk(relaxed = true),
        tagRepository = mockk(relaxed = true),
        reminderRepository = mockk(relaxed = true),
        idMappingRepository = IdMappingRepository(h.idMappingDao),
        reminderManager = mockk(relaxed = true),
        context = h.context,
    )

    private fun oldMapping(noteId: Long, provider: CloudService, path: String?, remoteId: Long? = null) = IdMapping(
        mappingId = noteId * 10,
        localNoteId = noteId,
        remoteNoteId = remoteId,
        provider = provider,
        extras = null,
        isDeletedLocally = false,
        storageUri = path,
    )

    @Test
    fun restoreWithOldVaultMappings_syncTwice_noDuplicatesNoBinned() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val backup = Backup(
            version = 1,
            notes = setOf(
                Note(id = 1, title = "First", content = "one", modifiedDate = 10L),
                Note(id = 2, title = "Second", content = "two", modifiedDate = 20L),
            ),
            idMappings = setOf(
                oldMapping(1, CloudService.FILE_STORAGE, "Old/First.md"),
                oldMapping(2, CloudService.FILE_STORAGE, "content://old/tree/x/Second.md"),
                oldMapping(2, CloudService.NEXTCLOUD, null, remoteId = 77L),
            ),
        )

        manager(h).restoreNotesFromBackup(backup)
        h.syncNotes()
        h.syncNotes()

        val notes = h.noteDao.getAll(SortMethod.TITLE_ASC).first()
        assertEquals(listOf("First", "Second"), notes.map { it.title })
        assertTrue("no binned notes expected", notes.none { it.isDeleted })
        assertEquals(setOf("First.md", "Second.md"), h.vaultFs.snapshotPaths().filter { it.endsWith(".md") }.toSet())
        notes.forEach {
            val mapping = h.idMappingDao.getByLocalIdAndProvider(it.id, CloudService.FILE_STORAGE)!!
            assertTrue(!mapping.isDeletedLocally && mapping.storageUri == "${it.title}.md")
            assertEquals(null, h.idMappingDao.getByLocalIdAndProvider(it.id, CloudService.NEXTCLOUD))
        }
    }
}
