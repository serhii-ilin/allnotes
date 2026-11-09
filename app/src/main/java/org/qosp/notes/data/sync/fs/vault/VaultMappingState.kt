package org.qosp.notes.data.sync.fs.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Structured FILE_STORAGE [org.qosp.notes.data.model.IdMapping.extras].
 * Legacy plain-hash strings are still accepted via [VaultMappingExtras.parse].
 */
@Serializable
data class VaultMappingState(
    val baselineHash: String,
    val preservedYaml: String = "",
    val vaultAttachmentPaths: List<String> = emptyList(),
    val vaultGeneration: Long = 0L,
    val conflictRemoteHash: String? = null,
    val conflictCopyPath: String? = null,
    val pendingRecoveryPath: String? = null,
) {
    val vaultAttachmentPathSet: Set<String> = vaultAttachmentPaths.toSet()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(state: VaultMappingState): String = json.encodeToString(state)

        fun decode(extras: String?): VaultMappingState? {
            if (extras.isNullOrBlank()) return null
            return runCatching {
                if (extras.startsWith("{")) json.decodeFromString<VaultMappingState>(extras) else null
            }.getOrNull()
        }

        fun resolve(extras: String?): VaultMappingState? {
            decode(extras)?.let { return it }
            val legacy = VaultMappingExtras.parse(extras)
            val hash = legacy.baselineHash ?: extras?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return VaultMappingState(
                baselineHash = hash,
                conflictRemoteHash = legacy.conflictRemoteHash,
                conflictCopyPath = legacy.conflictCopyPath,
            )
        }

        fun fromDiskSync(
            rawFileContent: String,
            note: org.qosp.notes.data.model.Note,
            relativePath: String,
            vaultAttachmentPaths: List<String>,
            vaultGeneration: Long,
            assignedNotebookName: String? = null,
            existing: VaultMappingState? = null,
        ): VaultMappingState {
            val preserved = VaultNoteDocument.parse(rawFileContent).frontMatter.preservedYaml
            val sortedPaths = vaultAttachmentPaths.distinct().sorted()
            val logicalHash = VaultSyncImport.canonicalFileHash(
                note,
                relativePath,
                assignedNotebookName,
                preserved,
            )
            return VaultMappingState(
                baselineHash = logicalHash,
                preservedYaml = preserved,
                vaultAttachmentPaths = sortedPaths,
                vaultGeneration = vaultGeneration,
                conflictRemoteHash = existing?.conflictRemoteHash,
                conflictCopyPath = existing?.conflictCopyPath,
            )
        }
    }
}
