package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.di.SyncScope
import org.qosp.notes.preferences.PreferenceRepository

/**
 * Reacts to external changes under the vault tree URI via [ContentObserver], with
 * resume/periodic sync as a fallback ([SyncWorker], [org.qosp.notes.ui.MainActivity] resume).
 */
class VaultFolderChangeMonitor(
    private val context: Context,
    private val preferenceRepository: PreferenceRepository,
    private val noteRepository: NoteRepository,
    private val syncScope: SyncScope,
) {
    private val tag = VaultFolderChangeMonitor::class.java.simpleName
    private var observer: ContentObserver? = null

    fun start() {
        syncScope.launch {
            preferenceRepository
                .getEncryptedString(PreferenceRepository.STORAGE_LOCATION)
                .distinctUntilChanged()
                .collect { location -> registerObserver(location) }
        }
    }

    private fun registerObserver(location: String) {
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
        if (location.isBlank()) return
        val treeUri = Uri.parse(location)
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                syncScope.launch {
                    Log.d(tag, "Vault tree change; scheduling sync")
                    noteRepository.syncNotes()
                }
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
