package org.qosp.notes.data.sync.fs.vault.history

import android.os.Build
import kotlinx.coroutines.flow.first
import org.qosp.notes.preferences.PreferenceRepository
import java.util.UUID

/**
 * This install's history identity. The id lives in encrypted preferences, which Android excludes from backup
 * and device transfer, so a file-level clone of the notes folder never carries an id to another install.
 *
 * The id and the sequence are kept per notes folder: switching folders starts a separate log, and switching
 * back resumes the id and `seq` that folder's log already has.
 */
class DeviceIdentity(
    private val prefs: PreferenceRepository,
    private val model: String = Build.MODEL ?: "Android",
    private val defaultScope: String = "",
) {
    /** The stored id for [scope], created on first use. */
    suspend fun deviceId(scope: String = defaultScope): String {
        val key = scoped(PreferenceRepository.HISTORY_DEVICE_ID, scope)
        val stored = prefs.getEncryptedString(key).first().trim()
        if (stored.isNotEmpty()) return stored
        val created = UUID.randomUUID().toString()
        prefs.putEncryptedStrings(key to created)
        return created
    }

    /** `Pixel 9 · a1b2c3`: the phone model plus enough of the id to tell two installs apart. */
    fun label(id: String): String = "$model · ${id.take(6)}"

    /** Highest `seq` this install ever wrote in [scope]; 0 before the first flush. */
    suspend fun lastWrittenSeq(scope: String = defaultScope): Long =
        prefs.getEncryptedString(scoped(PreferenceRepository.HISTORY_LAST_SEQ, scope)).first()
            .trim().toLongOrNull() ?: 0L

    suspend fun setLastWrittenSeq(seq: Long, scope: String = defaultScope) {
        prefs.putEncryptedStrings(scoped(PreferenceRepository.HISTORY_LAST_SEQ, scope) to seq.toString())
    }

    /** UTC day ("YYYY-MM-DD") of the last retention prune in [scope]; blank before the first one. */
    suspend fun lastPrunedDay(scope: String = defaultScope): String =
        prefs.getEncryptedString(scoped(PreferenceRepository.HISTORY_LAST_PRUNE_DAY, scope)).first().trim()

    suspend fun setLastPrunedDay(day: String, scope: String = defaultScope) {
        prefs.putEncryptedStrings(scoped(PreferenceRepository.HISTORY_LAST_PRUNE_DAY, scope) to day)
    }

    /** Switches [scope] to a fresh id with an empty sequence, after this install found lines it never wrote. */
    suspend fun rotate(scope: String = defaultScope): String {
        val created = UUID.randomUUID().toString()
        prefs.putEncryptedStrings(
            scoped(PreferenceRepository.HISTORY_DEVICE_ID, scope) to created,
            scoped(PreferenceRepository.HISTORY_LAST_SEQ, scope) to "0",
        )
        return created
    }

    /** Older installs kept one unscoped id; keep reading it when no folder scope is known. */
    private fun scoped(base: String, scope: String): String =
        if (scope.isBlank()) base else "$base@$scope"

}
