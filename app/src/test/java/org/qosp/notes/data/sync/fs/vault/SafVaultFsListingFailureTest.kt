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
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.sync.fs.StorageBackend
import org.qosp.notes.data.sync.fs.StorageConfig
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/** A provider that fails (null cursor) the children query of one folder must fail the scan, not look empty. */
@RunWith(RobolectricTestRunner::class)
class SafVaultFsListingFailureTest {

    class FailingProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val docId = uri.pathSegments[3]
            if (docId == failingDocId) return null
            val omitTop = docId == ROOT && uri.lastPathSegment == "children" && omitTopOnceFromRootQueries > 0
            if (omitTop) omitTopOnceFromRootQueries--
            val cursor = MatrixCursor(
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
            )
            when (docId) {
                ROOT -> {
                    if (!omitTop) {
                        cursor.addRow(arrayOf<Any?>("$ROOT/top", "top.md", "text/markdown", 1_000_000L, 3L))
                    }
                    cursor.addRow(arrayOf<Any?>("$ROOT/a", "a", DocumentsContract.Document.MIME_TYPE_DIR, null, null))
                }
                "$ROOT/a" -> cursor.addRow(arrayOf<Any?>("$ROOT/a/n", "n.md", "text/markdown", 1_000_000L, 1L))
            }
            return cursor
        }

        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
            val file = File.createTempFile("vault-fs", ".md")
            file.writeText("body")
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return AssetFileDescriptor(pfd, 0, file.length())
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    companion object {
        private const val AUTHORITY = "com.test.failing"
        private const val ROOT = "root"
        @Volatile var failingDocId: String? = null
        /** Number of upcoming root listings that silently drop top.md (a partial listing, not an error). */
        @Volatile var omitTopOnceFromRootQueries = 0
    }

    @After
    fun reset() {
        failingDocId = null
        omitTopOnceFromRootQueries = 0
    }

    private fun vaultFs(): Pair<Context, SafVaultFs> {
        Robolectric.buildContentProvider(FailingProvider::class.java).create(AUTHORITY)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = DocumentFile.fromTreeUri(context, DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT))!!
        return context to SafVaultFs(context, root)
    }

    @Test
    fun snapshot_failsWhenOneSubfolderQueryFails() {
        failingDocId = "$ROOT/a"
        val (_, fs) = vaultFs()
        assertThrows(IOException::class.java) { fs.beginDirectoryCache() }
    }

    @Test
    fun uncachedListing_failsWhenSubfolderQueryFails() {
        val (_, fs) = vaultFs()
        // The root lists fine, then the provider fails for the subfolder.
        assertEquals(listOf("n.md"), fs.listChildren("a").map { it.name })
        failingDocId = "$ROOT/a"
        assertThrows(IOException::class.java) { fs.listChildren("a") }
    }

    @Test
    fun uncachedListing_failsWhenParentQueryFails() {
        val (_, fs) = vaultFs()
        failingDocId = ROOT
        assertThrows(IOException::class.java) { fs.listChildren("a") }
    }

    @Test
    fun getAll_returnsNullInsteadOfPartialList() = runTest {
        val (context, fs) = vaultFs()
        val notebookDao = mockk<NotebookDao>(relaxed = true)
        coEvery { notebookDao.getById(any()) } returns flowOf(null)
        val coordinator = mockk<VaultSyncCoordinator>(relaxed = true)
        val backend = StorageBackend(
            context,
            StorageConfig(location = Uri.parse("content://test/vault")),
            notebookDao,
            coordinator,
            vaultFs = fs,
        )
        assertNotNull(backend.getAll(emptySet()))
        failingDocId = "$ROOT/a"
        assertNull(backend.getAll(emptySet()))
    }

    @Test
    fun getAll_failsWhenSnapshotOmitsAMappedFileThatExistsLive() = runTest {
        val (context, fs) = vaultFs()
        val notebookDao = mockk<NotebookDao>(relaxed = true)
        coEvery { notebookDao.getById(any()) } returns flowOf(null)
        val backend = StorageBackend(
            context,
            StorageConfig(location = Uri.parse("content://test/vault")),
            notebookDao,
            mockk<VaultSyncCoordinator>(relaxed = true),
            vaultFs = fs,
        )
        // Only the snapshot's root listing omits top.md; a live lookup still finds it.
        omitTopOnceFromRootQueries = 1
        assertNull(backend.getAll(setOf("top.md")))
        assertNotNull(backend.getAll(setOf("top.md")))
    }
}
