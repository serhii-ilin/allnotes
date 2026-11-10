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
    /** SHA-256 of last verified on-disk file bytes (conflict checks). */
    val baselineRawHash: String = "",
    val preservedYaml: String = "",
    val vaultAttachmentPaths: List<String> = emptyList(),
    val vaultGeneration: Long = 0L,
    val conflictRemoteHash: String? = null,
    val conflictCopyPath: String? = null,
    val pendingRecoveryPath: String? = null,
    /** Vault path when the note file was removed for bin; [org.qosp.notes.data.model.IdMapping.storageUri] is cleared. */
    val binnedVaultPath: String? = null,
    /** Remote vault delete still needed after local bin (e.g. folder was unavailable). */
    val pendingVaultDelete: Boolean = false,
    /** Consecutive complete scans that did not list this note's file; the note is binned on the second. */
    val missingScanCount: Int = 0,
    /** True only when the user binned the note. A bin caused by a missing file never deletes the vault file. */
    val userBinned: Boolean = false,
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
            if (legacy.conflictRemoteHash == null && legacy.conflictCopyPath == null) {
                // Legacy plain on-disk SHA in extras is not a logical baseline.
                return null
            }
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
            val parsed = VaultNoteDocument.parse(rawFileContent)
            val preserved = parsed.frontMatter.preservedYaml
            val fromDisk = VaultAttachments.parseFromBody(parsed.body, relativePath).map { it.path }
            val sortedPaths = (vaultAttachmentPaths + fromDisk).distinct().sorted()
            val logicalHash = VaultSyncImport.canonicalFileHash(
                note,
                relativePath,
                assignedNotebookName,
                preserved,
            )
            return VaultMappingState(
                baselineHash = logicalHash,
                baselineRawHash = VaultContentHash.ofText(rawFileContent),
                preservedYaml = preserved,
                vaultAttachmentPaths = sortedPaths,
                vaultGeneration = vaultGeneration,
                conflictRemoteHash = existing?.conflictRemoteHash,
                conflictCopyPath = existing?.conflictCopyPath,
                binnedVaultPath = existing?.binnedVaultPath,
                pendingVaultDelete = existing?.pendingVaultDelete == true,
                userBinned = existing?.userBinned == true,
            )
        }
    }
}
