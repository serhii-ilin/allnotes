package org.qosp.notes.data.sync.fs.vault.history

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.qosp.notes.preferences.PreferenceRepository
import java.io.File

/**
 * Release B: turns other devices' recorded actions into changes on this device during sync.
 *
 * Every applier is conservative. It changes the app's notes, notebooks or tags only, never deletes or moves a file
 * in the folder, and skips (reporting a [StoredMismatch]) whatever the files or local edits contradict. It is
 * idempotent: applying the same history twice changes nothing the second time.
 */
interface HistoryApplier {
    /**
     * Called inside the vault lock after a complete scan.
     *
     * @param history the merged logs; only items with `applicable == true` may change anything.
     * @param ownDeviceId this install's id; its own events are never applied here, they only shadow older events
     *   from other devices.
     * @param scannedPaths normalized vault-relative paths of every note file the scan listed.
     */
    suspend fun apply(history: HistoryReadResult, ownDeviceId: String, scannedPaths: Set<String>): ApplyOutcome
}

/** [changed] when notes, notebooks or tags were modified, so sync re-reads them before deciding anything else. */
data class ApplyOutcome(val changed: Boolean = false, val mismatches: List<StoredMismatch> = emptyList())

/** An event this device did not apply, kept on this device only and shown on the Folder history screen. */
@Serializable
data class StoredMismatch(
    val eid: String,
    val t: Long,
    val dev: String,
    val op: String,
    val reason: String,
    val id: String? = null,
    val path: String? = null,
    val name: String? = null,
)

/**
 * The current set of unapplied events (`files/vault-history/mismatches.json`). It is replaced on every sync, so an
 * event that applies later drops out by itself.
 */
class HistoryMismatchStore(private val file: File) {

    @Synchronized
    fun readAll(): List<StoredMismatch> {
        if (!file.exists()) return emptyList()
        return runCatching {
            HistoryJson.json.decodeFromString<List<StoredMismatch>>(file.readText())
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun replace(mismatches: List<StoredMismatch>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(HistoryJson.json.encodeToString(mismatches.distinctBy { it.eid }))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    companion object {
        const val FILE_NAME = "mismatches.json"

        /** Inside `vault-history/`, which backup already excludes. */
        fun defaultFile(context: Context): File = File(context.filesDir, "${HistoryOutbox.DIR_NAME}/$FILE_NAME")
    }
}

/** Device-local switch "Use folder history for sync", on unless the user turned it off. Recording is unaffected. */
class HistoryApplySettings(private val prefs: PreferenceRepository) {
    suspend fun enabled(): Boolean =
        prefs.getEncryptedString(PreferenceRepository.HISTORY_APPLY).first().trim() != OFF

    suspend fun setEnabled(enabled: Boolean) {
        prefs.putEncryptedStrings(PreferenceRepository.HISTORY_APPLY to if (enabled) ON else OFF)
    }

    private companion object {
        const val ON = "on"
        const val OFF = "off"
    }
}

/**
 * Runs every [HistoryApplier] once per sync and keeps the mismatch list current. Never throws: a history that cannot
 * be read leaves sync exactly as it would be without the feature.
 */
class HistoryApplyCoordinator(
    private val identity: DeviceIdentity,
    private val settings: HistoryApplySettings,
    private val mismatches: HistoryMismatchStore,
    private val appliers: List<HistoryApplier>,
) {
    /**
     * Read-only decision for an empty folder scan: true when every live mapped note's newest state event is
     * an applicable bin from another device. Only then is the empty listing the folder agreeing with the
     * history; anything unexplained is a provider glitch and must change nothing.
     *
     * @param liveUids the `noteUid` of every live, non-quarantined FILE_STORAGE mapping.
     */
    suspend fun explainsEmptyScan(access: VaultFileAccess, liveUids: Collection<String>): Boolean {
        if (!settings.enabled()) return false
        // A mapping without an id cannot be explained, and an empty set explains nothing.
        if (liveUids.isEmpty() || liveUids.any { it.isBlank() }) return false
        val history = try {
            HistoryReader(access).read()
        } catch (e: Exception) {
            Log.w(TAG, "Folder history unreadable; an empty scan is not explained: ${e.message}")
            return false
        }
        // The latest state event per id, own events included: they shadow older decisions from other devices.
        val latest = LinkedHashMap<String, HistoryReadResult.Item>()
        for (item in history.items) {
            if (item.event.op !in HistoryOps.NOTE_STATE_OPS) continue
            val id = item.event.id?.takeIf { it.isNotBlank() } ?: continue
            latest[id] = item
        }
        val ownId = identity.deviceId(access.historyScope)
        return liveUids.all { uid ->
            val item = latest[uid] ?: return@all false
            item.applicable && item.event.dev != ownId && item.event.op == HistoryOps.NOTE_BIN
        }
    }

    /** Caller holds the vault lock. True when an applier changed notes, notebooks or tags. */
    suspend fun apply(access: VaultFileAccess, scannedPaths: Set<String>): Boolean {
        if (!settings.enabled()) {
            mismatches.replace(emptyList())
            return false
        }
        val history = try {
            HistoryReader(access).read()
        } catch (e: Exception) {
            Log.w(TAG, "Folder history unreadable; sync continues without it: ${e.message}")
            return false
        }
        val ownId = identity.deviceId(access.historyScope)
        var changed = false
        val found = mutableListOf<StoredMismatch>()
        for (applier in appliers) {
            val outcome = try {
                applier.apply(history, ownId, scannedPaths)
            } catch (e: Exception) {
                Log.w(TAG, "${applier::class.simpleName} failed; its events wait for the next sync: ${e.message}")
                continue
            }
            changed = changed || outcome.changed
            found += outcome.mismatches
        }
        mismatches.replace(found)
        return changed
    }

    private companion object {
        const val TAG = "HistoryApply"
    }
}
