package org.qosp.notes.data.sync.core

import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.qosp.notes.Config
import org.qosp.notes.data.dao.IdMappingDao
import org.qosp.notes.data.dao.NoteDao
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.NoteEntity
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.data.sync.fs.vault.VaultSyncImport
import org.qosp.notes.di.SyncScope
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.ui.utils.Toaster
import kotlin.time.Duration.Companion.milliseconds

class ProcessRemoteActionsPushCasTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
    }

    @Test
    fun pushApply_skippedWhenNoteChangedDuringPush_sameSecond() = runTest {
        val noteDao = mockk<NoteDao>()
        coEvery { noteDao.update(any<NoteEntity>()) } returns Unit
        coEvery { noteDao.update(*anyVararg<NoteEntity>()) } returns Unit
        coEvery { noteDao.updateLastModified(any(), any()) } returns Unit
        val idMappingDao = mockk<IdMappingDao>()
        val notebookDao = mockk<NotebookDao>(relaxed = true)
        val prefs = mockk<PreferenceRepository>()
        coEvery { prefs.getEncryptedString(any()) } returns flowOf("content://vault", "1")
        val coordinator = VaultSyncCoordinator(prefs)
        val backend = mockk<ISyncBackend>()
        every { backend.type } returns CloudService.FILE_STORAGE
        coEvery { backend.isAvailable() } returns AvailabilityStatus.Available
        val provider = mockk<BackendProvider>()
        every { provider.isSyncing } returns true
        every { provider.syncProvider } returns MutableStateFlow(backend)
        val path = "Note.md"
        val note = Note(id = 1L, title = "Note", content = "A", modifiedDate = 100L)
        val changed = note.copy(content = "B")
        val mapping = IdMapping(
            localNoteId = 1L,
            remoteNoteId = null,
            provider = CloudService.FILE_STORAGE,
            extras = VaultMappingState.encode(
                VaultMappingState(
                    baselineHash = VaultSyncImport.canonicalFileHash(note, path, null, ""),
                    vaultGeneration = 1L,
                ),
            ),
            isDeletedLocally = false,
            storageUri = path,
        )
        coEvery { idMappingDao.getByLocalIdAndProvider(1L, CloudService.FILE_STORAGE) } returns mapping
        coEvery { backend.updateNote(any(), any()) } returns mapping
        val remoteYaml = "---\n---\nA"
        coEvery { backend.getNote(mapping) } returns SyncNote(
            id = 0,
            idStr = path,
            title = "Note",
            content = remoteYaml,
            lastModified = 100L,
        )
        coEvery { noteDao.getById(1L) } returnsMany listOf(
            flowOf(note),
            flowOf(changed),
        )

        val scope = SyncScope(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val actions = ProcessRemoteActions(
            scope,
            provider,
            idMappingDao,
            noteDao,
            mockk<Toaster>(relaxed = true),
            coordinator,
            notebookDao,
            prefs,
        )
        actions(note.id, RemoteOperation.Update(note, vaultFence = mockk(relaxed = true)))
        advanceTimeBy(Config.RemoteUpdateDebounceTime + 100.milliseconds)
        coVerify(exactly = 0) { noteDao.update(any<NoteEntity>()) }
    }
}
