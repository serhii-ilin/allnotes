package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.di.SyncScope
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.ui.reminders.ReminderManager

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
    private val reminderManager: ReminderManager,
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

    /**
     * Test seam: collapses bursts into one sync (no [ContentObserver] required).
     *
     * Events that arrive within [selfWriteWindowMs] of our own write are not dropped: an external
     * change (e.g. Syncthing) arriving right after our write must still be picked up. Instead the
     * sync is deferred until the self-write window has elapsed, plus the debounce delay.
     */
    internal fun onVaultChanged() {
        val sinceWrite = vaultWriteClock.millisSinceLastWrite()
        val delayMs = if (sinceWrite < selfWriteWindowMs) {
            debounceDelayMs + (selfWriteWindowMs - sinceWrite)
        } else {
            debounceDelayMs
        }
        debounceJob?.cancel()
        debounceJob = syncScope.launch {
            delay(delayMs) // cancellable waiting phase
            // Once a sync starts it must run to completion: our own writes re-fire the observer, and a
            // cancellation here would leave partial state (e.g. a note inserted without its mapping).
            withContext(NonCancellable) {
                Log.d(tag, "Vault tree change; scheduling sync")
                noteRepository.syncNotes()
                reminderManager.rescheduleAll()
            }
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
