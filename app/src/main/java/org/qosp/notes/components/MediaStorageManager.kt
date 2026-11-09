package org.qosp.notes.components

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.qosp.notes.BuildConfig
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.repo.NoteRepository
import android.provider.OpenableColumns
import org.qosp.notes.data.sync.fs.vault.VaultContentHash
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
        val mime = context.contentResolver.getType(source).orEmpty()
        val type = when {
            mime.startsWith("image/") -> MediaType.IMAGE
            mime.startsWith("video/") -> MediaType.VIDEO
            mime.startsWith("audio/") -> MediaType.AUDIO
            else -> MediaType.IMAGE
        }
        val ext = mime.substringAfter('/', "").takeIf { it.isNotBlank() }?.let { ".$it" }
            ?: type.defaultExtension
        val (destUri, file) = createMediaFile(type, ext) ?: return@withContext null
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
            android.util.Log.e("MediaStorageManager", "persistFromUri failed: ${e.message}", e)
            null
        }
    }

    suspend fun persistAttachmentFromUri(source: Uri): Attachment? = withContext(Dispatchers.IO) {
        val dest = persistFromUri(source) ?: return@withContext null
        val mimeType = context.contentResolver.getType(dest)
        val description = context.contentResolver.query(dest, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        }.orEmpty()
        val type = if (mimeType == null) Attachment.Type.GENERIC else when {
            mimeType.startsWith("image") -> Attachment.Type.IMAGE
            mimeType.startsWith("video") -> Attachment.Type.VIDEO
            mimeType.startsWith("audio") -> Attachment.Type.AUDIO
            else -> Attachment.Type.GENERIC
        }
        Attachment(type, dest.toString(), description)
    }

    enum class MediaType(val defaultExtension: String) {
        IMAGE(".jpg"),
        VIDEO(".mp4"),
        AUDIO(".mp3")
    }
}
