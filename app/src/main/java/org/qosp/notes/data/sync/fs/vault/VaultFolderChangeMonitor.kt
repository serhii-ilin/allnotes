package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.di.SyncScope
import org.qosp.notes.preferences.PreferenceRepository

/**
 * Reacts to external changes under the vault tree URI via [ContentObserver], with
 * resume/periodic sync as a fallback ([SyncWorker], [org.qosp.notes.ui.MainActivity] resume).
 *
 * Change bursts are collapsed into one sync [debounceDelayMs] after the last event, and events that
 * arrive within [selfWriteWindowMs] of our own vault write are ignored entirely.
 */
class VaultFolderChangeMonitor(
    private val context: Context,
    private val preferenceRepository: PreferenceRepository,
    private val noteRepository: NoteRepository,
    private val syncScope: SyncScope,
    private val vaultWriteClock: VaultWriteClock = VaultWriteClock(),
    private val debounceDelayMs: Long = 2_000L,
    private val selfWriteWindowMs: Long = 3_000L,
) {
    private val tag = VaultFolderChangeMonitor::class.java.simpleName
    private var observer: ContentObserver? = null
    private var debounceJob: Job? = null

    fun start() {
        syncScope.launch {
            preferenceRepository
                .getEncryptedString(PreferenceRepository.STORAGE_LOCATION)
                .distinctUntilChanged()
                .collect { location -> registerObserver(location) }
        }
    }

    /** Test seam: collapses bursts and suppresses self-triggered events (no [ContentObserver] required). */
    internal fun onVaultChanged() {
        if (vaultWriteClock.millisSinceLastWrite() < selfWriteWindowMs) {
            Log.d(tag, "Ignoring self-triggered vault change")
            return
        }
        debounceJob?.cancel()
        debounceJob = syncScope.launch {
            delay(debounceDelayMs)
            Log.d(tag, "Vault tree change; scheduling sync")
            noteRepository.syncNotes()
        }
    }

    private fun registerObserver(location: String) {
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
        if (location.isBlank()) return
        val treeUri = Uri.parse(location)
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onVaultChanged()
            }
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(treeUri, true, obs)
        }.isSuccess
        if (registered) {
            observer = obs
        } else {
            Log.w(tag, "ContentObserver not registered for $treeUri; rely on periodic/resume sync")
        }
    }
}
