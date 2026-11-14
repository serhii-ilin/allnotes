package org.qosp.notes.components

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.qosp.notes.BuildConfig
import android.provider.OpenableColumns
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.data.sync.fs.vault.AttachmentExtensions
import org.qosp.notes.data.sync.fs.vault.VaultContentHash
import org.qosp.notes.data.sync.fs.vault.VaultPaths
import org.qosp.notes.ui.attachments.getAttachmentUri
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest

class MediaStorageManager(
    private val context: Context,
    private val noteRepository: NoteRepository,
    private val mediaFolder: String,
) {
    private val directory get() = File(context.filesDir, mediaFolder)
        .also { it.mkdir() }

    @Volatile
    var lastPersistError: String? = null

    fun listMediaFiles(): List<String> {
        return directory
            .list()
            .orEmpty()
            .toList()
    }

    fun deleteAllMedia() {
        directory.deleteRecursively()
    }

    suspend fun cleanUpStorage() = runCatching {
        val filesUsed = noteRepository
            .getAll()
            .first()
            .flatMap { it.attachments }
            .map { it.path }

        val files = directory
            .list()
            .orEmpty()

        for (file in files) {
            if (getAttachmentUri(context, file, mediaFolder).toString() !in filesUsed) {
                File(directory, file).delete()
            }
        }
    }

    /***
     * Creates a media file in local storage.
     *
     * @return The file's [Uri] and [File] objects.
     */
    suspend fun createMediaFile(type: MediaType, extension: String = type.defaultExtension): Pair<Uri, File>? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val prefix = when (type) {
                    MediaType.IMAGE -> "img_"
                    MediaType.AUDIO -> "audio_"
                    MediaType.VIDEO -> "video_"
                    MediaType.GENERIC -> "file_"
                }

                val file = File.createTempFile(prefix, extension, directory)
                FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", file) to file
            }.getOrNull()
        }
    }

    /**
     * Copy bytes from [source] into a new private file; returns the content [Uri] only after
     * a non-empty verify. Does not delete [source].
     */
    suspend fun persistFromUri(source: Uri): Uri? = withContext(Dispatchers.IO) {
        lastPersistError = null
        val mime = context.contentResolver.getType(source).orEmpty()
        val attachmentType = when {
            mime.startsWith("image/") -> Attachment.Type.IMAGE
            mime.startsWith("video/") -> Attachment.Type.VIDEO
            mime.startsWith("audio/") -> Attachment.Type.AUDIO
            else -> Attachment.Type.GENERIC
        }
        val type = when (attachmentType) {
            Attachment.Type.IMAGE -> MediaType.IMAGE
            Attachment.Type.VIDEO -> MediaType.VIDEO
            Attachment.Type.AUDIO -> MediaType.AUDIO
            Attachment.Type.GENERIC -> MediaType.GENERIC
        }
        val originalName = queryDisplayName(source)
        val ext = AttachmentExtensions.preferredExtension(mime.ifBlank { null }, attachmentType)
            .ifBlank { type.defaultExtension }
        val dest = when {
            originalName.isNotBlank() -> {
                val desired = AttachmentExtensions.ensureFileNameHasExtension(
                    originalName,
                    mime.ifBlank { null },
                    attachmentType,
                )
                createNamedMediaFile(type, desired.ifBlank { "file$ext" })
            }
            else -> createMediaFile(type, ext)
        } ?: return@withContext null
        val destUri = dest.first
        val file = dest.second
        try {
            var expectedHash: String? = null
            context.contentResolver.openInputStream(source)?.use { raw ->
                val digest = MessageDigest.getInstance("SHA-256")
                val input = DigestInputStream(raw, digest)
                val buffer = ByteArray(8 * 1024)
                var read = input.read(buffer)
                if (read <= 0) throw IOException("empty source")
                while (input.read(buffer) != -1) { /* drain for hash */ }
                expectedHash = VaultContentHash.ofBytes(digest.digest())
            } ?: throw IOException("cannot read source")
            context.contentResolver.openInputStream(source)?.use { input ->
                context.contentResolver.openOutputStream(destUri)?.use { output ->
                    input.copyTo(output)
                } ?: throw IOException("cannot write dest")
            } ?: throw IOException("cannot re-read source")
            val writtenHash = file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val wrapped = DigestInputStream(input, digest)
                val buffer = ByteArray(8 * 1024)
                while (wrapped.read(buffer) != -1) { /* drain */ }
                VaultContentHash.ofBytes(digest.digest())
            }
            if (writtenHash != expectedHash) {
                file.delete()
                throw IOException("verify failed")
            }
            destUri
        } catch (e: Exception) {
            if (file.exists()) file.delete()
            lastPersistError = e.message ?: "Failed to save file"
            android.util.Log.e("MediaStorageManager", "persistFromUri failed: ${e.message}", e)
            null
        }
    }

    suspend fun persistAttachmentFromUri(source: Uri): Attachment? = withContext(Dispatchers.IO) {
        val dest = persistFromUri(source) ?: return@withContext null
        val mimeType = context.contentResolver.getType(dest)
        val description = queryDisplayName(dest).ifBlank {
            queryDisplayName(source)
        }
        val type = if (mimeType == null) Attachment.Type.GENERIC else when {
            mimeType.startsWith("image") -> Attachment.Type.IMAGE
            mimeType.startsWith("video") -> Attachment.Type.VIDEO
            mimeType.startsWith("audio") -> Attachment.Type.AUDIO
            else -> Attachment.Type.GENERIC
        }
        Attachment(type, dest.toString(), description)
    }

    private fun queryDisplayName(uri: Uri): String =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        }.orEmpty()

    internal fun uniqueMediaFileNameForTest(desired: String): String = uniqueMediaFileName(desired)

    internal fun mediaDirectoryForTest(): File = directory

    private fun uniqueMediaFileName(desired: String): String {
        val sanitized = VaultPaths.sanitizeFileName(desired)
        if (!File(directory, sanitized).exists()) return sanitized
        val dot = sanitized.lastIndexOf('.')
        val base = if (dot > 0) sanitized.substring(0, dot) else sanitized
        val ext = if (dot > 0) sanitized.substring(dot) else ""
        var i = 2
        while (File(directory, "$base-$i$ext").exists()) i++
        return "$base-$i$ext"
    }

    private suspend fun createNamedMediaFile(type: MediaType, desiredFileName: String): Pair<Uri, File>? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val name = uniqueMediaFileName(desiredFileName)
                val file = File(directory, name)
                file.createNewFile()
                FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", file) to file
            }.getOrNull()
        }
    }

    enum class MediaType(val defaultExtension: String) {
        IMAGE(".jpg"),
        VIDEO(".mp4"),
        AUDIO(".m4a"),
        GENERIC(".bin"),
    }
}
