package org.qosp.notes.ui.tasks

import org.qosp.notes.ui.utils.vault.VaultMarkwonContext

/**
 * Pure task-row Markdown rendering plan (vault context + source text for [io.noties.markwon.Markwon.applyTo]).
 */
data class TaskRowRenderPlan(
    val markdown: String?,
    val vaultContext: VaultMarkwonContext?,
)

fun planTaskRowRender(
    text: String,
    inPreview: Boolean,
    isChecked: Boolean,
    vaultRelativePath: String?,
): TaskRowRenderPlan {
    val markdown = when {
        inPreview && text.isNotBlank() -> text
        !inPreview && isChecked && text.isNotBlank() -> "~~${text.trim()}~~"
        else -> null
    }
    val vaultContext = if (markdown != null && !vaultRelativePath.isNullOrBlank()) {
        VaultMarkwonContext(vaultRelativePath)
    } else {
        null
    }
    return TaskRowRenderPlan(markdown, vaultContext)
}
