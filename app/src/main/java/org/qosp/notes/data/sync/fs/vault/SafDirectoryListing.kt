package org.qosp.notes.data.sync.fs.vault

import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Maps a child-documents [Cursor] (queried via [DocumentsContract.buildChildDocumentsUriUsingTree])
 * into entries. Extracted from [SafVaultFs] so the cursor mapping can be unit-tested with a
 * [android.database.MatrixCursor] without a SAF provider.
 */
internal object SafDirectoryListing {

    data class Entry(
        val name: String,
        val documentUri: Uri,
        val isDirectory: Boolean,
        val lastModifiedMs: Long,
        val size: Long,
    )

    fun fromCursor(cursor: Cursor, treeUri: Uri): List<Entry> {
        val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val mtimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        val out = mutableListOf<Entry>()
        while (cursor.moveToNext()) {
            val docId = (if (idCol >= 0) cursor.getString(idCol) else null) ?: continue
            val name = (if (nameCol >= 0) cursor.getString(nameCol) else null) ?: continue
            val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
            val lastModified = if (mtimeCol >= 0 && !cursor.isNull(mtimeCol)) cursor.getLong(mtimeCol) else 0L
            val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L
            out.add(
                Entry(
                    name = name,
                    documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    lastModifiedMs = lastModified,
                    size = size,
                ),
            )
        }
        return out
    }
}
