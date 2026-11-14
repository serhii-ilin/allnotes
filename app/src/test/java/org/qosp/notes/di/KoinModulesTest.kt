package org.qosp.notes.di

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.verify.definition
import org.koin.test.verify.injectedParameters
import org.koin.test.verify.verify

class KoinModulesTest : KoinTest {

    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun testAllModules() {
        val allModules = module {
            includes(
                MarkwonModule.markwonModule,
                PreferencesModule.prefModule,
                RepositoryModule.repoModule,
                UIModule.uiModule,
                UtilModule.utilModule,
                SyncModule.syncModule,
            )
        }
        allModules.verify(
            extraTypes = listOf(
                Context::class,
                Application::class,
                WorkerParameters::class,
                SharedPreferences::class,
                CoroutineScope::class,
                kotlin.coroutines.CoroutineContext::class,
                SyncScope::class,
            ),
            injections = injectedParameters(
                definition<org.qosp.notes.ui.whatsnew.WhatsNewViewModel>(
                    Application::class
                ),
                definition<org.qosp.notes.components.workers.BinCleaningWorker>(
                    Context::class,
                    WorkerParameters::class
                ),
                definition<org.qosp.notes.components.workers.SyncWorker>(
                    Context::class,
                    WorkerParameters::class
                ),
                // The outbox file is app-private storage, not a Koin definition.
                definition<org.qosp.notes.data.sync.fs.vault.history.HistoryOutbox>(
                    java.io.File::class
                ),
                definition<org.qosp.notes.data.sync.fs.vault.history.HistoryMismatchStore>(
                    java.io.File::class
                ),
                // The appliers are listed inline in SyncModule, not resolved from Koin.
                definition<org.qosp.notes.data.sync.fs.vault.history.HistoryApplyCoordinator>(
                    List::class
                ),
            )
        )
    }
}
