package org.qosp.notes.ui.attachments

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import org.qosp.notes.App
import org.qosp.notes.BuildConfig
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.sync.fs.vault.VaultDocumentTree
import org.qosp.notes.data.sync.fs.vault.VaultPaths
import org.qosp.notes.preferences.PreferenceRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File

fun getAttachmentFilename(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver
            .query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            }
    } catch (e: Throwable) { null }
}

fun Attachment.uri(context: Context) = getAttachmentUri(context, path)

fun Attachment.Companion.fromUri(context: Context, uri: Uri): Attachment {
    val mimeType = context.contentResolver.getType(uri)
    val description = getAttachmentFilename(context, uri) ?: ""
    val type = if (mimeType == null) Attachment.Type.GENERIC else when {
        mimeType.startsWith("image") -> Attachment.Type.IMAGE
        mimeType.startsWith("video") -> Attachment.Type.VIDEO
        mimeType.startsWith("audio") -> Attachment.Type.AUDIO
        else -> Attachment.Type.GENERIC
    }
    return Attachment(type, uri.toString(), description)
}

fun getAttachmentUri(context: Context, path: String, mediaFolder: String = App.MEDIA_FOLDER): Uri? {
    return when {
        VaultPaths.isVaultRelativeAttachment(path) -> resolveVaultAttachmentUri(context, path)
        path.startsWith("content://") || path.startsWith("file://") -> Uri.parse(path)
        else -> {
            runCatching {
                val dir = File(context.filesDir, mediaFolder).also { it.mkdir() }
                FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", File(dir, path))
            }.getOrNull()
        }
    }
}

fun resolveVaultAttachmentUri(context: Context, vaultRelStoredPath: String): Uri? {
    val relative = VaultPaths.toVaultRelative(vaultRelStoredPath)
    val treeUri = runBlocking {
        getKoin().get<PreferenceRepository>()
            .getEncryptedString(PreferenceRepository.STORAGE_LOCATION)
            .first()
            .takeIf { it.isNotBlank() }
            ?.let { Uri.parse(it) }
    } ?: return null
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
    val file = VaultDocumentTree.findByRelativePath(root, relative) ?: return null
    return file.uri
}
