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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

class VaultImportMappingLifecycleTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK
    private lateinit var idMappingRepository: IdMappingRepository

    @MockK
    private lateinit var notebookDao: NotebookDao

    @InjectMockKs
    private lateinit var synchronizeNotes: SynchronizeNotes

    private val path = "Work/Imported.md"

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        coEvery { notebookDao.getById(any()) } returns flowOf(null)
    }

    @Test
    fun fromDiskSync_foreignImport_isCleanLogicalBaseline() {
        val raw = """
            |---
            |aliases:
            |  - desktop
            |---
            |Body
        """.trimMargin()
        val imported = VaultSyncImport.noteFromRemote(
            SyncNote(0, path, "Imported", raw, 1L, VaultContentHash.ofText(raw)),
        )
        val state = VaultMappingState.fromDiskSync(
            raw,
            imported,
            path,
            VaultSyncImport.vaultPathsFromNote(imported),
            vaultGeneration = 1L,
        )
        assertTrue(state.preservedYaml.contains("aliases"))
        val canonical = VaultSyncImport.canonicalFileHash(imported, path, null, state.preservedYaml)
        assertEquals(state.baselineHash, canonical)
        assertFalse(VaultSyncImport.isLocallyDirty(imported, path, state, null))
    }

}
