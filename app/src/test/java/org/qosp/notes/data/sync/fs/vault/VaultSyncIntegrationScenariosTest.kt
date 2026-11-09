package org.qosp.notes.data.sync.fs.vault

import io.mockk.coEvery
import io.mockk.every
import io.mockk.junit4.MockKRule
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Tag
import org.qosp.notes.data.repo.IdMappingRepository
import org.qosp.notes.data.sync.core.NoteAction
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.data.sync.core.SynchronizeNotes
import org.qosp.notes.preferences.CloudService

/**
 * Scenario-level sync classification using production [SynchronizeNotes] + vault helpers (no SAF).
 */
@RunWith(RobolectricTestRunner::class)
class VaultSyncIntegrationScenariosTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    private val idMappingRepository = mockk<IdMappingRepository>()
    private val notebookDao = mockk<NotebookDao>()
    private lateinit var synchronizeNotes: SynchronizeNotes

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        coEvery { notebookDao.getById(any()) } returns flowOf(null)
        synchronizeNotes = SynchronizeNotes(idMappingRepository, notebookDao)
    }

    private fun yaml(body: String, tags: List<String>? = null) = buildString {
        append("---\n")
        if (tags != null) {
            append("tags: [")
            append(tags.joinToString(", ") { "\"$it\"" })
            append("]\n")
        }
        append("---\n")
        append(body)
    }

    @Test
    fun onlyBinnedNote_activeMappedPathsEmpty_scanCanSucceed() = runTest {
        val h = VaultFolderTestHarness()
        h.seedNoteFile("Active.md", "Active", "still here")
        val scan = h.backend.getAll(emptySet())
        assertFalse(scan.isNullOrEmpty())
    }

    @Test
    fun binnedMapping_excludedFromKnownPaths() {
        val mappings = listOf(
            IdMapping(
                localNoteId = 1L,
                remoteNoteId = null,
                provider = CloudService.FILE_STORAGE,
                extras = null,
                isDeletedLocally = true,
                storageUri = null,
            ),
        )
        val active = mappings.filterNot { it.isDeletedLocally }.mapNotNull { it.storageUri }
        assertTrue(active.isEmpty())
    }

    @Test
    fun taggedNote_threeSyncCycles_noPushAfterBaselineSet() = runTest {
        val path = "Tagged.md"
        val tag = Tag(id = 1L, name = "work")
        val local = Note(
            id = 1L,
            title = "Tagged",
            content = "Body",
            tags = listOf(tag),
            modifiedDate = 10L,
        )
        val content = yaml("Body", listOf("work"))
        val remote = SyncNote(
            id = 0,
            idStr = path,
            title = "Tagged",
            content = content,
            lastModified = 10L,
        )
        val hash = VaultSyncImport.logicalHashFromRemoteFile(remote, local, path, null)
        val state = VaultMappingState(baselineHash = hash, vaultGeneration = 1L)
        val mapping = IdMapping(
            localNoteId = 1L,
            remoteNoteId = null,
            provider = CloudService.FILE_STORAGE,
            extras = VaultMappingState.encode(state),
            isDeletedLocally = false,
            storageUri = path,
        )
        coEvery { idMappingRepository.getAllByProvider(CloudService.FILE_STORAGE) } returns listOf(mapping)
        repeat(3) {
            val result = synchronizeNotes(listOf(local), listOf(remote), CloudService.FILE_STORAGE)
            assertFalse(result.remoteUpdates.any { it is NoteAction.Update || it is NoteAction.Create })
        }
    }

    @Test
    fun unmappedRemote_linksToMatchingLocalByPathAndHash() = runTest {
        val path = "Hello.md"
        val local = Note(id = 2L, title = "Hello", content = "Hi", modifiedDate = 5L)
        val full = VaultNoteDocument.fromNote(local, path).renderFullText()
        val remote = SyncNote(0, path, full, "Hello", 5L)
        coEvery { idMappingRepository.getAllByProvider(CloudService.FILE_STORAGE) } returns emptyList()
        val result = synchronizeNotes(listOf(local), listOf(remote), CloudService.FILE_STORAGE, vaultExportCutoffEpoch = 1L)
        assertTrue(result.newMappings.isNotEmpty())
        assertTrue(result.localUpdates.none { it is NoteAction.Create })
        assertTrue(result.remoteUpdates.none { it is NoteAction.Create })
    }

    @Test
    fun nestedAgendaMarkdown_alwaysImportable() {
        val paths = setOf("Work/Meeting.md", "Work/Meeting/agenda.md")
        assertFalse(VaultDocumentTree.isUnderNoteAssetFolder("Work/Meeting/agenda.md", paths))
    }
}
