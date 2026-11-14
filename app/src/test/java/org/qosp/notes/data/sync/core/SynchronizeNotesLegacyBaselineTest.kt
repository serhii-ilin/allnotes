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
import org.qosp.notes.data.sync.fs.vault.VaultMappingExtras
import org.qosp.notes.data.sync.fs.vault.VaultSyncImport
import org.qosp.notes.preferences.CloudService

/**
 * A mapping that only carries a legacy baseline (no [org.qosp.notes.data.sync.fs.vault.VaultMappingState])
 * still pushes a locally changed note when the file itself is unchanged. For a mapping with a state that arm
 * is unreachable: `isLocallyDirty` already proved the local hash equals the baseline.
 */
class SynchronizeNotesLegacyBaselineTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK
    private lateinit var idMappingRepository: IdMappingRepository

    @MockK
    private lateinit var notebookDao: NotebookDao

    @InjectMockKs
    private lateinit var synchronizeNotes: SynchronizeNotes

    private val raw = "---\ncreated: 1000\nmodified: 1000\n---\nDisk body\n"

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        coEvery { notebookDao.getById(any()) } returns flowOf(null)
    }

    @Test
    fun legacyBaselineWithAnUnchangedFile_pushesTheLocalNote() = runTest {
        val local = Note(
            id = 1L,
            title = "Plan",
            content = "Local body",
            creationDate = 1_000L,
            modifiedDate = 1_000L,
        )
        val remote = SyncNote(id = 0L, idStr = "Plan.md", title = "Plan", lastModified = 1_000L, content = raw)
        val baseline = VaultSyncImport.logicalHashFromRemoteFile(remote, local, "Plan.md", null)
        val mapping = IdMapping(
            localNoteId = local.id,
            remoteNoteId = null,
            provider = CloudService.FILE_STORAGE,
            extras = VaultMappingExtras.baselineOnly(baseline),
            isDeletedLocally = false,
            storageUri = "Plan.md",
        )
        coEvery { idMappingRepository.getAllByProvider(CloudService.FILE_STORAGE) } returns listOf(mapping)

        val result = synchronizeNotes(listOf(local), listOf(remote), CloudService.FILE_STORAGE)

        assertEquals(0, result.localUpdates.size)
        assertEquals(1, result.remoteUpdates.size)
        assertTrue(result.remoteUpdates.single() is NoteAction.Update)
        assertEquals(local, (result.remoteUpdates.single() as NoteAction.Update).note)
    }
}
