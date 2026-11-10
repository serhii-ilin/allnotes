package org.qosp.notes.ui.common.recycler

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewSourceTest {

    @Test
    fun `short text is returned unchanged`() {
        val content = "First line\nSecond line\nThird line"

        assertEquals(content, previewSource(content))
    }

    @Test
    fun `empty string stays empty`() {
        assertEquals("", previewSource(""))
    }

    @Test
    fun `caps content at the line limit`() {
        val content = (1..20).joinToString("\n") { "line $it" }
        val expected = (1..15).joinToString("\n") { "line $it" }

        assertEquals(expected, previewSource(content))
    }

    @Test
    fun `caps long single-line content at the character limit`() {
        val result = previewSource("x".repeat(2_000))

        assertEquals(800, result.length)
        assertEquals("x".repeat(800), result)
    }

    @Test
    fun `cuts at the last line boundary before the character limit`() {
        val line = "x".repeat(100)
        val content = (1..10).joinToString("\n") { line }

        val result = previewSource(content, maxChars = 250)

        assertEquals("$line\n$line", result)
    }

    @Test
    fun `falls back to a hard cut when no line boundary fits`() {
        val result = previewSource("x".repeat(50) + "\nshort", maxChars = 10)

        assertEquals("x".repeat(10), result)
    }

    @Test
    fun `hard cut does not split a surrogate pair`() {
        val content = "a😀" + "x".repeat(20)

        // Cutting at two chars would leave the high surrogate of the emoji alone.
        assertEquals("a", previewSource(content, maxChars = 2))
    }

    @Test
    fun `hard cut keeps a complete surrogate pair`() {
        val content = "a😀" + "x".repeat(20)

        assertEquals("a😀", previewSource(content, maxChars = 3))
    }
}
