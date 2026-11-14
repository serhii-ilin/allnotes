package org.qosp.notes.ui.history

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.qosp.notes.R
import org.qosp.notes.databinding.FragmentHistoryBinding
import org.qosp.notes.ui.common.BaseFragment
import org.qosp.notes.ui.utils.collect
import org.qosp.notes.ui.utils.viewBinding

class FolderHistoryFragment : BaseFragment(R.layout.fragment_history) {
    private val binding by viewBinding(FragmentHistoryBinding::bind)
    private val model: FolderHistoryViewModel by viewModel()

    private val adapter = HistoryAdapter()
    private lateinit var formatter: FolderHistoryFormatter

    override val hasMenu = false
    override val toolbar: Toolbar
        get() = binding.layoutAppBar.toolbar
    override val toolbarTitle: String
        get() = getString(R.string.nav_history)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        formatter = FolderHistoryFormatter(requireContext())
        binding.recyclerHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerHistory.adapter = adapter

        binding.layoutSwipeRefresh.setOnRefreshListener { model.load() }
        binding.textHistoryError.setOnClickListener { model.load() }

        model.state.collect(viewLifecycleOwner) { render(it) }
    }

    private fun render(state: FolderHistoryUiState) {
        val rows = buildRows(state)
        adapter.submitList(rows)

        binding.layoutSwipeRefresh.isRefreshing = state.loading
        binding.indicatorHistoryEmpty.isVisible = state.isEmpty
        binding.textHistoryError.isVisible = state.error
        binding.recyclerHistory.isVisible = rows.isNotEmpty()
    }

    private fun buildRows(state: FolderHistoryUiState): List<HistoryRow> = buildList {
        if (state.skippedLines > 0) {
            add(
                HistoryRow.Header(
                    resources.getQuantityString(
                        R.plurals.history_skipped_lines,
                        state.skippedLines,
                        state.skippedLines,
                    )
                )
            )
        }

        if (state.mismatches.isNotEmpty()) {
            add(HistoryRow.Header(getString(R.string.history_not_applied_here)))
            state.mismatches.forEach { mismatch ->
                val row = formatter.format(mismatch)
                add(HistoryRow.Mismatch(row.action, row.device, row.time, row.reason.orEmpty()))
            }
        }

        state.entries.forEach { entry ->
            val row = formatter.format(entry)
            add(HistoryRow.Event(row.action, row.device, row.time))
        }
    }
}
