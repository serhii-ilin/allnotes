package org.qosp.notes.data.sync.core

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.IdMappingRepository
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.preferences.CloudService

class SynchronizeNotesMissingFileTest {

    @Test
    fun blocker5_missingFile_clearsMappingLikeBin() = runTest {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        val idMappingRepository = mockk<IdMappingRepository>()
        coEvery { mockk<NotebookDao>().getById(any()) } returns flowOf(null)
        val notebookDao = mockk<NotebookDao>()
        coEvery { notebookDao.getById(any()) } returns flowOf(null)
        val sync = SynchronizeNotes(idMappingRepository, notebookDao)
        val path = "Vanish.md"
        val mapping = IdMapping(
            localNoteId = 4L,
            remoteNoteId = null,
            provider = CloudService.FILE_STORAGE,
            extras = VaultMappingState.encode(VaultMappingState(baselineHash = "h", missingScanCount = 1)),
            isDeletedLocally = false,
            storageUri = path,
        )
        val local = Note(id = 4L, title = "Vanish", content = "Body")
        coEvery { idMappingRepository.getAllByProvider(CloudService.FILE_STORAGE) } returns listOf(mapping)
        val result = sync(listOf(local), emptyList(), CloudService.FILE_STORAGE, vaultExportCutoffEpoch = 1L)
        assertTrue(result.localUpdates.any { it is NoteAction.Delete })
        val binMapping = result.newMappings.first()
        assertEquals(null, binMapping.storageUri)
        assertTrue(binMapping.isDeletedLocally)
        val state = VaultMappingState.resolve(binMapping.extras)!!
        assertEquals(path, state.binnedVaultPath)
    }

    @Test
    fun firstMiss_keepsNoteAndMapping_onlyCountsTheMiss() = runTest {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        val idMappingRepository = mockk<IdMappingRepository>()
        val notebookDao = mockk<NotebookDao>()
        coEvery { notebookDao.getById(any()) } returns flowOf(null)
        val sync = SynchronizeNotes(idMappingRepository, notebookDao)
        val path = "Vanish.md"
        val mapping = IdMapping(
            localNoteId = 4L,
            remoteNoteId = null,
            provider = CloudService.FILE_STORAGE,
            extras = VaultMappingState.encode(VaultMappingState(baselineHash = "h")),
            isDeletedLocally = false,
            storageUri = path,
        )
        val local = Note(id = 4L, title = "Vanish", content = "Body")
        coEvery { idMappingRepository.getAllByProvider(CloudService.FILE_STORAGE) } returns listOf(mapping)
        val result = sync(listOf(local), emptyList(), CloudService.FILE_STORAGE, vaultExportCutoffEpoch = 1L)
        assertTrue(result.localUpdates.none { it is NoteAction.Delete })
        val update = result.newMappings.single()
        assertEquals(path, update.storageUri)
        assertEquals(false, update.isDeletedLocally)
        assertEquals(1, VaultMappingState.resolve(update.extras)!!.missingScanCount)
    }
}
