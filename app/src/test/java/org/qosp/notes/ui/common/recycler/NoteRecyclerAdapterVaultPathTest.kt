package org.qosp.notes.ui.common.recycler

import android.os.Looper
import androidx.recyclerview.widget.RecyclerView
import io.mockk.mockk
import io.noties.markwon.Markwon
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteRecyclerAdapterVaultPathTest {

    private val notes = listOf(
        Note(id = 1, title = "One"),
        Note(id = 2, title = "Two"),
        Note(id = 3, title = "Three"),
    )

    @Test
    fun `identical map triggers no item change notifications`() {
        val adapter = createAdapter()
        val initialPaths = mapOf(1L to "one.md", 2L to "two.md", 3L to "three.md")
        adapter.vaultRelativePaths = initialPaths
        idleMainLooper()

        val observer = RecordingObserver()
        adapter.registerAdapterDataObserver(observer)

        // Structurally equal but a different map instance, as emitted by repeatOnLifecycle.
        adapter.vaultRelativePaths = HashMap(initialPaths)
        idleMainLooper()

        assertEquals(0, observer.totalEvents)
    }

    @Test
    fun `changed path notifies only the affected position`() {
        val adapter = createAdapter()
        val initialPaths = mapOf(1L to "one.md", 2L to "two.md", 3L to "three.md")
        adapter.vaultRelativePaths = initialPaths
        idleMainLooper()

        val observer = RecordingObserver()
        adapter.registerAdapterDataObserver(observer)

        adapter.vaultRelativePaths = initialPaths + (2L to "folder/two.md")
        idleMainLooper()

        assertEquals(1, observer.rangeChanges.size)
        assertEquals(0, observer.otherEvents)

        val change = observer.rangeChanges.single()
        assertEquals(1, change.positionStart) // "Two" is at index 1.
        assertEquals(1, change.itemCount)
        assertEquals(NoteRecyclerAdapter.Payload.VaultPathChanged, change.payload)
    }

    private fun createAdapter(): NoteRecyclerAdapter {
        val adapter = NoteRecyclerAdapter(listener = null, markwon = mockk(relaxed = true))
        adapter.submitList(notes)
        awaitSubmittedList(adapter, notes.size)
        return adapter
    }

    private fun awaitSubmittedList(adapter: NoteRecyclerAdapter, expectedSize: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            idleMainLooper()
            if (adapter.itemCount == expectedSize) return
            Thread.sleep(10)
        }
        fail("Adapter list was not submitted in time")
    }

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class RecordingObserver : RecyclerView.AdapterDataObserver() {
        data class RangeChange(val positionStart: Int, val itemCount: Int, val payload: Any?)

        val rangeChanges = mutableListOf<RangeChange>()
        var otherEvents = 0
            private set

        val totalEvents get() = rangeChanges.size + otherEvents

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            rangeChanges.add(RangeChange(positionStart, itemCount, payload))
        }

        override fun onChanged() {
            otherEvents++
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            otherEvents++
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            otherEvents++
        }

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
            otherEvents++
        }
    }
}
