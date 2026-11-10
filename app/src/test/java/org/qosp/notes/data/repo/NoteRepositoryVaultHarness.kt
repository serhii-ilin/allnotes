package org.qosp.notes.data.repo

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import org.qosp.notes.Config
import org.qosp.notes.data.AppDatabase
import org.qosp.notes.data.dao.IdMappingDao
import org.qosp.notes.data.dao.NoteDao
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.dao.ReminderDao
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.core.ISyncBackend
import org.qosp.notes.data.sync.core.ProcessRemoteActions
import org.qosp.notes.data.sync.core.RemoteOperation
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.data.sync.fs.vault.VaultFileOpFence
import kotlinx.coroutines.flow.first
import org.qosp.notes.preferences.SortMethod
import org.qosp.notes.data.repo.IdMappingRepository
import org.qosp.notes.data.sync.core.SynchronizeNotes
import org.qosp.notes.data.sync.fs.StorageBackend
import org.qosp.notes.data.sync.fs.StorageConfig
import org.qosp.notes.data.sync.fs.vault.InMemoryVaultFs
import org.qosp.notes.data.sync.fs.vault.VaultIo
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultSyncImport
import org.qosp.notes.data.sync.getMapping
import org.qosp.notes.data.sync.fs.vault.VaultFolderTestHarness
import org.qosp.notes.data.sync.fs.vault.VaultPortableMetadata
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.data.sync.fs.vault.VaultTreeAccess
import org.qosp.notes.di.SyncScope
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.ui.utils.Toaster
import kotlin.time.Duration.Companion.milliseconds

/**
 * Production [NoteRepositoryImpl] + real sync/debounce paths over [InMemoryVaultFs].
 */
class NoteRepositoryVaultHarness(
    private val testScope: TestScope,
    val vaultFs: InMemoryVaultFs = InMemoryVaultFs(caseInsensitive = true),
) {
    private val savedVaultDispatcher: CoroutineDispatcher = VaultIo.vaultDispatcher

    val context: Context = ApplicationProvider.getApplicationContext()
    val editorSessionRegistry = EditorSessionRegistry()
    private val roomDispatcher = UnconfinedTestDispatcher(testScope.testScheduler)
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .fallbackToDestructiveMigration()
        .setQueryExecutor(roomDispatcher.asExecutor())
        .setTransactionExecutor(roomDispatcher.asExecutor())
        .build()

    val noteDao: NoteDao = db.noteDao
    val idMappingDao: IdMappingDao = db.idMappingDao
    private val reminderDao: ReminderDao = db.reminderDao
    private val notebookDao = mockk<NotebookDao>(relaxed = true)
    private val prefs = mockk<PreferenceRepository>(relaxed = true)
    private val vaultSyncCoordinator = VaultSyncCoordinator(prefs)
    private val vaultTreeAccess = mockk<VaultTreeAccess>(relaxed = true)
    private val backendProvider = mockk<BackendProvider>(relaxed = true)

    val backend: StorageBackend = StorageBackend(
        context,
        StorageConfig(Uri.parse("content://test/vault")),
        notebookDao,
        vaultSyncCoordinator,
        vaultFs = vaultFs,
    )

    private val providerFlow = MutableStateFlow<ISyncBackend?>(backend)

    val repository: NoteRepositoryImpl
    private val processRemoteActions: ProcessRemoteActions
    private val prefsRef: PreferenceRepository

    init {
        prefsRef = prefs
        VaultIo.vaultDispatcher = UnconfinedTestDispatcher(testScope.testScheduler)
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        coEvery { notebookDao.getById(any()) } returns flowOf(null)
        coEvery { prefs.getEncryptedString(PreferenceRepository.STORAGE_LOCATION) } returns flowOf("content://vault")
        coEvery { prefs.getEncryptedString(PreferenceRepository.VAULT_CONFIGURED_AT) } returns flowOf("1")
        coEvery { prefs.getEncryptedString(any()) } returns flowOf("1")
        every { backendProvider.isSyncing } returns true
        every { backendProvider.syncProvider } returns providerFlow
        val scope = SyncScope(CoroutineScope(UnconfinedTestDispatcher(testScope.testScheduler)))
        processRemoteActions = ProcessRemoteActions(
            scope,
            backendProvider,
            idMappingDao,
            noteDao,
            mockk<Toaster>(relaxed = true),
            vaultSyncCoordinator,
            notebookDao,
            prefs,
        )
        repository = NoteRepositoryImpl(
            noteDao,
            idMappingDao,
            reminderDao,
            backendProvider,
            SynchronizeNotes(IdMappingRepository(idMappingDao), notebookDao),
            processRemoteActions,
            scope,
            mockk<Toaster>(relaxed = true),
            prefs,
            VaultPortableMetadata(noteDao, notebookDao, db.tagDao, db.noteTagDao),
            notebookDao,
            vaultTreeAccess,
            vaultSyncCoordinator,
            editorSessionRegistry,
        )
    }

    fun tree(): VaultFolderTestHarness = VaultFolderTestHarness(vaultFs)

    suspend fun flushRemoteActions() {
        testScope.advanceTimeBy(Config.RemoteUpdateDebounceTime + 100.milliseconds)
        testScope.advanceUntilIdle()
    }

    suspend fun insertAndSync(note: Note): Long {
        val id = repository.insertNote(note, sync = true)
        flushRemoteActions()
        val mapping = idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)
        assert(mapping != null && !mapping.storageUri.isNullOrBlank() && !mapping.isDeletedLocally) {
            "insertAndSync: expected vault mapping for note $id"
        }
        return id
    }

    suspend fun updateAndSync(note: Note) {
        repository.updateNotes(note, sync = true)
        flushRemoteActions()
    }

    suspend fun syncNotes() {
        repository.syncNotes()
        flushRemoteActions()
    }

    suspend fun getNote(id: Long): Note? = noteDao.getById(id).first()

    /**
     * Test seam: [hook] runs inside the create, after the vault file is written and before the repository records
     * the mapping. That is the window where a bin can arrive while a create is in flight.
     */
    fun onBackendCreated(hook: suspend (Note) -> Unit) {
        providerFlow.value = object : ISyncBackend by backend {
            override suspend fun createNote(note: Note, linkExisting: Boolean): SyncNote {
                val created = backend.createNote(note, linkExisting)
                hook(note)
                return created
            }
        }
    }

    fun conflictFiles(): List<String> = vaultFs.snapshotPaths().filter { "(conflict" in it }

    fun duplicateSuffixFiles(): List<String> =
        vaultFs.snapshotPaths().filter { Regex("""-\d+\.md$""").containsMatchIn(it) }

    fun contentUriFolders(): List<String> =
        vaultFs.snapshotPaths().filter { it.contains("content:/") || it.contains("com.android") }

    suspend fun insertLocal(note: Note): Long = noteDao.insert(note.toEntity())

    suspend fun upsertMapping(mapping: IdMapping) {
        val provider = mapping.provider ?: return
        val existing = idMappingDao.getByLocalIdAndProvider(mapping.localNoteId, provider)
        if (existing != null) {
            idMappingDao.update(mapping.copy(mappingId = existing.mappingId))
        } else {
            idMappingDao.insert(mapping)
        }
    }

    suspend fun mappingCountFor(noteId: Long): Int =
        if (idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE) != null) 1 else 0

    suspend fun localNoteCount(): Int = noteDao.getAll(SortMethod.TITLE_ASC).first().size

    private suspend fun vaultFence(): VaultFileOpFence = VaultFileOpFence(
        vaultSyncCoordinator.currentGeneration(),
        prefsRef.getEncryptedString(PreferenceRepository.STORAGE_LOCATION).first(),
    )

    suspend fun pushRemote(noteId: Long, op: RemoteOperation) {
        val fenced = when (op) {
            is RemoteOperation.Create -> op.copy(vaultFence = vaultFence())
            is RemoteOperation.Update -> op.copy(vaultFence = vaultFence())
            is RemoteOperation.Delete -> op.copy(vaultFence = vaultFence())
        }
        processRemoteActions(noteId, fenced)
        flushRemoteActions()
    }

    suspend fun binAndSync(vararg notes: Note) {
        repository.moveNotesToBin(*notes, sync = true)
        flushRemoteActions()
    }

    suspend fun restoreAndSync(vararg notes: Note) {
        repository.restoreNotes(*notes)
        flushRemoteActions()
    }

    suspend fun deleteAndSync(vararg notes: Note) {
        repository.deleteNotes(*notes, sync = true)
        flushRemoteActions()
    }

    fun close() {
        VaultIo.vaultDispatcher = savedVaultDispatcher
    }
}
