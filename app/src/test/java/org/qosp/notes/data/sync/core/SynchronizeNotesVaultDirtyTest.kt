package org.qosp.notes.data.sync.core

import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.IdMappingRepository
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultSyncImport
import org.qosp.notes.preferences.CloudService

class SynchronizeNotesVaultDirtyTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK
    private lateinit var idMappingRepository: IdMappingRepository

    @MockK
    private lateinit var notebookDao: NotebookDao

    @InjectMockKs
    private lateinit var synchronizeNotes: SynchronizeNotes

  private val path = "Work/Note.md"

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        coEvery { notebookDao.getById(any()) } returns flowOf(null)
    }

    @Test
    fun dirtyLocal_newerRemoteMtime_doesNotPull() = runTest {
        val local = Note(id = 1L, title = "t", content = "local edit", modifiedDate = 100L)
        val baseline = VaultSyncImport.canonicalFileHash(Note(title = "t", content = "synced"), path, null, "")
        val state = VaultMappingState(baselineHash = baseline)
        val mapping = IdMapping(
            localNoteId = 1L,
            remoteNoteId = null,
            provider = CloudService.FILE_STORAGE,
            extras = VaultMappingState.encode(state),
            isDeletedLocally = false,
            storageUri = path,
        )
        coEvery { idMappingRepository.getAllByProvider(CloudService.FILE_STORAGE) } returns listOf(mapping)
        val remote = SyncNote(
            id = 0,
            idStr = path,
            title = "t",
            content = "---\n---\nsynced\n",
            lastModified = 999L,
            extra = baseline,
        )
        val result = synchronizeNotes(listOf(local), listOf(remote), CloudService.FILE_STORAGE)
        assertEquals(0, result.localUpdates.size)
        assertEquals(0, result.remoteUpdates.size)
    }

    @Test
    fun dirtyAttachmentMembership_newerRemoteMtime_doesNotPull() = runTest {
        val vaultRel = "vaultrel:Work/Note/x.jpg"
        val local = Note(
            id = 1L,
            title = "t",
            content = "same body",
            modifiedDate = 100L,
            attachments = emptyList(),
        )
        val baselineNote = Note(title = "t", content = "same body", attachments = listOf())
        val baseline = VaultSyncImport.canonicalFileHash(baselineNote, path, null, "")
        val state = VaultMappingState(baselineHash = baseline, vaultAttachmentPaths = listOf(vaultRel))
        assertTrue(VaultSyncImport.isLocallyDirty(local, path, state, null))
        val mapping = IdMapping(
            localNoteId = 1L,
            remoteNoteId = null,
            provider = CloudService.FILE_STORAGE,
            extras = VaultMappingState.encode(state),
            isDeletedLocally = false,
            storageUri = path,
        )
        coEvery { idMappingRepository.getAllByProvider(CloudService.FILE_STORAGE) } returns listOf(mapping)
        val remoteYaml = "---\n---\nsame body\n![x](Note/x.jpg)"
        val remote = SyncNote(
            id = 0,
            idStr = path,
            title = "t",
            content = remoteYaml,
            lastModified = 500L,
            extra = baseline,
        )
        val result = synchronizeNotes(listOf(local), listOf(remote), CloudService.FILE_STORAGE)
        assertEquals(0, result.localUpdates.size)
    }
}
