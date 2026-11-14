package org.qosp.notes.ui.notebooks.dialog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.repo.NotebookRepository
import org.qosp.notes.data.sync.fs.vault.VaultPaths

/** FMT-11 AC7: why a typed notebook name cannot be used, or that it may be saved. */
sealed interface NotebookNameResult {
    data object Valid : NotebookNameResult
    data class Invalid(val problem: VaultPaths.NameProblem) : NotebookNameResult
    data object Duplicate : NotebookNameResult
}

class NotebookDialogViewModel(private val notebookRepository: NotebookRepository) : ViewModel() {
    fun insertNotebook(notebook: Notebook) {
        viewModelScope.launch(Dispatchers.IO) {
            notebookRepository.insert(notebook)
        }
    }

    fun updateNotebook(notebook: Notebook) {
        viewModelScope.launch(Dispatchers.IO) {
            notebookRepository.update(notebook)
        }
    }

    /**
     * Judges the name exactly as typed: [name] is never trimmed. A duplicate is another notebook whose
     * [VaultPaths.collisionKey] equals this one's, so "Work" and "work" cannot become the same folder.
     */
    suspend fun validate(name: String, ignoreId: Long? = null): NotebookNameResult {
        VaultPaths.validateSegmentName(name)?.let { return NotebookNameResult.Invalid(it) }
        val key = VaultPaths.collisionKey(name)
        val duplicate = notebookRepository.getAll().first().any { notebook ->
            notebook.id != ignoreId && VaultPaths.collisionKey(notebook.name) == key
        }
        return if (duplicate) NotebookNameResult.Duplicate else NotebookNameResult.Valid
    }
}
