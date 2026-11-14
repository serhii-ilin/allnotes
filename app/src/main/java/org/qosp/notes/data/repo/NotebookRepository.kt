package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.sync.fs.vault.VaultPaths
import org.qosp.notes.data.sync.fs.vault.history.FolderHistoryService
import org.qosp.notes.data.sync.fs.vault.history.HistoryOps

class NotebookRepository(
    private val notebookDao: NotebookDao,
    private val noteRepository: NoteRepository,
    private val folderHistoryService: FolderHistoryService? = null,
) {

    /** A notebook name is its folder name, so an invalid segment never reaches the database (FMT-11 AC7). */
    private fun requireValidName(name: String) {
        VaultPaths.validateSegmentName(name)?.let { problem ->
            throw IllegalArgumentException("Invalid notebook name <$name>: $problem")
        }
    }

    suspend fun insert(notebook: Notebook): Long {
        requireValidName(notebook.name)
        val id = notebookDao.insert(notebook)
        folderHistoryService?.record(HistoryOps.NOTEBOOK_CREATE, name = notebook.name)
        return id
    }

    suspend fun delete(vararg notebooks: Notebook) {
        val affectedNotes = notebooks
            .map { noteRepository.getByNotebook(it.id).first() }
            .flatten()

        // Room's SET_NULL would clear the column without a push; do it through the repository so each file
        // moves to the root and stops naming the notebook, before the notebook row goes.
        if (affectedNotes.isNotEmpty()) {
            noteRepository.updateNotes(
                *affectedNotes.map { it.copy(notebookId = null) }.toTypedArray(),
                sync = true,
            )
        }
        notebookDao.delete(*notebooks)
        notebooks.forEach { folderHistoryService?.record(HistoryOps.NOTEBOOK_DELETE, name = it.name) }
    }

    suspend fun update(vararg notebooks: Notebook, shouldSync: Boolean = true) {
        notebooks.forEach { requireValidName(it.name) }
        val renamed = notebooks.mapNotNull { updated ->
            val before = notebookDao.getById(updated.id).first()
            if (before != null && before.name != updated.name) before.name to updated else null
        }
        notebookDao.update(*notebooks)
        renamed.forEach { (oldName, updated) ->
            folderHistoryService?.record(HistoryOps.NOTEBOOK_RENAME, name = oldName, to = updated.name)
        }
        // Each file lives in its notebook's folder: pushing the notes moves them to the renamed one.
        val affectedNotes = renamed.flatMap { (_, updated) -> noteRepository.getByNotebook(updated.id).first() }
        if (affectedNotes.isNotEmpty()) {
            noteRepository.updateNotes(*affectedNotes.toTypedArray(), sync = shouldSync)
        }
    }

    fun getById(notebookId: Long): Flow<Notebook?> {
        return notebookDao.getById(notebookId)
    }

    fun getAll(): Flow<List<Notebook>> {
        return notebookDao.getAll()
    }

    fun getByName(name: String): Flow<Notebook?> {
        return notebookDao.getByName(name)
    }
}
