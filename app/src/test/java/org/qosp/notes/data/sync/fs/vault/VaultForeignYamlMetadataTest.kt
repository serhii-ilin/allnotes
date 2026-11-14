package org.qosp.notes.data.sync.fs.vault

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
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.IdMappingRepository
import org.qosp.notes.data.sync.core.SynchronizeNotes
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.preferences.CloudService

class VaultForeignYamlMetadataTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK
    private lateinit var idMappingRepository: IdMappingRepository

    @MockK
    private lateinit var notebookDao: NotebookDao

    @InjectMockKs
    private lateinit var synchronizeNotes: SynchronizeNotes

    private val path = "Note.md"

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        coEvery { notebookDao.getById(any()) } returns flowOf(null)
    }

    @Test
    fun externalAliases_changeRemoteLogicalHash() {
        val local = Note(id = 1L, title = "t", content = "Body", modifiedDate = 10L)
        val baseline = VaultSyncImport.canonicalFileHash(local, path, null, "")
        val withAliases = """
            |---
            |aliases:
            |  - desk
            |---
            |Body
        """.trimMargin()
        val remoteLogical = VaultSyncImport.logicalHashFromRemoteFile(
            SyncNote(0, path, "t", withAliases, 10L, ""),
            local,
            path,
            null,
        )
        assertNotEquals(baseline, remoteLogical)
    }

    @Test
    fun externalAliases_triggersPull_notConflict() = runTest {
        val local = Note(id = 1L, title = "t", content = "Body", modifiedDate = 10L)
        val baseline = VaultSyncImport.canonicalFileHash(local, path, null, "")
        val state = VaultMappingState(baselineHash = baseline, preservedYaml = "")
        val withAliases = """
            |---
            |aliases:
            |  - desk
            |---
            |Body
        """.trimMargin()
        val mapping = IdMapping(
            localNoteId = 1L,
            remoteNoteId = null,
            provider = CloudService.FILE_STORAGE,
            extras = VaultMappingState.encode(state),
            isDeletedLocally = false,
            storageUri = path,
        )
        coEvery { idMappingRepository.getAllByProvider(CloudService.FILE_STORAGE) } returns listOf(mapping)
        val edited = SyncNote(0, path, "t", withAliases, 10L, "")
        val first = synchronizeNotes(listOf(local), listOf(edited), CloudService.FILE_STORAGE)
        assertEquals(1, first.localUpdates.size)
        assertEquals(0, first.vaultConflicts.size)
    }
}
