package org.qosp.notes.ui.whatsnew

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.qosp.notes.R
import org.qosp.notes.data.WhatsNewItem
import org.qosp.notes.databinding.DialogWhatsNewBinding
import org.qosp.notes.databinding.ItemWhatsNewBinding

/**
 * Shows the same content the old Compose welcome screen did, without leaving the main activity.
 * [onDismiss] is called no matter how the dialog is closed, so the caller can mark the update as seen.
 */
fun Activity.showWhatsNewDialog(item: WhatsNewItem, onDismiss: () -> Unit) {
    val binding = DialogWhatsNewBinding.inflate(layoutInflater)

    item.items.forEach { body ->
        val row = ItemWhatsNewBinding.inflate(layoutInflater, binding.items, false)
        row.text.text = body
        binding.items.addView(row.root)
    }

    MaterialAlertDialogBuilder(this)
        .setTitle(item.title)
        .setView(binding.root)
        .setPositiveButton(R.string.continue_next, null)
        .setOnDismissListener { onDismiss() }
        .show()
}
