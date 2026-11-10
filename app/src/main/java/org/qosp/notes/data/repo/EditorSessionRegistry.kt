package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tracks notes whose editor still has unflushed content.
 *
 * The note list discards blank notes whenever it resumes, which can happen before an editor's
 * asynchronous flush reaches Room. Keeping those ids here lets [NoteRepositoryImpl] leave them
 * alone until the write completes. The map holds one entry per note and is exposed as a flow so
 * [awaitIdle] can wait for the last writer without polling.
 */
class EditorSessionRegistry {

    private val pendingNoteIds = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val pending: StateFlow<Map<Long, Int>> = pendingNoteIds.asStateFlow()

    /** Marks [noteId] as having state that is not in Room yet. Safe to call repeatedly. */
    fun markPending(noteId: Long) {
        pendingNoteIds.update { current ->
            if (current.containsKey(noteId)) current else current + (noteId to 1)
        }
    }

    /** Clears the marker once every pending write for [noteId] has been persisted. */
    fun markIdle(noteId: Long) {
        pendingNoteIds.update { it - noteId }
    }

    fun isPending(noteId: Long): Boolean = pendingNoteIds.value.containsKey(noteId)

    /**
     * Suspends until [noteId] has no editor write left to land. Bounded: if a previous session's
     * write failed and never cleared its marker, opening the note must still work.
     */
    suspend fun awaitIdle(noteId: Long, timeoutMs: Long = AWAIT_IDLE_TIMEOUT_MS) {
        withTimeoutOrNull(timeoutMs) { pendingNoteIds.first { !it.containsKey(noteId) } }
    }

    private companion object {
        const val AWAIT_IDLE_TIMEOUT_MS = 5_000L
    }
}
