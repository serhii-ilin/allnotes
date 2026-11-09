package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.ui.attachments.getAttachmentUri
import org.qosp.notes.ui.attachments.resolveVaultAttachmentUri
import java.io.IOException

object VaultMediaWriter {
    fun copyAttachmentToVault(
        context: Context,
        root: DocumentFile,
        noteRelativePath: String,
        attachment: Attachment,
        existingNames: MutableSet<String>,
    ): Attachment {
        val assetDir = VaultPaths.assetDirectoryForNote(noteRelativePath)
        VaultDocumentTree.ensureDirectory(root, assetDir)
        val dir = VaultDocumentTree.ensureDirectory(root, assetDir)
        val sourceUri = resolveSource(context, attachment) ?: throw IOException("Cannot open attachment source")
        val suggested = attachment.description.ifBlank { attachment.fileName }.ifBlank { "file" }
        val fileName = VaultPaths.uniqueName(suggested, existingNames)
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
        val vaultPath = VaultPaths.normalize("$assetDir/$fileName")
        return attachment.copy(
            path = VaultPaths.vaultRelative(vaultPath),
            description = attachment.description.ifBlank { fileName },
            fileName = "",
        )
    }

    private fun resolveSource(context: Context, attachment: Attachment): Uri? {
        return when {
            VaultPaths.isVaultRelativeAttachment(attachment.path) ->
                resolveVaultAttachmentUri(context, attachment.path)
            else -> getAttachmentUri(context, attachment.path)
        }
    }

    fun openVaultAttachmentUri(file: DocumentFile): Uri = file.uri
}
