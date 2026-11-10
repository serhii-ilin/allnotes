package org.qosp.notes.data.sync.fs.vault

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * End-to-end [SafVaultFs] over a NESTED tree via a fake [ContentProvider]. Guards the document-id
 * resolution used to walk subfolders: `DocumentsContract.isTreeUri` is true for every
 * `tree/X/...` URI, so `getTreeDocumentId` must never be used on a child URI or the walk recurses
 * forever and overflows the stack.
 */
@RunWith(RobolectricTestRunner::class)
class SafVaultFsNestedTreeTest {

    class FakeVaultProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            // uri: content://authority/tree/<treeId>/document/<docId>/children
            val docId = uri.pathSegments[3]
            val cursor = MatrixCursor(
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
            )
            Tree.childrenOf(docId).forEach { child ->
                cursor.addRow(
                    arrayOf<Any?>(
                        child.docId,
                        child.name,
                        if (child.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "text/markdown",
                        if (child.isDirectory) null else 1_000_000L,
                        if (child.isDirectory) null else child.content.length.toLong(),
                    ),
                )
            }
            return cursor
        }

        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
            // uri: content://authority/tree/<treeId>/document/<docId>
            val docId = uri.pathSegments[3]
            val content = Tree.contentOf(docId)
            val file = File.createTempFile("vault-fs", ".md")
            file.writeText(content)
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return AssetFileDescriptor(pfd, 0, content.length.toLong())
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    data class Node(val docId: String, val name: String, val isDirectory: Boolean, val content: String = "")

    companion object {
        private const val AUTHORITY = "com.test.vault"
        private const val ROOT_DOC_ID = "root"

        private val tree: Map<String, List<Node>> = mapOf(
            ROOT_DOC_ID to listOf(
                Node("$ROOT_DOC_ID/top", "top.md", isDirectory = false, content = "TOP"),
                Node("$ROOT_DOC_ID/a", "a", isDirectory = true),
            ),
            "$ROOT_DOC_ID/a" to listOf(
                Node("$ROOT_DOC_ID/a/mid", "mid.md", isDirectory = false, content = "MID"),
                Node("$ROOT_DOC_ID/a/b", "b", isDirectory = true),
            ),
            "$ROOT_DOC_ID/a/b" to listOf(
                Node("$ROOT_DOC_ID/a/b/n", "n.md", isDirectory = false, content = "N"),
            ),
        )

        private val contents: Map<String, String> = mapOf(
            "$ROOT_DOC_ID/top" to "TOP",
            "$ROOT_DOC_ID/a/mid" to "MID",
            "$ROOT_DOC_ID/a/b/n" to "N",
        )

        object Tree {
            fun childrenOf(docId: String): List<Node> = tree[docId].orEmpty()
            fun contentOf(docId: String): String = contents[docId].orEmpty()
        }
    }

    private fun vaultFs(onWrite: () -> Unit = {}): SafVaultFs {
        Robolectric.buildContentProvider(FakeVaultProvider::class.java).create(AUTHORITY)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val treeUri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_DOC_ID)
        val root = DocumentFile.fromTreeUri(context, treeUri)!!
        return SafVaultFs(context, root, onWrite = onWrite)
    }

    @Test
    fun mutations_markWriteBeforeAndAfter_evenWhenNothingChanges() {
        var marks = 0
        val fs = vaultFs(onWrite = { marks++ })

        assertFalse(fs.delete("a/missing.md"))
        assertEquals(2, marks)

        fs.ensureDirectory("")
        assertEquals(4, marks)
    }

    @Test
    fun nestedTree_terminates_lists_reads_and_indexes() {
        val fs = vaultFs()

        // Must terminate (no StackOverflowError from re-listing the root).
        fs.beginDirectoryCache()

        assertEquals(listOf("n.md"), fs.listChildren("a/b").map { it.name })
        assertEquals("N", fs.readText("a/b/n.md"))
        assertTrue(fs.isFile("a/b/n.md"))
        assertTrue(fs.isDirectory("a/b"))
        assertFalse(fs.isDirectory("a/b/n.md"))

        val indexed = VaultDocumentTree.listNoteCandidates(fs).map { it.relativePath }
        assertEquals(listOf("top.md", "a/mid.md", "a/b/n.md"), indexed)
    }
}
