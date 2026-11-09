package org.qosp.notes.data.sync.core

import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.qosp.notes.Config
import org.qosp.notes.data.dao.IdMappingDao
import org.qosp.notes.data.dao.NoteDao
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.fs.vault.VaultFileOpFence
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.di.SyncScope
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.ui.utils.Toaster
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

class ProcessRemoteActionsVaultFenceTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK
    private lateinit var backendProvider: BackendProvider

    @MockK
    private lateinit var idMappingDao: IdMappingDao

    @MockK
    private lateinit var noteDao: NoteDao

    @MockK
    private lateinit var toaster: Toaster

    @MockK
    private lateinit var notebookDao: NotebookDao

    @MockK
    private lateinit var preferenceRepository: PreferenceRepository

    @MockK
    private lateinit var vaultSyncCoordinator: VaultSyncCoordinator

    private lateinit var scope: SyncScope
    private lateinit var processRemoteActions: ProcessRemoteActions

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        scope = SyncScope(CoroutineScope(UnconfinedTestDispatcher()))
        processRemoteActions = ProcessRemoteActions(
            scope,
            backendProvider,
            idMappingDao,
            noteDao,
            toaster,
            vaultSyncCoordinator,
            notebookDao,
            preferenceRepository,
        )
        every { backendProvider.isSyncing } returns true
        coEvery { vaultSyncCoordinator.withVaultLock(any<suspend () -> Unit>()) } coAnswers {
            firstArg<suspend () -> Unit>()()
        }
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun staleFence_abortsBeforeBackendWrite() = runTest {
        val oldBackend = mockk<ISyncBackend>(relaxed = true) {
            every { type } returns CloudService.FILE_STORAGE
        }
        val newBackend = mockk<ISyncBackend>(relaxed = true) {
            every { type } returns CloudService.FILE_STORAGE
        }
        val providers = MutableStateFlow<ISyncBackend?>(oldBackend)
        every { backendProvider.syncProvider } returns providers
        val note = Note(id = 1L, title = "t", content = "push me")
        val fence = VaultFileOpFence(vaultGeneration = 1L, vaultTreeUri = "content://old-tree")
        coEvery { preferenceRepository.getEncryptedString(PreferenceRepository.STORAGE_LOCATION) } returns
            flowOf("content://new-tree")
        coEvery { vaultSyncCoordinator.currentGeneration() } returns 2L

        providers.value = newBackend
        processRemoteActions(1L, RemoteOperation.Update(note, vaultFence = fence))
        advanceTimeBy(Config.RemoteUpdateDebounceTime + 500.milliseconds)

        coVerify(exactly = 0) { newBackend.updateNote(any(), any()) }
        coVerify(exactly = 0) { oldBackend.updateNote(any(), any()) }
    }
}
