package org.qosp.notes.data.sync.fs.vault

/**
 * Encodes [org.qosp.notes.data.model.IdMapping.extras] for FILE_STORAGE:
 * - baseline file SHA-256 hash
 * - optional one-shot conflict record so repeated syncs do not spawn more copies
 */
object VaultMappingExtras {
    private const val CONFLICT_REMOTE = "conflictRemote="
    private const val CONFLICT_PATH = "conflictPath="

    data class Parsed(
        val baselineHash: String?,
        val conflictRemoteHash: String?,
        val conflictCopyPath: String?,
    )

    fun parse(extras: String?): Parsed {
        if (extras.isNullOrBlank()) return Parsed(null, null, null)
        if (!extras.contains(CONFLICT_REMOTE)) {
            return Parsed(extras.trim(), null, null)
        }
        var baseline: String? = null
        var conflictRemote: String? = null
        var conflictPath: String? = null
        extras.split('|').forEach { part ->
            val p = part.trim()
            when {
                p.startsWith(CONFLICT_REMOTE) -> conflictRemote = p.removePrefix(CONFLICT_REMOTE)
                p.startsWith(CONFLICT_PATH) -> conflictPath = p.removePrefix(CONFLICT_PATH)
                p.isNotEmpty() -> baseline = p
            }
        }
        return Parsed(baseline, conflictRemote, conflictPath)
    }

    fun baselineOnly(hash: String): String = hash

    fun withConflict(baselineHash: String?, remoteHash: String, conflictPath: String): String {
        val base = baselineHash?.takeIf { it.isNotEmpty() } ?: ""
        return listOf(base, "$CONFLICT_REMOTE$remoteHash", "$CONFLICT_PATH$conflictPath")
            .filter { it.isNotEmpty() }
            .joinToString("|")
    }

    fun baselineHash(extras: String?): String? = parse(extras).baselineHash
}
