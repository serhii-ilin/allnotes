package org.qosp.notes.data.repo

import android.util.Log
import kotlinx.coroutines.flow.first
import org.qosp.notes.data.sync.fs.vault.VaultPaths
import org.qosp.notes.preferences.PreferenceRepository

/** One notebook whose name had to change so that it can be its folder name. */
data class NotebookNameRename(val from: String, val to: String)

/**
 * FMT-11 AC7, one-time upgrade: a notebook saved before names were validated may not be a usable folder name.
 * It is renamed to [VaultPaths.sanitizeFileName], made unique by [VaultPaths.collisionKey] against the other
 * notebooks, through [NotebookRepository.update] so the notes' files move and the history records the rename.
 *
 * The rename is safe to repeat: a second pass finds only valid names. [run] marks the pass done in preferences
 * so every app start does not scan the notebooks table again.
 */
class NotebookNameMigration(
    private val notebookRepository: NotebookRepository,
    private val preferenceRepository: PreferenceRepository,
) {

    suspend fun run(): List<NotebookNameRename> {
        val done = preferenceRepository
            .getEncryptedString(PreferenceRepository.NOTEBOOK_NAMES_MIGRATED)
            .first()
            .trim() == DONE
        if (done) return emptyList()
        val renames = migrate()
        preferenceRepository.putEncryptedStrings(PreferenceRepository.NOTEBOOK_NAMES_MIGRATED to DONE)
        return renames
    }

    /** Renames every invalid notebook now; returns what changed, so a test can assert and a second call can be empty. */
    suspend fun migrate(): List<NotebookNameRename> {
        val notebooks = notebookRepository.getAll().first().sortedBy { it.id }
        val usedKeys = notebooks
            .filter { VaultPaths.validateSegmentName(it.name) == null }
            .mapTo(mutableSetOf()) { VaultPaths.collisionKey(it.name) }
        val renames = mutableListOf<NotebookNameRename>()
        for (notebook in notebooks) {
            if (VaultPaths.validateSegmentName(notebook.name) == null) continue
            val name = VaultPaths.uniqueSegmentName(notebook.name, usedKeys)
            usedKeys += VaultPaths.collisionKey(name)
            notebookRepository.update(notebook.copy(name = name))
            Log.i(TAG, "Renamed notebook <${notebook.name}> to <$name>")
            renames += NotebookNameRename(notebook.name, name)
        }
        return renames
    }

    private companion object {
        const val TAG = "NotebookNameMigration"
        const val DONE = "1"
    }
}
