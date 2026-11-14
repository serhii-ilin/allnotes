package org.qosp.notes.data.sync.core

import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.junit4.MockKRule
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.IdMappingRepository
import org.qosp.notes.data.sync.fs.vault.VaultContentHash
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultSyncImport
import org.qosp.notes.preferences.CloudService

class SynchronizeNotesMappedLocalOnlyTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    private val idMappingRepository = io.mockk.mockk<IdMappingRepository>()
    private val notebookDao = io.mockk.mockk<NotebookDao>()
    private lateinit var synchronizeNotes: SynchronizeNotes

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        coEvery { notebookDao.getById(any()) } returns flowOf(null)
        synchronizeNotes = SynchronizeNotes(idMappingRepository, notebookDao)
    }

    @Test
    fun mappedFormerLocalOnly_withRemotePresent_neverSchedulesDelete() = runTest {
        val path = "Note.md"
        val local = Note(id = 1L, title = "Note", content = "Body", isLocalOnly = true, modifiedDate = 50L)
        val state = VaultMappingState(
            baselineHash = VaultSyncImport.canonicalFileHash(local, path, null, ""),
            vaultGeneration = 1L,
        )
        val mapping = IdMapping(
            localNoteId = 1L,
            remoteNoteId = null,
            provider = CloudService.FILE_STORAGE,
            extras = VaultMappingState.encode(state),
            isDeletedLocally = false,
            storageUri = path,
        )
        val yaml = "---\n---\nBody"
        val remote = SyncNote(
            id = 0,
            idStr = path,
            title = "Note",
            content = yaml,
            lastModified = 50L,
            extra = VaultContentHash.ofText(yaml),
        )
        coEvery { idMappingRepository.getAllByProvider(CloudService.FILE_STORAGE) } returns listOf(mapping)

        repeat(3) {
            val result = synchronizeNotes(
                listOf(local),
                listOf(remote),
                CloudService.FILE_STORAGE,
                vaultExportCutoffEpoch = 100L,
            )
            assertFalse(result.localUpdates.any { it is NoteAction.Delete })
            assertFalse(result.remoteUpdates.any { it is NoteAction.Create })
        }
    }

    @Test
    fun mappedFormerLocalOnly_stableAcrossCycles_noDuplicateCreate() = runTest {
        val path = "Exported.md"
        val local = Note(id = 2L, title = "Exported", content = "X", isLocalOnly = true, modifiedDate = 10L)
        val state = VaultMappingState(
            baselineHash = VaultSyncImport.canonicalFileHash(local, path, null, ""),
            vaultGeneration = 1L,
        )
        val mapping = IdMapping(
            localNoteId = 2L,
            remoteNoteId = null,
            provider = CloudService.FILE_STORAGE,
            extras = VaultMappingState.encode(state),
            isDeletedLocally = false,
            storageUri = path,
        )
        val yaml = "---\n---\nX"
        val remote = SyncNote(
            id = 0,
            idStr = path,
            title = "Exported",
            content = yaml,
            lastModified = 10L,
            extra = VaultContentHash.ofText(yaml),
        )
        coEvery { idMappingRepository.getAllByProvider(CloudService.FILE_STORAGE) } returns listOf(mapping)

        val creates = (1..3).sumOf {
            synchronizeNotes(listOf(local), listOf(remote), CloudService.FILE_STORAGE, vaultExportCutoffEpoch = 1L)
                .remoteUpdates.count { it is NoteAction.Create }
        }
        assertTrue(creates == 0)
    }
}
