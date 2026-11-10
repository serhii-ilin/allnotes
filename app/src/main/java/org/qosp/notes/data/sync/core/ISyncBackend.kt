package org.qosp.notes.data.sync.core

import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.preferences.CloudService

/**
 * Sync backend contract. Kept as an interface (rather than inlining [org.qosp.notes.data.sync.fs.StorageBackend])
 * because tests delegate to a [StorageBackend] through it to simulate push/scan failures.
 */
interface ISyncBackend {
    val type: CloudService

    /** True when the backend's paths compare case-insensitively (vault on a case-insensitive volume). */
    val caseInsensitivePaths: Boolean get() = false

    /**
     * Paths that recovery could not settle. The latest scan skipped them, so callers must treat them as neither
     * present nor missing.
     */
    val quarantinedPaths: Set<String> get() = emptySet()

    /**
     * Check if the sync backend is available for operations.
     * Called before every sync to ensure the backend can be reached.
     * @return AvailabilityStatus indicating if backend is available or unavailable with reason
     */
    suspend fun isAvailable(): AvailabilityStatus

    /**
     * @param linkExisting when false, always writes a new file, even if an identical one already exists
     *   (restoring a note must not attach it to another live note's file).
     */
    suspend fun createNote(note: Note, linkExisting: Boolean = true): SyncNote
    suspend fun updateNote(note: Note, mapping: IdMapping): IdMapping
    suspend fun deleteNote(mapping: IdMapping, removeAssets: Boolean = true): Boolean
    suspend fun getNote(mapping: IdMapping): SyncNote?
    suspend fun getAll(knownMappedPaths: Set<String> = emptySet()): List<SyncNote>?
}
