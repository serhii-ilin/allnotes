package org.qosp.notes.data.sync.core

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.IdMappingRepository
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultNoteDocument
import org.qosp.notes.preferences.CloudService

class SynchronizeNotesFileReturnTest {

    private val idMappingRepository = mockk<IdMappingRepository>()
    private val notebookDao = mockk<NotebookDao>()
    private val sync = SynchronizeNotes(idMappingRepository, notebookDao)

    @Test
    fun blocker5_binnedLocalAndReturnedRemote_pullsInsteadOfRemoteDelete() = runTest {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        coEvery { notebookDao.getById(any()) } returns flowOf(null)
        val path = "Back.md"
        val local = Note(id = 9L, title = "Back", content = "Body", isDeleted = true, modifiedDate = 1L)
        val remoteBody = VaultNoteDocument.fromNote(local.copy(isDeleted = false), path).renderFullText()
        val remote = SyncNote(0, path, remoteBody, "Back", 2L)
        val state = VaultMappingState(baselineHash = "x", binnedVaultPath = path)
        val mapping = IdMapping(
            localNoteId = 9L,
            remoteNoteId = null,
            provider = CloudService.FILE_STORAGE,
            extras = VaultMappingState.encode(state),
            isDeletedLocally = false,
            storageUri = path,
        )
        coEvery { idMappingRepository.getAllByProvider(CloudService.FILE_STORAGE) } returns listOf(mapping)
        val result = sync(listOf(local), listOf(remote), CloudService.FILE_STORAGE, vaultExportCutoffEpoch = 1L)
        assertTrue(result.localUpdates.any { it is NoteAction.Update })
        assertFalse(result.remoteUpdates.any { it is NoteAction.Delete })
    }
}
