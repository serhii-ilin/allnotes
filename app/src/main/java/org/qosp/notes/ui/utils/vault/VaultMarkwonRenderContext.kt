package org.qosp.notes.ui.utils.vault

/**
 * Per-render vault path for Markwon image resolution (Markwon's drawable callback is not always a [android.widget.TextView]).
 */
object VaultMarkwonRenderContext {
    private val current = ThreadLocal<VaultMarkwonContext?>()

    fun set(context: VaultMarkwonContext?) {
        current.set(context)
    }

    fun get(): VaultMarkwonContext? = current.get()

    fun clear() {
        current.remove()
    }
}
