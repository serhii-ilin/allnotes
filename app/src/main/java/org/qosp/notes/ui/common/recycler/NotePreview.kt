package org.qosp.notes.ui.common.recycler

const val PREVIEW_MAX_LINES = 15
const val PREVIEW_MAX_CHARS = 800

/**
 * Cuts [content] down to what a note card can display. Parsing and laying out the whole note
 * just to hide most of it is the main cost of binding a card, so keep the preview small.
 */
fun previewSource(
    content: String,
    maxLines: Int = PREVIEW_MAX_LINES,
    maxChars: Int = PREVIEW_MAX_CHARS,
): String {
    if (content.isEmpty()) return content

    val lines = content.lineSequence().take(maxLines.coerceAtLeast(1)).joinToString("\n")
    if (lines.length <= maxChars) return lines

    val limit = maxChars.coerceAtLeast(0)
    if (limit == 0) return ""

    // Prefer cutting after a complete line instead of mid-word; fall back to a hard cut
    // when the first line is already longer than the limit.
    val lineBoundary = lines.lastIndexOf('\n', limit - 1)
    return if (lineBoundary > 0) lines.substring(0, lineBoundary) else lines.substring(0, limit)
}
