package org.qosp.notes.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.core.ProcessRemoteActions
import org.qosp.notes.data.sync.core.SynchronizeNotes
import org.qosp.notes.data.sync.fs.vault.VaultFolderChangeMonitor
import org.qosp.notes.data.sync.fs.vault.VaultForegroundRescan
import org.qosp.notes.data.sync.fs.vault.VaultPortableMetadata
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.data.sync.fs.vault.VaultTreeAccess
import org.qosp.notes.data.sync.fs.vault.VaultWriteClock
import org.qosp.notes.data.repo.NoteRepository

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
        singleOf(::VaultWriteClock)
        single {
            VaultFolderChangeMonitor(
                context = get(),
                preferenceRepository = get(),
                noteRepository = get(),
                syncScope = get(),
                vaultWriteClock = get(),
            )
        }
    }
}

class SyncScope(scope: CoroutineScope) : CoroutineScope by scope
