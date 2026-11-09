package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.security.MessageDigest

object VaultStreamHashes {
    fun hashUri(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                md.update(buffer, 0, read)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        } ?: throw IOException("Cannot read $uri")
    }

    fun hashDocumentFile(context: Context, file: DocumentFile): String? {
        if (!file.isFile) return null
        return runCatching { hashUri(context, file.uri) }.getOrNull()
    }
}
