package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Note
import java.io.IOException

/**
 * When a vault-backed note’s relative path changes, asset folders follow
 * [VaultPaths.assetDirectoryForNote] ({basename}/ beside the note file).
 */
object VaultNoteRelocation {
    fun remapNoteAttachments(note: Note, fromNotePath: String, toNotePath: String): Note {
        val oldDir = VaultPaths.assetDirectoryForNote(fromNotePath)
        val newDir = VaultPaths.assetDirectoryForNote(toNotePath)
        if (oldDir == newDir) return note
        val prefix = "$oldDir/"
        val updated = note.attachments.map { att ->
            if (!VaultPaths.isVaultRelativeAttachment(att.path)) return@map att
            val rel = VaultPaths.toVaultRelative(att.path)
            if (!rel.startsWith(prefix)) return@map att
            val tail = rel.removePrefix(prefix)
            val newPath = VaultPaths.normalize("$newDir/$tail")
            att.copy(
                path = VaultPaths.vaultRelative(newPath),
                description = att.description.ifBlank { VaultPaths.fileName(newPath) },
            )
        }
        return note.copy(attachments = updated)
    }

    /**
     * Moves files listed in [ownedVaultRelPaths] from the old asset directory into the new one.
     * Files not owned are left untouched. Deletes successfully moved sources; removes [oldAssetDir]
     * only when empty afterward.
     */
    fun relocateOwnedAssets(
        context: Context,
        root: DocumentFile,
        fromNotePath: String,
        toNotePath: String,
        ownedVaultRelPaths: Set<String>,
    ) {
        val oldDir = VaultPaths.assetDirectoryForNote(fromNotePath)
        val newDir = VaultPaths.assetDirectoryForNote(toNotePath)
        if (oldDir == newDir || ownedVaultRelPaths.isEmpty()) return
        VaultDocumentTree.ensureDirectory(root, newDir)
        val ownedRel = ownedVaultRelPaths.map { VaultPaths.toVaultRelative(it) }.toSet()
        for (rel in ownedRel) {
            if (!rel.startsWith("$oldDir/")) continue
            val fileName = VaultPaths.fileName(rel)
            val targetRel = VaultPaths.normalize("$newDir/$fileName")
            moveAssetIfNeeded(context, root, rel, targetRel)
        }
        pruneEmptyAssetDirectory(context, root, oldDir, ownedRel)
    }

    private fun moveAssetIfNeeded(
        context: Context,
        root: DocumentFile,
        sourceRel: String,
        targetRel: String,
    ) {
        if (sourceRel == targetRel) return
        val source = VaultDocumentTree.findByRelativePath(root, sourceRel) ?: return
        val existingTarget = VaultDocumentTree.findByRelativePath(root, targetRel)
        if (existingTarget != null) {
            val sameBytes = VaultStreamHashes.hashDocumentFile(context, source) ==
                VaultStreamHashes.hashDocumentFile(context, existingTarget)
            if (sameBytes) {
                DocumentsContract.deleteDocument(context.contentResolver, source.uri)
            }
            return
        }
        val parent = VaultPaths.parentPath(targetRel)
        val dir = if (parent.isEmpty()) root else VaultDocumentTree.ensureDirectory(root, parent)
        val targetName = VaultPaths.fileName(targetRel)
        val mime = context.contentResolver.getType(source.uri) ?: "application/octet-stream"
        val created = dir.createFile(mime, targetName.substringBeforeLast('.'))
            ?: throw IOException("Cannot create $targetRel")
        if (created.name != targetName) created.renameTo(targetName)
        val target = dir.findFile(targetName) ?: created
        val expectedHash = VaultStreamHashes.hashDocumentFile(context, source)
            ?: throw IOException("Cannot hash $sourceRel")
        context.contentResolver.openInputStream(source.uri)?.use { input ->
            context.contentResolver.openOutputStream(target.uri)?.use { output ->
                input.copyTo(output)
            } ?: throw IOException("Cannot write $targetRel")
        } ?: throw IOException("Cannot read $sourceRel")
        val writtenHash = VaultStreamHashes.hashDocumentFile(context, target)
        if (writtenHash != expectedHash) {
            DocumentsContract.deleteDocument(context.contentResolver, target.uri)
            throw IOException("Verify failed for $targetRel")
        }
        DocumentsContract.deleteDocument(context.contentResolver, source.uri)
    }

    private fun pruneEmptyAssetDirectory(
        context: Context,
        root: DocumentFile,
        oldAssetDir: String,
        ownedRelUnderOld: Set<String>,
    ) {
        val dir = VaultDocumentTree.ensureDirectory(root, oldAssetDir)
        val children = dir.listFiles() ?: return
        for (child in children) {
            val name = child.name ?: continue
            val rel = VaultPaths.normalize("$oldAssetDir/$name")
            if (rel in ownedRelUnderOld && child.isFile) {
                DocumentsContract.deleteDocument(context.contentResolver, child.uri)
            }
        }
        val remaining = dir.listFiles()?.filter { it.name != null }.orEmpty()
        if (remaining.isEmpty()) {
            DocumentsContract.deleteDocument(context.contentResolver, dir.uri)
        }
    }

    /**
     * After [materializePathsFromPush], align still-private attachments with mapping baseline paths
     * that are present on disk (debounced push before Room picked up vaultrel).
     */
    fun applyBaselineVaultPathsIfPending(
        attachments: List<Attachment>,
        mappingState: VaultMappingState?,
        fromDisk: List<Attachment>,
    ): List<Attachment> {
        if (mappingState == null) return attachments
        val onDisk = fromDisk.filter { VaultPaths.isVaultRelativeAttachment(it.path) }.map { it.path }.toSet()
        return attachments.map { att ->
            if (VaultPaths.isVaultRelativeAttachment(att.path)) return@map att
            val suggested = VaultPaths.sanitizeFileName(
                att.description.ifBlank { att.path.substringAfterLast('/') },
            )
            val stored = mappingState.vaultAttachmentPathSet.firstOrNull { candidate ->
                candidate in onDisk &&
                    VaultPaths.fileName(VaultPaths.toVaultRelative(candidate))
                        .equals(suggested, ignoreCase = true)
            } ?: return@map att
            att.copy(
                path = stored,
                description = att.description.ifBlank {
                    VaultPaths.fileName(VaultPaths.toVaultRelative(stored))
                },
                fileName = "",
            )
        }
    }

    fun deleteOwnedAssets(
        context: Context,
        root: DocumentFile,
        noteRelativePath: String,
        ownedVaultRelPaths: Set<String>,
    ) {
        val assetDir = VaultPaths.assetDirectoryForNote(noteRelativePath)
        val ownedRel = ownedVaultRelPaths.map { VaultPaths.toVaultRelative(it) }.toSet()
        for (rel in ownedRel) {
            if (!rel.startsWith("$assetDir/")) continue
            VaultDocumentTree.findByRelativePath(root, rel)?.let { file ->
                DocumentsContract.deleteDocument(context.contentResolver, file.uri)
            }
        }
        VaultDocumentTree.findDirectory(root, assetDir)?.let { dir ->
            val remaining = dir.listFiles()?.filter { it.name != null }.orEmpty()
            if (remaining.isEmpty()) {
                DocumentsContract.deleteDocument(context.contentResolver, dir.uri)
            }
        }
    }

    /** Ordered pairing of local private attachments with disk vault paths after push. */
    fun materializePathsFromPush(local: List<Attachment>, fromDisk: List<Attachment>): List<Attachment> {
        val diskVault = fromDisk.filter { VaultPaths.isVaultRelativeAttachment(it.path) }.toMutableList()
        return local.map { att ->
            if (VaultPaths.isVaultRelativeAttachment(att.path)) return@map att
            val suggested = VaultPaths.sanitizeFileName(
                att.description.ifBlank { att.path.substringAfterLast('/') },
            )
            val matchIdx = diskVault.indexOfFirst { disk ->
                val diskName = VaultPaths.fileName(VaultPaths.toVaultRelative(disk.path))
                diskName.equals(suggested, ignoreCase = true) ||
                    VaultPaths.sanitizeFileName(att.description) == diskName
            }
            val match = matchIdx.takeIf { it >= 0 }?.let { diskVault.removeAt(it) }
            if (match != null) {
                att.copy(path = match.path, description = match.description, fileName = "")
            } else {
                att
            }
        }
    }
}
