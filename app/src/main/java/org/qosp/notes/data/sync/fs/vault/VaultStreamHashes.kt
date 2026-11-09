package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

object VaultStreamHashes {
    fun hashUri(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { input ->
            return VaultContentHash.ofBytes(input.readBytes())
        } ?: throw IOException("Cannot read $uri")
    }

    fun hashDocumentFile(context: Context, file: DocumentFile): String? {
        if (!file.isFile) return null
        return runCatching { hashUri(context, file.uri) }.getOrNull()
    }
}
