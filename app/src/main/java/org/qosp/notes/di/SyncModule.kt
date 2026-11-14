package org.qosp.notes.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.qosp.notes.BuildConfig
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.core.ProcessRemoteActions
import org.qosp.notes.data.sync.core.SynchronizeNotes
import org.qosp.notes.data.sync.fs.vault.VaultFolderChangeMonitor
import org.qosp.notes.data.sync.fs.vault.VaultForegroundRescan
import org.qosp.notes.data.sync.fs.vault.VaultPortableMetadata
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.data.sync.fs.vault.VaultTreeAccess
import org.qosp.notes.data.sync.fs.vault.VaultWriteClock
import org.qosp.notes.data.sync.fs.vault.history.DeviceIdentity
import org.qosp.notes.data.sync.fs.vault.history.FolderHistory
import org.qosp.notes.data.sync.fs.vault.history.FolderHistoryService
import org.qosp.notes.data.sync.fs.vault.history.HistoryOutbox
import org.qosp.notes.data.sync.fs.vault.history.HistoryApplyCoordinator
import org.qosp.notes.data.sync.fs.vault.history.HistoryApplySettings
import org.qosp.notes.data.sync.fs.vault.history.HistoryCatalogApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryMismatchStore
import org.qosp.notes.data.sync.fs.vault.history.HistoryNoteApplier
import org.qosp.notes.data.sync.fs.vault.history.VaultFolderHistory
import org.qosp.notes.preferences.HistoryRetention
import org.qosp.notes.preferences.PreferenceRepository

object SyncModule {

    val syncModule = module {
        single<SyncScope> { SyncScope(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) }

        singleOf(::BackendProvider)
        singleOf(::ProcessRemoteActions)
        singleOf(::SynchronizeNotes)
        singleOf(::VaultPortableMetadata)
        singleOf(::VaultSyncCoordinator)
        singleOf(::VaultTreeAccess)
        singleOf(::VaultForegroundRescan)
        // The clock and the monitor have defaulted constructor parameters, which Koin's constructor DSL
        // would try to resolve (AUD-1). Build them with their defaults and keep one shared clock so the
        // monitor recognises the app's own vault writes.
        single { VaultWriteClock() }
        single {
            VaultFolderChangeMonitor(
                context = get(),
                preferenceRepository = get(),
                noteRepository = get(),
                syncScope = get(),
                reminderManager = get(),
                vaultWriteClock = get(),
            )
        }

        // Folder history: explicit factories, because Koin's constructor DSL ignores Kotlin defaults and
        // DeviceIdentity and FolderHistoryService both have one.
        single { DeviceIdentity(get()) }
        single { HistoryOutbox(HistoryOutbox.defaultFile(androidContext())) }
        single {
            // The retention period is device-local and read per flush; capture the repository here because
            // the lambda runs later, outside this definition's Koin scope.
            val preferences: PreferenceRepository = get()
            FolderHistoryService(
                identity = get(),
                outbox = get(),
                appVersion = BuildConfig.VERSION_NAME,
                backendProvider = get(),
                retention = { preferences.get<HistoryRetention>().first().keepMonths },
            )
        }
        single { HistoryApplySettings(get()) }
        single { HistoryMismatchStore(HistoryMismatchStore.defaultFile(androidContext())) }
        // Release B appliers are listed here, in the order they run.
        single {
            HistoryApplyCoordinator(
                get(),
                get(),
                get(),
                appliers = listOf(
                    HistoryNoteApplier(
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        now = System::currentTimeMillis,
                    ),
                    HistoryCatalogApplier(get(), get(), get(), get()),
                ),
            )
        }
        single<FolderHistory> { VaultFolderHistory(get(), get(), get(), mismatchStore = get()) }
    }
}

class SyncScope(scope: CoroutineScope) : CoroutineScope by scope
