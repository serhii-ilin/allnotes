package org.qosp.notes.ui.history

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.qosp.notes.data.sync.fs.vault.history.FolderHistory
import org.qosp.notes.data.sync.fs.vault.history.FolderHistorySnapshot
import org.qosp.notes.data.sync.fs.vault.history.HistoryEntry
import org.qosp.notes.data.sync.fs.vault.history.HistoryMismatch

@OptIn(ExperimentalCoroutinesApi::class)
class FolderHistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun histories(loader: suspend () -> FolderHistorySnapshot) = object : FolderHistory {
        override suspend fun load() = loader()
    }

    private fun entry(timeMs: Long) = HistoryEntry(
        timeMs = timeMs,
        deviceLabel = "Pixel 9 · a1b2c3",
        op = "note.bin",
        knownOp = true,
        noteTitle = "Plan",
        path = "Plan.md",
    )

    @Test
    fun `exposes entries in the newest-first order the history gives`() = runTest(dispatcher) {
        val entries = listOf(entry(3), entry(2), entry(1))
        val model = FolderHistoryViewModel(histories { FolderHistorySnapshot(entries = entries) })

        advanceUntilIdle()

        assertEquals(entries, model.state.value.entries)
    }

    @Test
    fun `exposes skipped lines and mismatches`() = runTest(dispatcher) {
        val mismatches = listOf(
            HistoryMismatch(
                timeMs = 2,
                deviceLabel = "Pixel 9 · a1b2c3",
                op = "note.bin",
                reason = "The note has unsynced changes here",
                noteTitle = "Plan",
            )
        )
        val model = FolderHistoryViewModel(
            histories { FolderHistorySnapshot(entries = listOf(entry(1)), skippedLines = 4, mismatches = mismatches) }
        )

        advanceUntilIdle()

        assertEquals(4, model.state.value.skippedLines)
        assertEquals(mismatches, model.state.value.mismatches)
        assertFalse(model.state.value.isEmpty)
    }

    @Test
    fun `empty snapshot shows the empty state`() = runTest(dispatcher) {
        val model = FolderHistoryViewModel(histories { FolderHistorySnapshot() })

        advanceUntilIdle()

        assertTrue(model.state.value.isEmpty)
        assertFalse(model.state.value.loading)
        assertFalse(model.state.value.error)
    }

    @Test
    fun `a loader failure shows an error instead of crashing`() = runTest(dispatcher) {
        val model = FolderHistoryViewModel(histories { error("cannot read the folder") })

        advanceUntilIdle()

        assertTrue(model.state.value.error)
        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.entries.isEmpty())
    }

    @Test
    fun `a skipped line or mismatch is not an empty screen`() = runTest(dispatcher) {
        val model = FolderHistoryViewModel(histories { FolderHistorySnapshot(skippedLines = 1) })

        advanceUntilIdle()

        assertFalse(model.state.value.isEmpty)
    }
}
