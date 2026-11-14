package org.qosp.notes.di

import org.koin.android.ext.koin.androidApplication
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.qosp.notes.ui.ActivityViewModel
import org.qosp.notes.ui.archive.ArchiveViewModel
import org.qosp.notes.ui.attachments.dialog.AttachmentDialogViewModel
import org.qosp.notes.ui.deleted.DeletedViewModel
import org.qosp.notes.ui.editor.EditorViewModel
import org.qosp.notes.ui.history.FolderHistoryViewModel
import org.qosp.notes.ui.main.MainViewModel
import org.qosp.notes.ui.notebooks.ManageNotebooksViewModel
import org.qosp.notes.ui.notebooks.dialog.NotebookDialogViewModel
import org.qosp.notes.ui.reminders.EditReminderViewModel
import org.qosp.notes.ui.search.SearchViewModel
import org.qosp.notes.ui.settings.SettingsViewModel
import org.qosp.notes.ui.tags.TagsViewModel
import org.qosp.notes.ui.tags.dialog.TagDialogViewModel
import org.qosp.notes.ui.whatsnew.WhatsNewViewModel

object UIModule {
    val uiModule = module {
        viewModel {
            EditorViewModel(
                noteRepository = get(),
                notebookRepository = get(),
                preferenceRepository = get(),
                vaultTreeAccess = get(),
                editorSessionRegistry = get(),
                syncScope = get(),
            )
        }
        viewModelOf(::ActivityViewModel)
        viewModelOf(::ArchiveViewModel)
        viewModelOf(::TagsViewModel)
        viewModelOf(::TagDialogViewModel)
        viewModelOf(::SettingsViewModel)
        viewModelOf(::SearchViewModel)
        viewModelOf(::EditReminderViewModel)
        viewModelOf(::ManageNotebooksViewModel)
        viewModelOf(::NotebookDialogViewModel)
        viewModelOf(::MainViewModel)
        viewModel { WhatsNewViewModel(androidApplication(), get()) }
        viewModelOf(::DeletedViewModel)
        viewModelOf(::AttachmentDialogViewModel)
        viewModel { FolderHistoryViewModel(get()) }
    }
}
