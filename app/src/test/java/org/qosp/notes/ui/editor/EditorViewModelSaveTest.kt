package org.qosp.notes.ui.editor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.NoteColor
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

        assertEquals(1, repository.updates.size)
        val saved = repository.updates.single()
        assertTrue(saved.sync)
        assertEquals("pending", saved.note.content)

        // The debounce timers were cancelled by the flush, so nothing else may be written.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, repository.updates.size)
    }

    private fun TestScope.createViewModel(): Pair<EditorViewModel, FakeNoteRepository> {
        val repository = FakeNoteRepository(Note(id = NOTE_ID, title = "title", content = "start"))
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
            syncScope = SyncScope(CoroutineScope(ioDispatcher + SupervisorJob())),
            ioDispatcher = ioDispatcher,
        )
        model.initialize(
            noteId = NOTE_ID,
            newNoteTitle = "",
            newNoteContent = "",
            newNoteAttachments = emptyList(),
            newNoteIsList = false,
            newNoteNotebookId = null,
        )
        advanceUntilIdle()
        return model to repository
    }

    private class FakeNoteRepository(initial: Note) : NoteRepository {

        data class Update(val note: Note, val sync: Boolean)

        private val note = MutableStateFlow<Note?>(initial)
        val updates = mutableListOf<Update>()

        override suspend fun updateNotes(vararg notes: Note, sync: Boolean) {
            notes.forEach { updated ->
                updates += Update(updated, sync)
                note.value = updated
            }
        }

        override fun getById(noteId: Long): Flow<Note?> = note

        override suspend fun insertNote(note: Note, sync: Boolean): Long = unsupported()
        override suspend fun moveNotesToBin(vararg notes: Note, sync: Boolean) { unsupported<Unit>() }
        override suspend fun restoreNotes(vararg notes: Note) { unsupported<Unit>() }
        override suspend fun deleteNotes(vararg notes: Note, sync: Boolean) { unsupported<Unit>() }
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
        override suspend fun getVaultRelativePath(noteId: Long): String? = null

        private fun <T> unsupported(): T = throw UnsupportedOperationException("not used in this test")
    }

    private companion object {
        const val NOTE_ID = 1L
    }
}
