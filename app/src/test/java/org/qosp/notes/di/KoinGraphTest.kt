package org.qosp.notes.di

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tfcporciuncula.flow.FlowSharedPreferences
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.get
import org.qosp.notes.components.MediaStorageManager
import org.qosp.notes.components.backup.BackupManager
import org.qosp.notes.data.repo.EditorSessionRegistry
import org.qosp.notes.data.repo.IdMappingRepository
import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.data.repo.NotebookNameMigration
import org.qosp.notes.data.repo.NotebookRepository
import org.qosp.notes.data.repo.ReminderRepository
import org.qosp.notes.data.repo.TagRepository
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.core.ProcessRemoteActions
import org.qosp.notes.data.sync.core.SynchronizeNotes
import org.qosp.notes.data.sync.fs.vault.VaultFolderChangeMonitor
import org.qosp.notes.data.sync.fs.vault.VaultForegroundRescan
import org.qosp.notes.data.sync.fs.vault.VaultPortableMetadata
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.data.sync.fs.vault.VaultTreeAccess
import org.qosp.notes.data.sync.fs.vault.VaultWriteClock
import org.qosp.notes.data.sync.fs.vault.history.FolderHistory
import org.qosp.notes.data.sync.fs.vault.history.FolderHistoryService
import org.qosp.notes.ui.history.FolderHistoryViewModel
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.ui.reminders.ReminderManager
import org.qosp.notes.ui.utils.Toaster
import org.robolectric.RobolectricTestRunner

/**
 * Builds the real Koin graph from `App.onCreate`.
 *
 * `Module.verify()` only inspects definitions, so it missed `singleOf(::VaultFolderChangeMonitor)`: Koin's
 * constructor DSL ignores Kotlin defaults and asked for a `Long` (AUD-1). Resolving every singleton the app
 * touches at start-up catches that class of wiring bug.
 */
@RunWith(RobolectricTestRunner::class)
class KoinGraphTest : KoinTest {

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun appModules_resolveTheSingletonsAppOnCreateNeeds() {
        startKoin {
            androidContext(ApplicationProvider.getApplicationContext<Application>())
            // Robolectric has no AndroidKeyStore, so the encrypted preferences single cannot be built; plain
            // SharedPreferences keeps the rest of the real graph, including SyncModule, untouched.
            allowOverride(true)
            modules(
                MarkwonModule.markwonModule,
                PreferencesModule.prefModule,
                RepositoryModule.repoModule,
                UIModule.uiModule,
                UtilModule.utilModule,
                SyncModule.syncModule,
                module {
                    single<FlowSharedPreferences> {
                        FlowSharedPreferences(
                            androidContext().getSharedPreferences("test_prefs", Context.MODE_PRIVATE)
                        )
                    }
                },
            )
        }

        // App.onCreate injects the monitor and starts it, so it is the first thing the graph builds.
        assertNotNull(get<VaultFolderChangeMonitor>())
        // The monitor shares this singleton with the writers, which is how it recognises app-made changes.
        assertNotNull(get<VaultWriteClock>())

        assertNotNull(get<PreferenceRepository>())
        assertNotNull(get<NoteRepository>())
        assertNotNull(get<BackendProvider>())
        assertNotNull(get<ReminderManager>())
        assertNotNull(get<Toaster>())

        // Sync and vault services behind the repository and the observer.
        assertNotNull(get<SynchronizeNotes>())
        assertNotNull(get<ProcessRemoteActions>())
        assertNotNull(get<VaultPortableMetadata>())
        assertNotNull(get<VaultSyncCoordinator>())
        assertNotNull(get<VaultTreeAccess>())
        assertNotNull(get<VaultForegroundRescan>())

        // Repositories and services the app's ViewModels and workers resolve.
        assertNotNull(get<EditorSessionRegistry>())
        assertNotNull(get<NotebookRepository>())
        assertNotNull(get<NotebookNameMigration>())
        assertNotNull(get<TagRepository>())
        assertNotNull(get<ReminderRepository>())
        assertNotNull(get<IdMappingRepository>())
        assertNotNull(get<MediaStorageManager>())
        assertNotNull(get<BackupManager>())

        // Folder history: recorder, screen source and the view model that reads it.
        val historyService = get<FolderHistoryService>()
        assertNotNull(historyService)
        assertNotNull(get<FolderHistory>())
        assertNotNull(get<FolderHistoryViewModel>())

        // The writers must receive the single recorder, not a null default: recording happens in the graph.
        assertSame(historyService, folderHistoryServiceOf(get<NoteRepository>()))
        assertSame(historyService, folderHistoryServiceOf(get<NotebookRepository>()))
        assertSame(historyService, folderHistoryServiceOf(get<TagRepository>()))
        assertSame(historyService, folderHistoryServiceOf(get<ProcessRemoteActions>()))
    }

    /** The repositories take it as an optional constructor parameter, so a missing binding would be silently null. */
    private fun folderHistoryServiceOf(instance: Any): FolderHistoryService? =
        instance.javaClass.getDeclaredField("folderHistoryService")
            .apply { isAccessible = true }
            .get(instance) as? FolderHistoryService
}
