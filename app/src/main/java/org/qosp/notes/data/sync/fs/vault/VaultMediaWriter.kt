package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.ui.attachments.getAttachmentUri
import java.io.IOException

object VaultMediaWriter {
    fun copyAttachmentToVault(
        context: Context,
        root: DocumentFile,
        noteRelativePath: String,
        attachment: Attachment,
        existingNames: MutableSet<String>,
    ): Attachment {
        if (VaultPaths.isVaultRelativeAttachment(attachment.path)) {
            val rel = VaultPaths.toVaultRelative(attachment.path)
            existingNames.add(VaultPaths.fileName(rel))
            return attachment
        }
        val assetDir = VaultPaths.assetDirectoryForNote(noteRelativePath)
        val dir = VaultDocumentTree.ensureDirectory(root, assetDir)
        val sourceUri = resolveSource(context, root, attachment) ?: throw IOException("Cannot open attachment source")
        val sourceHash = VaultStreamHashes.hashUri(context, sourceUri)
        findReusableFile(context, dir, sourceHash, existingNames)?.let { fileName ->
            val vaultPath = VaultPaths.normalize("$assetDir/$fileName")
            return attachment.copy(
                path = VaultPaths.vaultRelative(vaultPath),
                description = attachment.description.ifBlank { fileName },
                fileName = "",
            )
        }
        val suggested = attachment.description.ifBlank { attachment.fileName }.ifBlank { "file" }
        val sanitized = VaultPaths.sanitizeFileName(suggested)
        val fileName = if (sanitized !in existingNames) {
            sanitized
        } else {
            dir.findFile(sanitized)?.let { existing ->
                if (VaultStreamHashes.hashDocumentFile(context, existing) == sourceHash) {
                    existingNames.add(sanitized)
                    val vaultPath = VaultPaths.normalize("$assetDir/$sanitized")
                    return attachment.copy(
                        path = VaultPaths.vaultRelative(vaultPath),
                        description = attachment.description.ifBlank { sanitized },
                        fileName = "",
                    )
                }
            }
            VaultPaths.uniqueName(suggested, existingNames)
        }
        existingNames.add(fileName)
        val mime = context.contentResolver.getType(sourceUri) ?: "application/octet-stream"
        val target = dir.createFile(mime, fileName.substringBeforeLast('.'))
            ?: throw IOException("Cannot create $fileName in vault")
        if (target.name != fileName) target.renameTo(fileName)
        val finalFile = dir.findFile(fileName) ?: target
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            context.contentResolver.openOutputStream(finalFile.uri)?.use { output ->
                input.copyTo(output)
            } ?: throw IOException("Cannot write vault asset")
        } ?: throw IOException("Cannot read attachment source")
        val writtenHash = VaultStreamHashes.hashDocumentFile(context, finalFile)
        if (writtenHash != sourceHash) {
            throw IOException("Attachment byte verify failed for $fileName")
        }
        val vaultPath = VaultPaths.normalize("$assetDir/$fileName")
        return attachment.copy(
            path = VaultPaths.vaultRelative(vaultPath),
            description = attachment.description.ifBlank { fileName },
            fileName = "",
        )
    }

    private fun findReusableFile(
        context: Context,
        dir: DocumentFile,
        sourceHash: String,
        existingNames: MutableSet<String>,
    ): String? {
        for (child in dir.listFiles().orEmpty()) {
            if (!child.isFile) continue
            val name = child.name ?: continue
            if (VaultStreamHashes.hashDocumentFile(context, child) != sourceHash) continue
            existingNames.add(name)
            return name
        }
        return null
    }

    private fun resolveSource(context: Context, root: DocumentFile, attachment: Attachment): Uri? {
        return when {
            VaultPaths.isVaultRelativeAttachment(attachment.path) -> {
                val rel = VaultPaths.toVaultRelative(attachment.path)
                VaultDocumentTree.findByRelativePath(root, rel)?.uri
            }
            else -> getAttachmentUri(context, attachment.path)
        }
    }

    fun openVaultAttachmentUri(file: DocumentFile): Uri = file.uri

    fun copyAttachmentToVaultFs(
        context: Context,
        fs: VaultFs,
        noteRelativePath: String,
        attachment: Attachment,
        existingNames: MutableSet<String>,
    ): Attachment {
        if (VaultPaths.isVaultRelativeAttachment(attachment.path)) {
            val rel = VaultPaths.toVaultRelative(attachment.path)
            existingNames.add(VaultPaths.fileName(rel))
            return attachment
        }
        val assetDir = VaultPaths.assetDirectoryForNote(noteRelativePath)
        fs.ensureDirectory(assetDir)
        val sourceUri = resolveLocalAttachmentUri(context, attachment)
            ?: throw IOException("Cannot open attachment source")
        val sourceHash = VaultStreamHashes.hashUri(context, sourceUri)
        findReusableFileFs(fs, assetDir, sourceHash, existingNames)?.let { fileName ->
            val vaultPath = VaultPaths.normalize("$assetDir/$fileName")
            return attachment.copy(
                path = VaultPaths.vaultRelative(vaultPath),
                description = attachment.description.ifBlank { fileName },
                fileName = "",
            )
        }
        val suggested = attachment.description.ifBlank { attachment.fileName }.ifBlank { "file" }
        val sanitized = VaultPaths.sanitizeFileName(suggested)
        val fileName = if (sanitized !in existingNames) {
            sanitized
        } else {
            val existingPath = VaultPaths.normalize("$assetDir/$sanitized")
            if (fs.isFile(existingPath) &&
                VaultStreamHashes.hashVaultFsFile(fs, existingPath) == sourceHash
            ) {
                existingNames.add(sanitized)
                val vaultPath = VaultPaths.normalize("$assetDir/$sanitized")
                return attachment.copy(
                    path = VaultPaths.vaultRelative(vaultPath),
                    description = attachment.description.ifBlank { sanitized },
                    fileName = "",
                )
            }
            VaultPaths.uniqueName(suggested, existingNames)
        }
        existingNames.add(fileName)
        val mime = context.contentResolver.getType(sourceUri) ?: "application/octet-stream"
        val targetRel = VaultPaths.normalize("$assetDir/$fileName")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            val out = fs.openWrite(targetRel, mime)
                ?: throw IOException("Cannot open vault output for $fileName")
            out.use { output -> input.copyTo(output) }
        } ?: throw IOException("Cannot read attachment source")
        val writtenHash = VaultStreamHashes.hashVaultFsFile(fs, targetRel)
        if (writtenHash != sourceHash) {
            fs.delete(targetRel)
            throw IOException("Attachment byte verify failed for $fileName")
        }
        return attachment.copy(
            path = VaultPaths.vaultRelative(targetRel),
            description = attachment.description.ifBlank { fileName },
            fileName = "",
        )
    }

    private fun findReusableFileFs(
        fs: VaultFs,
        assetDir: String,
        sourceHash: String,
        existingNames: MutableSet<String>,
    ): String? {
        for (child in fs.listChildren(assetDir)) {
            if (child.isDirectory) continue
            if (VaultStreamHashes.hashVaultFsFile(fs, child.relativePath) != sourceHash) continue
            existingNames.add(child.name)
            return child.name
        }
        return null
    }

    private fun resolveLocalAttachmentUri(context: Context, attachment: Attachment): Uri? =
        getAttachmentUri(context, attachment.path)
}
