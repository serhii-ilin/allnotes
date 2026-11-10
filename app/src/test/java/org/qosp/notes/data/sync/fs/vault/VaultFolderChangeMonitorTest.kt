package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.data.sync.core.Success
import org.qosp.notes.di.SyncScope
import org.qosp.notes.preferences.PreferenceRepository

/**
 * Folder observer debounce + self-write suppression (injectable clock + delay, no ContentObserver).
 */
class VaultFolderChangeMonitorTest {

    private val context = mockk<Context>(relaxed = true)
    private val prefs = mockk<PreferenceRepository>(relaxed = true)
    private val noteRepository = mockk<NoteRepository>()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        coEvery { noteRepository.syncNotes() } returns Success
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun TestScopeMonitor(
        scope: SyncScope,
        clock: VaultWriteClock,
        debounceDelayMs: Long = 100L,
        selfWriteWindowMs: Long = 3_000L,
    ) = VaultFolderChangeMonitor(
        context = context,
        preferenceRepository = prefs,
        noteRepository = noteRepository,
        syncScope = scope,
        vaultWriteClock = clock,
        debounceDelayMs = debounceDelayMs,
        selfWriteWindowMs = selfWriteWindowMs,
    )

    @Test
    fun rapidChanges_collapseToSingleSync() = runTest {
        val scope = SyncScope(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val monitor = TestScopeMonitor(scope, VaultWriteClock(now = { 0L }))
        repeat(10) { monitor.onVaultChanged() }
        advanceTimeBy(100L)
        advanceUntilIdle()
        coVerify(exactly = 1) { noteRepository.syncNotes() }
    }

    @Test
    fun changeWithinSelfWriteWindow_isDeferredNotDropped() = runTest {
        var now = 5_000L
        val clock = VaultWriteClock(now = { now })
        val scope = SyncScope(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val monitor = TestScopeMonitor(scope, clock)
        clock.markWrite()
        now = 6_000L // 1000ms after the write: inside the 3s window (2000ms remaining).
        monitor.onVaultChanged()
        // Not yet: deferred until the window (2000ms) + debounce (100ms).
        advanceTimeBy(100L)
        coVerify(exactly = 0) { noteRepository.syncNotes() }
        // After the window + debounce, exactly one sync runs.
        advanceTimeBy(2_000L)
        advanceUntilIdle()
        coVerify(exactly = 1) { noteRepository.syncNotes() }
    }

    @Test
    fun changeAfterSelfWriteWindow_triggersSync() = runTest {
        var now = 5_000L
        val clock = VaultWriteClock(now = { now })
        val scope = SyncScope(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val monitor = TestScopeMonitor(scope, clock)
        clock.markWrite()
        now = 9_000L // 4000ms after the write: past the 3s window.
        monitor.onVaultChanged()
        advanceTimeBy(100L)
        advanceUntilIdle()
        coVerify(exactly = 1) { noteRepository.syncNotes() }
    }
}
