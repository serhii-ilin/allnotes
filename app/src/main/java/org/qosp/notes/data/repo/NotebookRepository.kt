package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.model.Notebook

class NotebookRepository(
    private val notebookDao: NotebookDao,
    private val noteRepository: NoteRepository,
) {

    suspend fun insert(notebook: Notebook): Long {
        return notebookDao.insert(notebook)
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
    }

    suspend fun update(vararg notebooks: Notebook, shouldSync: Boolean = true) {
        val renamed = notebooks.filter { updated ->
            val before = notebookDao.getById(updated.id).first()
            before != null && before.name != updated.name
        }
        notebookDao.update(*notebooks)
        // Each file lives in its notebook's folder: pushing the notes moves them to the renamed one.
        val affectedNotes = renamed.flatMap { noteRepository.getByNotebook(it.id).first() }
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
