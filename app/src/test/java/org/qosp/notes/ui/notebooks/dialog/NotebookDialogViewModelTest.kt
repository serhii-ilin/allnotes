package org.qosp.notes.ui.notebooks.dialog

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.repo.NotebookRepository
import org.qosp.notes.data.sync.fs.vault.VaultPaths

/** FMT-11 AC7: a typed notebook name is judged as typed and rejected with the reason, never altered. */
class NotebookDialogViewModelTest {

    private fun viewModel(notebooks: List<Notebook> = emptyList()): NotebookDialogViewModel {
        val repository = mockk<NotebookRepository>()
        every { repository.getAll() } returns flowOf(notebooks)
        return NotebookDialogViewModel(repository)
    }

    private suspend fun invalid(name: String, problem: VaultPaths.NameProblem) {
        assertEquals(
            "name=<$name>",
            NotebookNameResult.Invalid(problem),
            viewModel().validate(name),
        )
    }

    @Test
    fun aNameStartingWithADot_isRejected() = runTest {
        invalid(".trash", VaultPaths.NameProblem.LeadingDot)
    }

    @Test
    fun aNameWithAPathSeparator_isRejected() = runTest {
        invalid("a/b", VaultPaths.NameProblem.IllegalChar)
    }

    @Test
    fun aWindowsReservedName_isRejected() = runTest {
        invalid("CON", VaultPaths.NameProblem.ReservedName)
    }

    @Test
    fun aBlankName_isRejected() = runTest {
        invalid("", VaultPaths.NameProblem.Blank)
    }

    @Test
    fun aNameWithATrailingSpace_isRejectedAsTyped() = runTest {
        invalid("Work ", VaultPaths.NameProblem.TrailingDotOrSpace)
    }

    @Test
    fun aNameThatOnlyDiffersByCaseFromAnExistingOne_isADuplicate() = runTest {
        val model = viewModel(listOf(Notebook(name = "work", id = 1)))

        assertEquals(NotebookNameResult.Duplicate, model.validate("Work"))
    }

    @Test
    fun renamingANotebookToItsOwnName_isValid() = runTest {
        val model = viewModel(listOf(Notebook(name = "Work", id = 1)))

        assertEquals(NotebookNameResult.Valid, model.validate("Work", ignoreId = 1))
    }

    @Test
    fun aNewUniqueValidName_isValid() = runTest {
        val model = viewModel(listOf(Notebook(name = "Work", id = 1)))

        assertEquals(NotebookNameResult.Valid, model.validate("Home"))
    }
}
