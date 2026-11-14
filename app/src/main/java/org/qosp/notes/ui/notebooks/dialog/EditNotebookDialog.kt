package org.qosp.notes.ui.notebooks.dialog

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.qosp.notes.R
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.sync.fs.vault.VaultPaths
import org.qosp.notes.databinding.DialogEditNotebookBinding
import org.qosp.notes.ui.common.BaseDialog
import org.qosp.notes.ui.common.setButton
import org.qosp.notes.ui.utils.requestFocusAndKeyboard

class EditNotebookDialog : BaseDialog<DialogEditNotebookBinding>() {
    private val model: NotebookDialogViewModel by activityViewModel()
    private lateinit var notebook: Notebook

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notebook = arguments?.getParcelable(NOTEBOOK) ?: return
    }

    override fun createBinding(inflater: LayoutInflater) = DialogEditNotebookBinding.inflate(layoutInflater)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val renaming = this::notebook.isInitialized
        if (renaming) {
            dialog.setTitle(getString(R.string.action_rename_notebook))
            binding.editTextNotebookName.setText(notebook.name)
        } else {
            dialog.setTitle(getString(R.string.action_new_notebook))
        }

        dialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.action_save), this) {
            // The name is judged as typed: no trimming and no "Untitled" fallback, so it equals its folder.
            val name = binding.editTextNotebookName.text.toString()

            lifecycleScope.launch {
                when (val result = model.validate(name, ignoreId = if (renaming) notebook.id else null)) {
                    NotebookNameResult.Valid -> {
                        if (renaming) model.updateNotebook(notebook.copy(name = name))
                        else model.insertNotebook(Notebook(name))
                        dismiss()
                    }

                    NotebookNameResult.Duplicate -> showError(
                        getString(R.string.indicator_notebook_already_exists, name),
                    )

                    is NotebookNameResult.Invalid -> showError(
                        getString(result.problem.messageResource()),
                    )
                }
            }
        }
        binding.editTextNotebookName.requestFocusAndKeyboard()
    }

    private fun showError(message: String) {
        binding.editTextNotebookName.error = message
    }

    private fun VaultPaths.NameProblem.messageResource(): Int = when (this) {
        VaultPaths.NameProblem.Blank -> R.string.indicator_notebook_name_blank
        VaultPaths.NameProblem.IllegalChar -> R.string.indicator_notebook_name_illegal_char
        VaultPaths.NameProblem.LeadingDot -> R.string.indicator_notebook_name_leading_dot
        VaultPaths.NameProblem.TrailingDotOrSpace -> R.string.indicator_notebook_name_trailing_dot_or_space
        VaultPaths.NameProblem.ReservedName -> R.string.indicator_notebook_name_reserved_name
        VaultPaths.NameProblem.TooLong -> R.string.indicator_notebook_name_too_long
    }

    companion object {
        private const val NOTEBOOK = "NOTEBOOK"
        fun build(notebook: Notebook?): EditNotebookDialog {
            return EditNotebookDialog().apply {
                arguments = if (notebook == null) bundleOf() else bundleOf(
                    NOTEBOOK to notebook
                )
            }
        }
    }
}
