package org.qosp.notes.ui.editor

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.msoul.datastore.defaultOf
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.NoteColor
import org.qosp.notes.data.model.NoteTask
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.data.repo.NotebookRepository
import org.qosp.notes.data.sync.fs.vault.VaultTreeAccess
import org.qosp.notes.di.SyncScope
import org.qosp.notes.preferences.DateFormat
import org.qosp.notes.preferences.DefaultEditorMode
import org.qosp.notes.preferences.MoveCheckedItems
import org.qosp.notes.preferences.OpenMediaIn
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.preferences.ShowDate
import org.qosp.notes.preferences.ShowFabChangeMode
import org.qosp.notes.preferences.TimeFormat
import java.time.Instant

class EditorViewModel(
    private val noteRepository: NoteRepository,
    private val notebookRepository: NotebookRepository,
    private val preferenceRepository: PreferenceRepository,
    private val vaultTreeAccess: VaultTreeAccess,
    private val syncScope: SyncScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    var inEditMode: Boolean = false
    var isNotInitialized = true
    var moveCheckedItems: Boolean = true
    private val noteIdFlow: MutableStateFlow<Long?> = MutableStateFlow(null)
    private val _vaultRelativePath = MutableStateFlow<String?>(null)
    val vaultRelativePath = _vaultRelativePath.asStateFlow()
    var selectedRange = 0 to 0

    val data = noteIdFlow
        .filterNotNull()
        .flatMapLatest { noteRepository.getById(it) }
        .filterNotNull()
        .flatMapLatest { note ->
            getNotebookData(note.notebookId).flatMapLatest { notebook ->
                preferenceRepository.getAll().map { prefs ->
                    Data(
                        note = note,
                        notebook = notebook,
                        dateTimeFormats = prefs.dateFormat to prefs.timeFormat,
                        openMediaInternally = prefs.openMediaIn == OpenMediaIn.INTERNAL,
                        showDates = prefs.showDate == ShowDate.YES,
                        editorFontSize = prefs.editorFontSize.fontSize,
                        showFabChangeMode = prefs.showFabChangeMode == ShowFabChangeMode.FAB,
                        defaultEditorMode = prefs.defaultEditorMode,
                        isInitialized = true,
                    )
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = Data(),
        )

    /**
     * All note mutations go through this single channel so that transforms run in call order and
     * always see the result of the previous one. It lives on [syncScope] so pending saves can
     * finish after the fragment (and eventually this ViewModel) is gone.
     */
    private val mutations = Channel<Mutation>(Channel.UNLIMITED)
    private val persistMutex = Mutex()

    // Only editor-owned fields are cached. The rest of the note is always re-read from Room so
    // changes made elsewhere (notebook, pin/archive, reminders, vault imports) are not reverted.
    @Volatile
    private var pendingContent: String? = null
    @Volatile
    private var pendingTitle: String? = null
    @Volatile
    private var pendingTaskList: List<NoteTask>? = null
    @Volatile
    private var writeGeneration = 0L
    private var roomWriteJob: Job? = null
    private var vaultWriteJob: Job? = null

    init {
        syncScope.launch(ioDispatcher) {
            for (mutation in mutations) {
                try {
                    handleMutation(mutation)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply mutation $mutation", e)
                    (mutation as? Mutation.Flush)?.ack?.complete(Unit)
                }
            }
        }
    }

    private fun getNotebookData(notebookId: Long?): Flow<Notebook?> {
        return notebookId?.let { id -> notebookRepository.getById(id) } ?: flow { emit(null) }
    }

    fun initialize(
        noteId: Long,
        newNoteTitle: String,
        newNoteContent: String,
        newNoteAttachments: List<Attachment>,
        newNoteIsList: Boolean,
        newNoteNotebookId: Long?,
    ) {
        viewModelScope.launch {
            val id = withContext(ioDispatcher) {
                if (noteId > 0L) return@withContext noteId

                noteRepository.insertNote(
                    Note(
                        title = newNoteTitle,
                        content = newNoteContent,
                        notebookId = newNoteNotebookId,
                        isList = newNoteIsList,
                        attachments = newNoteAttachments,
                        isLocalOnly = false
                    ),
                )
            }

            noteIdFlow.emit(id)
            if (id > 0L) {
                withContext(ioDispatcher) {
                    vaultTreeAccess.warmFrom(preferenceRepository)
                    _vaultRelativePath.value = noteRepository.getVaultRelativePath(id)
                }
            }

            isNotInitialized = false
            moveCheckedItems = preferenceRepository.get<MoveCheckedItems>().first() == MoveCheckedItems.YES
        }
    }

    fun setNoteTitle(title: String) = sendMutation(Mutation.Title(title))

    fun setNoteContent(content: String) = sendMutation(Mutation.Content(content))

    fun setColor(color: NoteColor) = update { note ->
        note.copy(
            color = color,
            modifiedDate = Instant.now().epochSecond,
        )
    }

    fun deleteAttachment(attachment: Attachment) = update { note ->
        note.copy(
            attachments = note.attachments
                .filterNot { it.path == attachment.path }
                .toMutableList(),
            modifiedDate = Instant.now().epochSecond,
        )
    }

    fun insertAttachments(vararg attachments: Attachment) = update { note ->
        note.copy(
            attachments = note.attachments + attachments,
            modifiedDate = Instant.now().epochSecond,
        )
    }

    fun updateTaskList(list: List<NoteTask>) = sendMutation(Mutation.TaskList(list))

    fun toList() = update { note ->
        note.copy(
            content = "",
            isList = true,
            taskList = note.stringToTaskList(),
            modifiedDate = Instant.now().epochSecond,
        )
    }

    fun toTextNote() = update { note ->
        note.copy(
            content = note.taskListToString(),
            isList = false,
            taskList = listOf(),
            modifiedDate = Instant.now().epochSecond,
        )
    }

    /** Persists pending content/title to Room and the vault right away. */
    suspend fun flush() {
        val ack = CompletableDeferred<Unit>()
        if (mutations.trySend(Mutation.Flush(ack)).isFailure) {
            ack.complete(Unit)
            return
        }
        ack.await()
    }

    /**
     * Fire-and-forget flush for lifecycle callbacks: [syncScope] outlives the fragment, so the
     * write completes even if the view is already destroyed.
     */
    fun flushAsync(pendingTitle: String? = null) {
        // Enqueue the title synchronously so it cannot be dropped if the channel is closed in
        // onCleared before the background coroutine gets a chance to run.
        pendingTitle?.let { mutations.trySend(Mutation.Title(it)) }
        syncScope.launch(ioDispatcher) { flush() }
    }

    override fun onCleared() {
        // Safety net: the pipeline lives in syncScope, so this still runs after viewModelScope died.
        // Closing the channel afterwards lets the consumer coroutine finish instead of leaking.
        syncScope.launch(ioDispatcher) {
            flush()
            mutations.close()
        }
        super.onCleared()
    }

    private fun update(transform: suspend (Note) -> Note) = sendMutation(Mutation.Transform(transform))

    private fun sendMutation(mutation: Mutation) {
        mutations.trySend(mutation)
    }

    private suspend fun handleMutation(mutation: Mutation) {
        when (mutation) {
            is Mutation.Content -> {
                pendingContent = mutation.content
                scheduleDebouncedWrites()
            }

            is Mutation.Title -> {
                pendingTitle = mutation.title
                scheduleDebouncedWrites()
            }

            is Mutation.TaskList -> {
                pendingTaskList = mutation.taskList
                scheduleDebouncedWrites()
            }

            is Mutation.Transform -> {
                cancelDebouncedWrites()
                // Discrete actions persist immediately, but first fold in whatever the user has
                // typed so the transform (e.g. toList) sees the pending content/title/tasks.
                val note = latestNote() ?: return
                val transformed = mutation.transform(applyPendingFields(note))
                persistNote(transformed, sync = true)
                clearPendingFields()
            }

            is Mutation.Flush -> {
                cancelDebouncedWrites()
                flushPendingWrites()
                mutation.ack?.complete(Unit)
            }
        }
    }

    private suspend fun latestNote(): Note? {
        val noteId = noteIdFlow.filterNotNull().first()
        return noteRepository.getById(noteId).first()
    }

    private fun applyPendingFields(note: Note): Note {
        val content = pendingContent
        val title = pendingTitle
        val taskList = pendingTaskList
        if (content == null && title == null && taskList == null) return note
        return note.copy(
            content = content ?: note.content,
            title = title ?: note.title,
            taskList = taskList ?: note.taskList,
            modifiedDate = Instant.now().epochSecond,
        )
    }

    private fun scheduleDebouncedWrites() {
        val generation = ++writeGeneration

        // Room is cheap, so the index catches up quickly; the vault write is debounced much
        // longer because every push costs several SAF IPC calls.
        roomWriteJob?.cancel()
        roomWriteJob = syncScope.launch(ioDispatcher) {
            delay(ROOM_DEBOUNCE_MS)
            persistPending(sync = false, generation = generation)
        }

        vaultWriteJob?.cancel()
        vaultWriteJob = syncScope.launch(ioDispatcher) {
            delay(VAULT_DEBOUNCE_MS)
            persistPending(sync = true, generation = generation)
        }
    }

    private fun cancelDebouncedWrites() {
        writeGeneration++
        roomWriteJob?.cancel()
        roomWriteJob = null
        vaultWriteJob?.cancel()
        vaultWriteJob = null
    }

    private suspend fun flushPendingWrites() {
        if (hasPendingFields()) persistPending(sync = true)
    }

    private fun hasPendingFields(): Boolean =
        pendingContent != null || pendingTitle != null || pendingTaskList != null

    private fun clearPendingFields() {
        pendingContent = null
        pendingTitle = null
        pendingTaskList = null
    }

    private suspend fun persistPending(sync: Boolean, generation: Long? = null) {
        persistMutex.withLock {
            // A newer mutation superseded the debounced writer while it was waiting.
            if (generation != null && generation != writeGeneration) return
            val note = latestNote() ?: return
            noteRepository.updateNotes(applyPendingFields(note), sync = sync)
            // Only the vault write completes the save; the Room write must leave the pending
            // fields in place so the later vault push still carries them.
            if (sync && (generation == null || generation == writeGeneration)) clearPendingFields()
        }
    }

    private suspend fun persistNote(note: Note, sync: Boolean) {
        persistMutex.withLock { noteRepository.updateNotes(note, sync = sync) }
    }

    private sealed interface Mutation {
        data class Content(val content: String) : Mutation
        data class Title(val title: String) : Mutation
        data class TaskList(val taskList: List<NoteTask>) : Mutation
        class Transform(val transform: suspend (Note) -> Note) : Mutation
        class Flush(val ack: CompletableDeferred<Unit>?) : Mutation
    }

    data class Data(
        val note: Note? = null,
        val notebook: Notebook? = null,
        val dateTimeFormats: Pair<DateFormat, TimeFormat> = defaultOf<DateFormat>() to defaultOf<TimeFormat>(),
        val openMediaInternally: Boolean = true,
        val showDates: Boolean = true,
        val editorFontSize: Int = -1, // -1: not customised, default font size
        val showFabChangeMode: Boolean = true,
        val defaultEditorMode: DefaultEditorMode = defaultOf(),
        val isInitialized: Boolean = false,
        val moveCheckedItems: Boolean = true,
    )
}

private const val TAG = "EditorViewModel"
private const val ROOM_DEBOUNCE_MS = 300L
private const val VAULT_DEBOUNCE_MS = 1_500L
