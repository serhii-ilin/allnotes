package org.qosp.notes.ui.common.recycler

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.util.LruCache
import android.widget.TextView
import io.noties.markwon.Markwon
import org.commonmark.node.Code
import org.qosp.notes.R
import org.qosp.notes.data.model.Note
import org.qosp.notes.ui.editor.markdown.MarkdownOptions
import org.qosp.notes.ui.editor.markdown.OptionsVisitor
import org.qosp.notes.ui.utils.vault.VaultMarkwonContext
import org.qosp.notes.ui.utils.vault.VaultMarkwonRenderContext

/**
 * Renders note previews and keeps the recent results. The cache lives with the adapter, so a
 * configuration change (which recreates the adapter along with the view) naturally drops it.
 */
class NotePreviewRenderer(
    private val markwon: Markwon,
    context: Context,
) {

    private val context = context.applicationContext
    private val cache = object : LruCache<PreviewKey, Spanned>(CACHE_SIZE) {}

    fun text(note: Note): CharSequence = previewSource(note.content)

    fun apply(textView: TextView, note: Note, vaultRelativePath: String?) {
        val source = previewSource(note.content)
        val rendered = render(note, source, vaultRelativePath)

        textView.setTag(R.id.tag_vault_markwon_context, vaultRelativePath?.let(::VaultMarkwonContext))
        markwon.setParsedMarkdown(textView, rendered)
    }

    private fun render(note: Note, source: String, vaultRelativePath: String?): Spanned {
        val key = PreviewKey(note.id, note.content.hashCode(), note.isMarkdownEnabled, vaultRelativePath)
        cache.get(key)?.let { return it }

        val rendered = if (note.isMarkdownEnabled && source.isNotBlank()) {
            renderMarkdown(source, vaultRelativePath)
        } else {
            SpannableString(source)
        }

        cache.put(key, rendered)
        return rendered
    }

    private fun renderMarkdown(source: String, vaultRelativePath: String?): Spanned {
        val options = MarkdownOptions().apply {
            maximumTableColumns = 4
            tableReplacement = { Code(context.getString(R.string.message_cannot_preview_table)) }
        }

        val node = markwon.parse(source)
        node.accept(OptionsVisitor(options))

        return try {
            VaultMarkwonRenderContext.set(vaultRelativePath?.let(::VaultMarkwonContext))
            markwon.render(node)
        } finally {
            VaultMarkwonRenderContext.clear()
        }
    }

    private data class PreviewKey(
        val noteId: Long,
        val contentHash: Int,
        val isMarkdownEnabled: Boolean,
        val vaultRelativePath: String?,
    )

    private companion object {
        const val CACHE_SIZE = 200
    }
}
