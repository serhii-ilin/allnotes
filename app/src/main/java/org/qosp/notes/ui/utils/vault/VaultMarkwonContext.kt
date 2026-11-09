package org.qosp.notes.ui.utils.vault

/**
 * Per-render vault context for a single note (set on the target [android.widget.TextView] tag, not ThreadLocal).
 */
data class VaultMarkwonContext(
    val noteVaultRelativePath: String,
)
