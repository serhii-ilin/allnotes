package org.qosp.notes.data.sync.fs.vault

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.qosp.notes.Config
import org.qosp.notes.data.AppDatabase
import org.qosp.notes.data.dao.IdMappingDao
import org.qosp.notes.data.dao.NoteDao
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.NoteEntity
import org.qosp.notes.data.repo.IdMappingRepository
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.core.NoteAction
import org.qosp.notes.data.sync.core.ProcessRemoteActions
import org.qosp.notes.data.sync.core.RemoteOperation
import org.qosp.notes.data.sync.core.SyncNotesResult
import org.qosp.notes.data.sync.core.SynchronizeNotes
import org.qosp.notes.data.sync.getMapping
import org.qosp.notes.data.sync.fs.StorageBackend
import org.qosp.notes.data.sync.fs.StorageConfig
import org.qosp.notes.data.sync.getMapping
import org.qosp.notes.di.SyncScope
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.preferences.SortMethod
import kotlinx.coroutines.flow.first
import org.qosp.notes.ui.utils.Toaster
import kotlin.time.Duration.Companion.milliseconds

/**
 * In-memory Room + fake vault + real [SynchronizeNotes] and [ProcessRemoteActions].
 */
class VaultFolderSyncHarness(
    private val testScope: TestScope,
    val vaultFs: InMemoryVaultFs = InMemoryVaultFs(),
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .fallbackToDestructiveMigration()
        .allowMainThreadQueries()
        .build()

    val noteDao: NoteDao = db.noteDao
    val idMappingDao: IdMappingDao = db.idMappingDao
    private val notebookDao = mockk<NotebookDao>(relaxed = true)
    private val vaultSyncCoordinator = mockk<VaultSyncCoordinator>(relaxed = true)
    private val prefs = mockk<PreferenceRepository>(relaxed = true)

    val backend: StorageBackend = StorageBackend(
        context,
        StorageConfig(Uri.parse("content://test/vault")),
        notebookDao,
        vaultSyncCoordinator,
        vaultFs = vaultFs,
    )

    private val idMappingRepository = IdMappingRepository(idMappingDao)
    val synchronizeNotes = SynchronizeNotes(idMappingRepository, notebookDao)
    private val backendProvider = mockk<BackendProvider>(relaxed = true)
    val processRemoteActions: ProcessRemoteActions

    init {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        coEvery { notebookDao.getById(any()) } returns flowOf(null)
        coEvery { vaultSyncCoordinator.currentGeneration() } returns 1L
        coEvery { vaultSyncCoordinator.isStaleMapping(any()) } returns false
        coEvery { vaultSyncCoordinator.withVaultLock(any<suspend () -> Any>()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
        }
        coEvery { prefs.getEncryptedString(PreferenceRepository.STORAGE_LOCATION) } returns flowOf("content://vault")
        coEvery { prefs.getEncryptedString(any()) } returns flowOf("1")
        every { backendProvider.isSyncing } returns true
        every { backendProvider.syncProvider } returns MutableStateFlow(backend)

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
    }

    fun tree(): VaultFolderTestHarness = VaultFolderTestHarness(vaultFs)

    suspend fun insertNote(note: Note): Long = noteDao.insert(note.toEntity())

    suspend fun getNote(id: Long): Note? = noteDao.getById(id).first()

    /** Direct vault write (same code paths as [ProcessRemoteActions], without debounce). */
    suspend fun vaultPushCreate(note: Note): IdMapping {
        val created = backend.createNote(note)
        val mapping = created.getMapping(note.id, CloudService.FILE_STORAGE).copy(
            storageUri = created.idStr,
            extras = VaultMappingState.encode(
                VaultMappingState.fromDiskSync(
                    created.content.orEmpty(),
                    note,
                    created.idStr,
                    VaultSyncImport.vaultPathsFromNote(note),
                    1L,
                    null,
                ),
            ),
        )
        upsertMapping(mapping)
        noteDao.update(note.copy(isLocalOnly = false).toEntity())
        return mapping
    }

    suspend fun vaultPushUpdate(note: Note): IdMapping {
        val mapping = idMappingDao.getByLocalIdAndProvider(note.id, CloudService.FILE_STORAGE)
            ?: error("no mapping for note ${note.id}")
        val updated = backend.updateNote(note, mapping)
        idMappingDao.update(updated)
        val live = noteDao.getById(note.id).first() ?: note
        val state = VaultMappingState.resolve(updated.extras)
        if (state != null) {
            noteDao.update(live.copy(attachments = note.attachments).toEntity())
        }
        return updated
    }

    suspend fun vaultPushDelete(note: Note, removeAssets: Boolean = true) {
        val mapping = idMappingDao.getByLocalIdAndProvider(note.id, CloudService.FILE_STORAGE) ?: return
        backend.deleteNote(mapping, removeAssets)
        if (!removeAssets) {
            val path = VaultPaths.vaultMappingPath(mapping.storageUri)
            val state = VaultMappingState.resolve(mapping.extras)
                ?: VaultMappingState(baselineHash = "")
            idMappingDao.update(
                mapping.copy(
                    storageUri = null,
                    isDeletedLocally = true,
                    extras = VaultMappingState.encode(state.copy(binnedVaultPath = path)),
                ),
            )
        } else {
            idMappingDao.deleteByLocalId(note.id)
        }
    }

    suspend fun upsertMapping(mapping: IdMapping) {
        val provider = mapping.provider ?: return
        val existing = idMappingDao.getByLocalIdAndProvider(mapping.localNoteId, provider)
        if (existing != null) {
            idMappingDao.update(mapping.copy(mappingId = existing.mappingId))
        } else {
            idMappingDao.insert(mapping)
        }
    }

    suspend fun mappedPaths(): Set<String> =
        idMappingDao.getAllByCloudService(CloudService.FILE_STORAGE)
            .filterNot { it.isDeletedLocally }
            .mapNotNull { VaultPaths.vaultMappingPath(it.storageUri) }
            .toSet()

    suspend fun mappingCountFor(noteId: Long): Int =
        if (idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE) != null) 1 else 0

    suspend fun relinkLegacyContentMappings() {
        val legacy = idMappingDao.getAllByCloudService(CloudService.FILE_STORAGE)
            .filter { it.storageUri?.contains("://") == true }
        if (legacy.isEmpty()) return
        val locals = noteDao.getAll(SortMethod.TITLE_ASC).first()
        val remotes = vaultRemotesAfterScan(mappedPaths())
        for (row in legacy) {
            val local = locals.find { it.id == row.localNoteId } ?: continue
            val remote = remotes.firstOrNull { sync ->
                val desired = VaultPaths.normalizeNotePath(sync.idStr)
                val doc = VaultNoteDocument.parse(sync.content.orEmpty())
                val title = doc.frontMatter.title?.takeIf { it.isNotBlank() }
                    ?: VaultPaths.noteBaseName(sync.idStr)
                val titleMatch = title.equals(local.title, ignoreCase = true)
                val bodyMatch = VaultContentHash.ofText(local.content) == VaultContentHash.ofText(doc.body)
                val hashMatch = VaultSyncImport.canonicalFileHash(local, desired, null, null) ==
                    VaultSyncImport.logicalHashFromRemoteFile(sync, local, desired, null)
                titleMatch && (bodyMatch || hashMatch)
            } ?: continue
            val desired = VaultPaths.normalizeNotePath(remote.idStr)
            val imported = VaultSyncImport.noteFromRemote(remote, local)
            val state = VaultMappingState.fromDiskSync(
                remote.content.orEmpty(),
                imported,
                desired,
                VaultSyncImport.vaultPathsFromNote(imported),
                1L,
                null,
            )
            idMappingDao.update(
                row.copy(
                    storageUri = desired,
                    isDeletedLocally = false,
                    extras = VaultMappingState.encode(state),
                ),
            )
        }
    }

    fun refreshVaultIndex() {
        backend.invalidateVaultListingCache()
    }

    private suspend fun vaultRemotesAfterScan(paths: Set<String>): List<org.qosp.notes.data.sync.core.SyncNote> {
        var remotes = backend.getAll(paths) ?: backend.getAll(emptySet())
        if (remotes.isNullOrEmpty() && vaultFs.snapshotPaths().any { it.endsWith(".md", ignoreCase = true) }) {
            refreshVaultIndex()
            remotes = backend.getAll(paths) ?: backend.getAll(emptySet())
        }
        return remotes.orEmpty()
    }

    suspend fun runSync(vaultCutoff: Long = 1L): SyncNotesResult {
        val locals = noteDao.getAll(SortMethod.TITLE_ASC).first()
        val paths = mappedPaths()
        relinkLegacyContentMappings()
        val remotes = vaultRemotesAfterScan(paths)
        return synchronizeNotes(
            locals,
            remotes,
            CloudService.FILE_STORAGE,
            remoteScanSucceeded = true,
            vaultExportCutoffEpoch = vaultCutoff,
        )
    }

    suspend fun applySync(result: SyncNotesResult) {
        result.newMappings.forEach { upsertMapping(it) }
        for (action in result.localUpdates) {
            when (action) {
                is NoteAction.Create -> {
                    val imported = VaultSyncImport.noteFromRemote(action.remoteNote)
                    val id = insertNote(imported.copy(id = 0))
                    upsertMapping(action.remoteNote.getMapping(id, CloudService.FILE_STORAGE))
                }
                is NoteAction.Update -> {
                    val cur = getNote(action.note.id) ?: continue
                    if (cur.isDeleted) {
                        noteDao.update(cur.toEntity().copy(isDeleted = false, deletionDate = null))
                    }
                    val merged = VaultSyncImport.noteFromRemote(action.remoteNote, cur)
                    noteDao.update(merged.toEntity())
                    val m = idMappingDao.getByLocalIdAndProvider(action.note.id, CloudService.FILE_STORAGE)
                    if (m != null) {
                        val state = VaultMappingState.fromDiskSync(
                            action.remoteNote.content.orEmpty(),
                            merged,
                            VaultPaths.vaultMappingPath(m.storageUri) ?: action.remoteNote.idStr,
                            VaultSyncImport.vaultPathsFromNote(merged),
                            1L,
                            null,
                        )
                        idMappingDao.update(m.copy(extras = VaultMappingState.encode(state)))
                    }
                }
                is NoteAction.Delete -> {
                    val entity = (getNote(action.note.id) ?: action.note).toEntity()
                        .copy(isDeleted = true, deletionDate = System.currentTimeMillis() / 1000)
                    noteDao.update(entity)
                    result.newMappings.filter { it.localNoteId == action.note.id }.forEach { upsertMapping(it) }
                }
            }
        }
        for (action in result.remoteUpdates) {
            when (action) {
                is NoteAction.Create -> push(action.note.id, RemoteOperation.Create(action.note))
                is NoteAction.Update -> push(action.note.id, RemoteOperation.Update(action.note))
                is NoteAction.Delete -> push(action.note.id, RemoteOperation.Delete(action.note))
            }
        }
    }

    suspend fun syncRound(vaultCutoff: Long = 1L): SyncNotesResult {
        val r = runSync(vaultCutoff)
        applySync(r)
        return r
    }

    fun vaultFence(): VaultFileOpFence = VaultFileOpFence(1L, "content://vault")

    suspend fun push(noteId: Long, op: RemoteOperation) {
        val fenced = when (op) {
            is RemoteOperation.Create -> op.copy(vaultFence = vaultFence())
            is RemoteOperation.Update -> op.copy(vaultFence = vaultFence())
            is RemoteOperation.Delete -> op.copy(vaultFence = vaultFence())
        }
        processRemoteActions(noteId, fenced)
        testScope.advanceTimeBy(Config.RemoteUpdateDebounceTime + 50.milliseconds)
    }

    fun conflictFiles(): List<String> = vaultFs.snapshotPaths().filter { "(conflict" in it }

    fun duplicateSuffixFiles(): List<String> =
        vaultFs.snapshotPaths().filter { Regex("""-\d+\.md$""").containsMatchIn(it) }

    fun contentUriFolders(): List<String> =
        vaultFs.snapshotPaths().filter { it.contains("content:/") || it.contains("com.android") }
}
