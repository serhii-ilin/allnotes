package org.qosp.notes.ui.common.recycler

import android.content.Context
import android.text.Spanned
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.noties.markwon.Markwon
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotePreviewRendererTest {

    @Test
    fun `cache distinguishes equal hashes with different lengths`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val markwon = mockk<Markwon>(relaxed = true)
        val rendered = mutableListOf<Spanned>()
        every { markwon.setParsedMarkdown(any(), capture(rendered)) } just Runs

        val renderer = NotePreviewRenderer(markwon, context)
        val textView = TextView(context)
        // "VX" and "5foZnoBitWX3" share the same String.hashCode() but have different lengths.
        val first = Note(id = 1L, content = "VX", isMarkdownEnabled = false)
        val second = Note(id = 1L, content = "5foZnoBitWX3", isMarkdownEnabled = false)

        renderer.apply(textView, first, null)
        renderer.apply(textView, second, null)

        assertEquals("VX", rendered[0].toString())
        assertEquals("5foZnoBitWX3", rendered[1].toString())
    }
}
