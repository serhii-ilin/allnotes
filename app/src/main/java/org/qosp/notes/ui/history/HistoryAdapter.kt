package org.qosp.notes.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.qosp.notes.R
import org.qosp.notes.databinding.LayoutHistoryHeaderBinding
import org.qosp.notes.databinding.LayoutHistoryItemBinding

sealed interface HistoryRow {
    data class Header(val text: String) : HistoryRow

    data class Event(
        val action: String,
        val device: String,
        val time: String,
    ) : HistoryRow

    data class Mismatch(
        val action: String,
        val device: String,
        val time: String,
        val reason: String,
    ) : HistoryRow
}

class HistoryAdapter : ListAdapter<HistoryRow, RecyclerView.ViewHolder>(DiffCallback()) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is HistoryRow.Header -> TYPE_HEADER
        else -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(LayoutHistoryHeaderBinding.inflate(inflater, parent, false))
            else -> ItemViewHolder(LayoutHistoryItemBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is HistoryRow.Header -> (holder as HeaderViewHolder).bind(row)
            is HistoryRow.Event -> (holder as ItemViewHolder).bind(row.action, row.device, row.time, null)
            is HistoryRow.Mismatch -> (holder as ItemViewHolder).bind(row.action, row.device, row.time, row.reason)
        }
    }

    private class HeaderViewHolder(
        private val binding: LayoutHistoryHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HistoryRow.Header) {
            binding.textHistoryHeader.text = row.text
        }
    }

    private class ItemViewHolder(
        private val binding: LayoutHistoryItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(action: String, device: String, time: String, reason: String?) = with(binding) {
            textHistoryAction.text = action
            textHistoryMeta.text = itemView.context.getString(
                R.string.history_entry_meta,
                device,
                time,
            )
            textHistoryReason.isVisible = reason != null
            textHistoryReason.text = reason
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<HistoryRow>() {
        override fun areItemsTheSame(oldItem: HistoryRow, newItem: HistoryRow) = oldItem == newItem

        override fun areContentsTheSame(oldItem: HistoryRow, newItem: HistoryRow) = oldItem == newItem
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }
}
