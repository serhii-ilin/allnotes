package org.qosp.notes.data.sync.fs.vault

import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Cursor -> entry mapping for [SafVaultFs] listings, driven by a [MatrixCursor].
 *
 * Wiring SAF tree URIs through Robolectric's ContentResolver is not supported (and documentfile
 * 1.0.0's `fromTreeUri` does not honour document URIs), so the mapping is extracted into
 * [SafDirectoryListing.fromCursor] and tested here directly.
 */
@RunWith(RobolectricTestRunner::class)
class SafDirectoryListingTest {

    private val treeUri: Uri = Uri.parse("content://com.test/tree/root-id")

    private fun cursor(): MatrixCursor = MatrixCursor(
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        ),
    )

    @Test
    fun rowsWithNullNameOrIdAreSkipped() {
        val c = cursor()
        c.addRow(arrayOf<Any?>("doc-a", "a.md", "text/markdown", 1000L, 10L))
        c.addRow(arrayOf<Any?>("doc-b", null, "text/markdown", 1000L, 10L))
        c.addRow(arrayOf<Any?>(null, "b.md", "text/markdown", 1000L, 10L))
        c.addRow(arrayOf<Any?>("doc-dir", "sub", DocumentsContract.Document.MIME_TYPE_DIR, null, null))
        val entries = SafDirectoryListing.fromCursor(c, treeUri)
        assertEquals(listOf("a.md", "sub"), entries.map { it.name })
    }

    @Test
    fun metadataIsMapped_withDefaultsWhenNull() {
        val c = cursor()
        c.addRow(arrayOf<Any?>("doc-a", "a.md", "text/markdown", 1234L, 42L))
        c.addRow(arrayOf<Any?>("doc-b", "b.md", "text/plain", null, null))
        val entries = SafDirectoryListing.fromCursor(c, treeUri)
        assertEquals(1234L, entries[0].lastModifiedMs)
        assertEquals(42L, entries[0].size)
        assertEquals(0L, entries[1].lastModifiedMs)
        assertEquals(-1L, entries[1].size)
    }

    @Test
    fun directoryMimeMapsToIsDirectory() {
        val c = cursor()
        c.addRow(arrayOf<Any?>("doc-f", "f.md", "text/markdown", 0L, 1L))
        c.addRow(arrayOf<Any?>("doc-d", "d", DocumentsContract.Document.MIME_TYPE_DIR, 0L, -1L))
        val entries = SafDirectoryListing.fromCursor(c, treeUri)
        assertFalse(entries[0].isDirectory)
        assertTrue(entries[1].isDirectory)
    }

    @Test
    fun documentUriIsBuiltFromTreeUriAndDocId() {
        val c = cursor()
        c.addRow(arrayOf<Any?>("doc-a", "a.md", "text/markdown", 0L, 1L))
        val entries = SafDirectoryListing.fromCursor(c, treeUri)
        assertEquals(
            Uri.parse("content://com.test/tree/root-id/document/doc-a"),
            entries[0].documentUri,
        )
    }
}
