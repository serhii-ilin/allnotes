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
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.repo.IdMappingRepository
import org.qosp.notes.data.sync.core.NoteAction
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.data.sync.core.SynchronizeNotes
import org.qosp.notes.preferences.CloudService

/**
 * Exercises production [SynchronizeNotes] + [VaultSyncImport] decision paths (not isolated helpers).
 */
class VaultSyncOrchestrationTest {

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
        coEvery { notebookDao.getById(9L) } returns flowOf(Notebook(id = 9L, name = "Journal"))
    }

    private fun mapping(state: VaultMappingState) = IdMapping(
        localNoteId = 1L,
        remoteNoteId = null,
        provider = CloudService.FILE_STORAGE,
        extras = VaultMappingState.encode(state),
        isDeletedLocally = false,
        storageUri = path,
    )

    private fun remoteBody(body: String, extra: String? = null): SyncNote {
        val yaml = """
            |---
            |pinned: false
            |archived: false
            |hidden: false
            |isList: false
            |markdown: true
            |color: "Default"
            |tags: []
            |notebook: "Journal"
            |---
            |$body
        """.trimMargin()
        return SyncNote(
            id = 0,
            idStr = path,
            title = "t",
            content = yaml,
            lastModified = 300L,
            extra = extra ?: VaultContentHash.ofText(yaml),
        )
    }

    @Test
    fun oneSidedDesktopEdit_pullsWithoutConflict() = runTest {
        val local = Note(id = 1L, title = "t", content = "Body", notebookId = 9L, modifiedDate = 100L)
        val state = VaultMappingState(
            baselineHash = VaultSyncImport.canonicalFileHash(local, path, "Journal", ""),
            preservedYaml = "",
            vaultGeneration = 2L,
        )
        coEvery { idMappingRepository.getAllByProvider(CloudService.FILE_STORAGE) } returns listOf(mapping(state))
        val foreignYaml = "aliases:\n  - desktop\n"
        val remoteText = "---\n$foreignYaml---\nBody\n"
        val remote = SyncNote(
            id = 0,
            idStr = path,
            title = "t",
            content = remoteText,
            lastModified = 400L,
            extra = VaultContentHash.ofText(remoteText),
        )
        val result = synchronizeNotes(listOf(local), listOf(remote), CloudService.FILE_STORAGE)
        assertEquals(1, result.localUpdates.size)
        assertEquals(0, result.vaultConflicts.size)
        assertEquals(0, result.remoteUpdates.size)
    }

    @Test
    fun repeatSyncWhenUnchanged_staysClean() = runTest {
        val remote = remoteBody("Body")
        val imported = VaultSyncImport.noteFromRemote(remote)
        val local = imported.copy(id = 1L, modifiedDate = 100L)
        val baseline = VaultSyncImport.canonicalFileHash(local, path, null, "")
        val state = VaultMappingState(baselineHash = baseline)
        coEvery { idMappingRepository.getAllByProvider(CloudService.FILE_STORAGE) } returns listOf(mapping(state))
        val syncedRemote = remote.copy(lastModified = local.modifiedDate)
        val first = synchronizeNotes(listOf(local), listOf(syncedRemote), CloudService.FILE_STORAGE)
        assertEquals(0, first.localUpdates.size)
        assertEquals(0, first.remoteUpdates.size)
        val second = synchronizeNotes(listOf(local), listOf(syncedRemote), CloudService.FILE_STORAGE)
        assertEquals(0, second.localUpdates.size)
        assertEquals(0, second.remoteUpdates.size)
        assertEquals(0, second.vaultConflicts.size)
    }

    @Test
    fun trueConflict_recordsOncePerRemoteHash() = runTest {
        val local = Note(id = 1L, title = "t", content = "Local", modifiedDate = 100L)
        val state = VaultMappingState(
            baselineHash = VaultSyncImport.canonicalFileHash(
                Note(title = "t", content = "Shared"),
                path,
                null,
                "",
            ),
        )
        coEvery { idMappingRepository.getAllByProvider(CloudService.FILE_STORAGE) } returns listOf(mapping(state))
        val remote = remoteBody("Remote", extra = VaultContentHash.ofText(remoteBody("Remote").content!!))
        val first = synchronizeNotes(listOf(local), listOf(remote), CloudService.FILE_STORAGE)
        assertEquals(1, first.vaultConflicts.size)
        val remoteHash = VaultSyncImport.canonicalFileHash(
            VaultSyncImport.noteFromRemote(remote, local),
            path,
            null,
            "",
        )
        val recorded = state.copy(
            conflictRemoteHash = remoteHash,
            conflictCopyPath = "Work/Note (conflict).md",
        )
        coEvery { idMappingRepository.getAllByProvider(CloudService.FILE_STORAGE) } returns
            listOf(mapping(recorded))
        val second = synchronizeNotes(listOf(local), listOf(remote), CloudService.FILE_STORAGE)
        assertEquals(0, second.vaultConflicts.size)
    }

    @Test
    fun legacyNoteBeforeCutoff_notExportedAfterFolderSwitch() = runTest {
        val legacy = Note(id = 1L, title = "Old", creationDate = 50L, modifiedDate = 50L)
        coEvery { idMappingRepository.getAllByProvider(CloudService.FILE_STORAGE) } returns emptyList()
        val result = synchronizeNotes(
            listOf(legacy),
            emptyList(),
            CloudService.FILE_STORAGE,
            vaultExportCutoffEpoch = 200L,
        )
        assertEquals(0, result.remoteUpdates.size)
    }

    @Test
    fun attachmentRemoval_refreshThenPushPreservesLocalMembership() {
        val vaultRel = "vaultrel:Work/Note/x.jpg"
        val remote = remoteBody("![x](Note/x.jpg)")
        val baseline = setOf(vaultRel)
        val afterRefresh = VaultSyncImport.attachmentsAfterDirtyRefresh(emptyList(), remote, baseline)
        assertTrue(afterRefresh.isEmpty())
        val local = Note(title = "t", content = "edited", attachments = afterRefresh)
        val disk = VaultSyncImport.noteFromRemote(remote)
        val state = VaultMappingState(
            baselineHash = VaultSyncImport.canonicalFileHash(Note(title = "t", content = "old"), path, null, ""),
            vaultAttachmentPaths = listOf(vaultRel),
        )
        assertTrue(VaultSyncImport.isLocallyDirty(local, path, state, null))
        val afterPush = VaultSyncImport.mergeNoteAfterPush(local, disk, state, path, null)
        assertFalse(afterPush.attachments.any { it.path == vaultRel })
        val pending = Attachment(Attachment.Type.IMAGE, "content://p", "p")
        val withPending = local.copy(attachments = listOf(pending))
        val afterPushPending = VaultSyncImport.mergeNoteAfterPush(withPending, disk, state, path, null)
        assertTrue(afterPushPending.attachments.any { it.path == "content://p" })
    }

    @Test
    fun vaultFileOpFence_staleByTreeUri() {
        val fence = VaultFileOpFence(1L, "content://vault-a")
        assertTrue(fence.isStale(1L, "content://vault-b"))
        assertFalse(fence.isStale(1L, "content://vault-a"))
    }
}
