package org.qosp.notes.ui.editor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.NoteColor
import org.qosp.notes.data.model.NoteTask
import org.qosp.notes.data.repo.EditorSessionRegistry
import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.data.repo.NotebookRepository
import org.qosp.notes.data.sync.core.BaseResult
import org.qosp.notes.data.sync.fs.vault.VaultTreeAccess
import org.qosp.notes.di.SyncScope
import org.qosp.notes.preferences.AppPreferences
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.preferences.SortMethod

/**
 * Save-pipeline behaviour of [EditorViewModel]: content edits are debounced, all mutations are
 * applied in call order, and an explicit flush bypasses the timers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelSaveTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun typingBurst_persistsOnlyLastContentOnceAfterDebounce() = runTest(mainDispatcher) {
        val (model, repository) = createViewModel()

        model.setNoteContent("a")
        runCurrent()
        advanceTimeBy(100)
        model.setNoteContent("ab")
        runCurrent()
        advanceTimeBy(100)
        model.setNoteContent("abc")
        runCurrent()

        advanceTimeBy(299)
        runCurrent()
        assertTrue("nothing may be written while the user is still typing", repository.updates.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        val roomUpdates = repository.updates.filter { !it.sync }
        assertEquals(1, roomUpdates.size)
        assertEquals("abc", roomUpdates.single().note.content)
    }

    @Test
    fun typingBurst_persistsOnlyLastTitleOnceAfterDebounce() = runTest(mainDispatcher) {
        val (model, repository) = createViewModel()

        model.setNoteTitle("a")
        runCurrent()
        advanceTimeBy(100)
        model.setNoteTitle("ab")
        runCurrent()
        advanceTimeBy(100)
        model.setNoteTitle("My meeting")
        runCurrent()

        advanceTimeBy(299)
        runCurrent()
        assertTrue("nothing may be written while the user is still typing", repository.updates.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        val roomUpdates = repository.updates.filter { !it.sync }
        assertEquals(1, roomUpdates.size)
        assertEquals("My meeting", roomUpdates.single().note.title)
    }

    @Test
    fun vaultPush_happensOnceAfterLongerIdleWindow() = runTest(mainDispatcher) {
        val (model, repository) = createViewModel()

        model.setNoteContent("typed")
        runCurrent()

        advanceTimeBy(1_499)
        runCurrent()
        assertTrue(repository.updates.none { it.sync })

        advanceTimeBy(1)
        runCurrent()
        val vaultUpdates = repository.updates.filter { it.sync }
        assertEquals(1, vaultUpdates.size)
        assertEquals("typed", vaultUpdates.single().note.content)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, repository.updates.count { it.sync })
    }

    @Test
    fun mutations_applyInCallOrder() = runTest(mainDispatcher) {
        val (model, repository) = createViewModel()

        model.setNoteContent("a")
        runCurrent()
        model.setColor(NoteColor.Blue)
        runCurrent()
        model.setNoteContent("ab")
        runCurrent()

        advanceUntilIdle()

        val saved = repository.updates.last().note
        assertEquals("ab", saved.content)
        assertEquals(NoteColor.Blue, saved.color)
    }

    @Test
    fun flush_persistsPendingContentImmediately() = runTest(mainDispatcher) {
        val (model, repository) = createViewModel()

        model.setNoteContent("pending")
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        model.flush()

        // Flush writes Room immediately, then pushes the vault, so both calls are recorded.
        assertEquals(2, repository.updates.size)
        val roomWrite = repository.updates[0]
        assertFalse(roomWrite.sync)
        assertEquals("pending", roomWrite.note.content)
        val vaultWrite = repository.updates[1]
        assertTrue(vaultWrite.sync)
        assertEquals("pending", vaultWrite.note.content)

        // The debounce timers were cancelled by the flush, so nothing else may be written.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, repository.updates.size)
    }

    @Test
    fun externalChangeToNotebook_isNotRevertedByLaterContentSave() = runTest(mainDispatcher) {
        val (model, repository) = createViewModel()
        val movedNotebookId = 42L

        model.setNoteContent("a")
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        // Simulates a move to another notebook (or any other ViewModel updating the note).
        repository.externalUpdate { it.copy(notebookId = movedNotebookId) }

        model.setNoteContent("ab")
        runCurrent()
        advanceUntilIdle()

        val saved = repository.updates.last().note
        assertEquals(movedNotebookId, saved.notebookId)
        assertEquals("ab", saved.content)
    }

    @Test
    fun taskListBurst_producesSingleVaultPush() = runTest(mainDispatcher) {
        val (model, repository) = createViewModel()

        model.updateTaskList(listOf(NoteTask(0L, "a", false)))
        runCurrent()
        advanceTimeBy(100)
        model.updateTaskList(listOf(NoteTask(0L, "ab", false)))
        runCurrent()
        advanceTimeBy(100)
        model.updateTaskList(listOf(NoteTask(0L, "abc", false)))
        runCurrent()

        advanceTimeBy(1_499)
        runCurrent()
        assertTrue(repository.updates.none { it.sync })

        advanceTimeBy(1)
        runCurrent()
        val vaultUpdates = repository.updates.filter { it.sync }
        assertEquals(1, vaultUpdates.size)
        assertEquals("abc", vaultUpdates.single().note.taskList.single().content)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, repository.updates.count { it.sync })
    }

    @Test
    fun flush_duringSlowVaultWrite_stillWritesRoomPromptly() = runTest(mainDispatcher) {
        val (model, repository) = createViewModel()
        val gate = CompletableDeferred<Unit>()
        repository.vaultGate = gate

        model.setNoteContent("typed")
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue("the vault push should be in flight", repository.vaultStarted)

        val roomWritesBeforeFlush = repository.updates.count { !it.sync }
        val flushJob = launch { model.flush() }
        runCurrent()

        // The Room write happened even though the slow vault push still holds its lock.
        assertEquals(roomWritesBeforeFlush + 1, repository.updates.count { !it.sync })
        assertFalse(flushJob.isCompleted)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(flushJob.isCompleted)
    }

    @Test
    fun initialize_waitsForPreviousSessionFlush() = runTest(mainDispatcher) {
        val repository = FakeNoteRepository(Note(id = NOTE_ID, title = "title", content = "start"))
        val registry = EditorSessionRegistry()
        registry.markPending(NOTE_ID)
        val (model, _) = createViewModel(repository, registry, initialize = false)

        backgroundScope.launch { model.data.collect {} }
        model.initialize(
            noteId = NOTE_ID,
            newNoteTitle = "",
            newNoteContent = "",
            newNoteAttachments = emptyList(),
            newNoteIsList = false,
            newNoteNotebookId = null,
        )
        advanceTimeBy(1_000)

        assertEquals("must not read Room while the old session is still flushing", 0, repository.reads)

        registry.markIdle(NOTE_ID)
        advanceUntilIdle()
        assertTrue(repository.reads > 0)
    }

    @Test
    fun initialize_stopsWaitingForAStuckPreviousSession() = runTest(mainDispatcher) {
        val repository = FakeNoteRepository(Note(id = NOTE_ID, title = "title", content = "start"))
        val registry = EditorSessionRegistry()
        registry.markPending(NOTE_ID) // never cleared, e.g. the previous session's write failed
        val (model, _) = createViewModel(repository, registry, initialize = false)

        backgroundScope.launch { model.data.collect {} }
        model.initialize(
            noteId = NOTE_ID,
            newNoteTitle = "",
            newNoteContent = "",
            newNoteAttachments = emptyList(),
            newNoteIsList = false,
            newNoteNotebookId = null,
        )
        advanceTimeBy(6_000)

        assertTrue("the note must still open after the wait times out", repository.reads > 0)
    }

    private fun TestScope.createViewModel(
        repository: FakeNoteRepository = FakeNoteRepository(Note(id = NOTE_ID, title = "title", content = "start")),
        registry: EditorSessionRegistry = EditorSessionRegistry(),
        initialize: Boolean = true,
    ): Pair<EditorViewModel, FakeNoteRepository> {
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val preferences = mockk<PreferenceRepository>(relaxed = true) {
            every { getAll() } returns flowOf(AppPreferences())
            every { dataStore } returns mockk<DataStore<Preferences>>(relaxed = true) {
                every { data } returns flowOf(emptyPreferences())
            }
        }
        val model = EditorViewModel(
            noteRepository = repository,
            notebookRepository = mockk<NotebookRepository>(relaxed = true),
            preferenceRepository = preferences,
            vaultTreeAccess = mockk<VaultTreeAccess>(relaxed = true),
            editorSessionRegistry = registry,
            syncScope = SyncScope(CoroutineScope(ioDispatcher + SupervisorJob())),
            ioDispatcher = ioDispatcher,
        )
        if (initialize) {
            model.initialize(
                noteId = NOTE_ID,
                newNoteTitle = "",
                newNoteContent = "",
                newNoteAttachments = emptyList(),
                newNoteIsList = false,
                newNoteNotebookId = null,
            )
            advanceUntilIdle()
        }
        return model to repository
    }

    private class FakeNoteRepository(initial: Note) : NoteRepository {

        data class Update(val note: Note, val sync: Boolean)

        private val note = MutableStateFlow<Note?>(initial)
        val updates = mutableListOf<Update>()
        var reads = 0
            private set
        var vaultGate: CompletableDeferred<Unit>? = null
        var vaultStarted = false
            private set

        override suspend fun updateNotes(vararg notes: Note, sync: Boolean) {
            notes.forEach { updated ->
                updates += Update(updated, sync)
                note.value = updated
            }
            if (sync) {
                vaultStarted = true
                vaultGate?.await()
            }
        }

        override fun getById(noteId: Long): Flow<Note?> {
            reads++
            return note
        }

        fun externalUpdate(transform: (Note) -> Note) {
            note.value = note.value?.let(transform)
        }

        override suspend fun insertNote(note: Note, sync: Boolean): Long = unsupported()
        override suspend fun moveNotesToBin(vararg notes: Note, sync: Boolean) { unsupported<Unit>() }
        override suspend fun restoreNotes(vararg notes: Note) { unsupported<Unit>() }
        override suspend fun deleteNotes(vararg notes: Note, sync: Boolean, recordPurge: Boolean) { unsupported<Unit>() }
        override suspend fun discardEmptyNotes(): Boolean = unsupported()
        override suspend fun permanentlyDeleteNotesInBin() { unsupported<Unit>() }
        override suspend fun syncNotes(): BaseResult = unsupported()
        override fun getDeleted(sortMethod: SortMethod): Flow<List<Note>> = unsupported()
        override fun getArchived(sortMethod: SortMethod): Flow<List<Note>> = unsupported()
        override fun getNonDeleted(sortMethod: SortMethod): Flow<List<Note>> = unsupported()
        override fun getNonDeletedOrArchived(sortMethod: SortMethod): Flow<List<Note>> = unsupported()
        override fun getAll(sortMethod: SortMethod): Flow<List<Note>> = unsupported()
        override fun getByNotebook(notebookId: Long, sortMethod: SortMethod): Flow<List<Note>> = unsupported()
        override fun getNonRemoteNotes(provider: CloudService, sortMethod: SortMethod): Flow<List<Note>> = unsupported()
        override fun getNotesWithoutNotebook(sortMethod: SortMethod): Flow<List<Note>> = unsupported()
        override suspend fun getNotesByCloudService(provider: CloudService): Map<IdMapping, Note?> = unsupported()
        override suspend fun deleteIdMappingsForCloudService(cloudService: CloudService) { unsupported<Unit>() }
        override suspend fun deleteActiveIdMappingsForCloudService(cloudService: CloudService) { unsupported<Unit>() }
        override suspend fun countIdMappings(cloudService: CloudService): Int = unsupported()
        override suspend fun getVaultRelativePath(noteId: Long): String? = null

        private fun <T> unsupported(): T = throw UnsupportedOperationException("not used in this test")
    }

    private companion object {
        const val NOTE_ID = 1L
    }
}
