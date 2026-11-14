package org.qosp.notes.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.qosp.notes.data.sync.fs.vault.history.FolderHistory
import org.qosp.notes.data.sync.fs.vault.history.HistoryEntry
import org.qosp.notes.data.sync.fs.vault.history.HistoryMismatch

data class FolderHistoryUiState(
    val loading: Boolean = true,
    val entries: List<HistoryEntry> = emptyList(),
    val skippedLines: Int = 0,
    val mismatches: List<HistoryMismatch> = emptyList(),
    val error: Boolean = false,
) {
    /** True when there is nothing at all to show, not only when there are no events. */
    val isEmpty: Boolean
        get() = !loading && !error && entries.isEmpty() && mismatches.isEmpty() && skippedLines == 0
}

class FolderHistoryViewModel(
    private val folderHistory: FolderHistory,
) : ViewModel() {

    private val _state = MutableStateFlow(FolderHistoryUiState())
    val state: StateFlow<FolderHistoryUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            _state.value = try {
                val snapshot = folderHistory.load()
                FolderHistoryUiState(
                    loading = false,
                    entries = snapshot.entries,
                    skippedLines = snapshot.skippedLines,
                    mismatches = snapshot.mismatches,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A missing or unreadable folder must not take the screen down with it.
                FolderHistoryUiState(loading = false, error = true)
            }
        }
    }
}
