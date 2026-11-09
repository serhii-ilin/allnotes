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
import org.qosp.notes.data.model.Tag
import org.qosp.notes.data.repo.IdMappingRepository
import org.qosp.notes.data.sync.core.SynchronizeNotes
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.preferences.CloudService

/**
 * Baseline must reflect Room state **after** folder notebook + YAML tags (review item 1).
 */
class VaultImportBaselineOrderingTest {

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
        every { Log.d(any<String>(), any<String>()) } returns 0
        coEvery { notebookDao.getById(42L) } returns flowOf(org.qosp.notes.data.model.Notebook(id = 42L, name = "Work"))
        coEvery { notebookDao.getById(not(42L)) } returns flowOf(null)
    }

    private fun taggedFileBody(): String =
        """
            |---
            |tags:
            |  - alpha
            |notebook: "Work"
            |---
            |Body
        """.trimMargin()

    private fun noteAfterMetadata(body: String = "Body"): Note {
        val raw = taggedFileBody()
        val imported = VaultSyncImport.noteFromRemote(
            SyncNote(0, path, "Note", raw, 10L, VaultContentHash.ofText(raw)),
        )
        return imported.copy(
            id = 1L,
            notebookId = 42L,
            tags = listOf(Tag(id = 1L, name = "alpha")),
            content = body,
            modifiedDate = 10L,
        )
    }

    @Test
    fun baselineAfterTagsAndNotebook_isNotDirty() {
        val raw = taggedFileBody()
        val note = noteAfterMetadata()
        val state = VaultMappingState.fromDiskSync(
            raw,
            note,
            path,
            emptyList(),
            vaultGeneration = 1L,
            assignedNotebookName = "Work",
        )
        assertFalse(VaultSyncImport.isLocallyDirty(note, path, state, "Work"))
    }

    @Test
    fun importLifecycle_secondSyncClean_desktopEditPulls_noFalseConflict() = runTest {
        val raw = taggedFileBody()
        val note = noteAfterMetadata()
        val state = VaultMappingState.fromDiskSync(
            raw,
            note,
            path,
            emptyList(),
            1L,
            "Work",
        )
        val mapping = IdMapping(
            localNoteId = 1L,
            remoteNoteId = null,
            provider = CloudService.FILE_STORAGE,
            extras = VaultMappingState.encode(state),
            isDeletedLocally = false,
            storageUri = path,
        )
        coEvery { idMappingRepository.getAllByProvider(CloudService.FILE_STORAGE) } returns listOf(mapping)
        val unchangedRemote = SyncNote(0, path, "Note", raw, 10L, VaultContentHash.ofText(raw))
        val second = synchronizeNotes(listOf(note), listOf(unchangedRemote), CloudService.FILE_STORAGE)
        assertEquals("unexpected churn on unchanged file: $second", 0, second.vaultConflicts.size)
        assertEquals(0, second.remoteUpdates.size)

        val editedRaw = raw.replace("Body", "Body from desktop")
        val edited = SyncNote(0, path, "Note", editedRaw, 50L, VaultContentHash.ofText(editedRaw))
        val third = synchronizeNotes(listOf(note), listOf(edited), CloudService.FILE_STORAGE)
        assertEquals(1, third.localUpdates.size)
        assertEquals(0, third.vaultConflicts.size)
        assertEquals(0, third.remoteUpdates.size)
    }
}
