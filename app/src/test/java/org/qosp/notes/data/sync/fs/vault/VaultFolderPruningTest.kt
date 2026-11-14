package org.qosp.notes.data.sync.fs.vault

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.sync.fs.StorageBackend
import org.qosp.notes.data.sync.fs.StorageConfig
import org.qosp.notes.preferences.CloudService
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VaultFolderPruningTest {

    /** Lists the first [emptyListings] queries of [flaky] as empty, like a provider returning a short cursor. */
    private class FlakyListingFs(
        private val inner: InMemoryVaultFs,
        private val flaky: String,
        private var emptyListings: Int,
    ) : VaultFs by inner {
        override fun listChildren(relativeDir: String): List<VaultFs.VaultFsEntry> {
            if (relativeDir == flaky && emptyListings > 0) {
                emptyListings--
                return emptyList()
            }
            return inner.listChildren(relativeDir)
        }

        /** Like a SAF directory delete: everything inside goes too. */
        override fun delete(relativePath: String): Boolean {
            inner.snapshotPaths().filter { it.startsWith("$relativePath/") }.forEach { inner.delete(it) }
            return inner.delete(relativePath)
        }
    }

    private fun backend(fs: VaultFs, prune: Boolean): StorageBackend {
        val notebookDao = mockk<NotebookDao>(relaxed = true)
        coEvery { notebookDao.getById(any()) } returns flowOf(null)
        return StorageBackend(
            ApplicationProvider.getApplicationContext(),
            StorageConfig(Uri.parse("content://test/vault")),
            notebookDao,
            mockk(relaxed = true),
            mockk<VaultSyncCoordinator>(relaxed = true),
            vaultFs = fs,
            pruneEmptyFolders = prune,
        )
    }

    private fun mapping(path: String) = IdMapping(
        localNoteId = 1L,
        remoteNoteId = null,
        provider = CloudService.FILE_STORAGE,
        extras = null,
        isDeletedLocally = false,
        storageUri = path,
    )

    @Test
    fun pruningOff_folderStaysAfterItsLastNoteMovesToTheBin() = runTest {
        val fs = InMemoryVaultFs()
        fs.writeText("Work/a.md", "body")
        backend(fs, prune = false).trashNote(mapping("Work/a.md"))
        assertTrue(fs.isDirectory("Work"))
    }

    @Test
    fun pruningOn_emptyFolderIsRemoved() = runTest {
        val fs = InMemoryVaultFs()
        fs.writeText("Work/a.md", "body")
        backend(fs, prune = true).trashNote(mapping("Work/a.md"))
        assertFalse(fs.isDirectory("Work"))
    }

    @Test
    fun pruningOn_firstListingEmptyButSecondShowsAFile_folderIsKept() = runTest {
        val inner = InMemoryVaultFs()
        inner.writeText("Work/a.md", "body")
        inner.writeText("Work/b.md", "other")
        val fs = FlakyListingFs(inner, flaky = "Work", emptyListings = 1)
        backend(fs, prune = true).trashNote(mapping("Work/a.md"))
        assertTrue(inner.isFile("Work/b.md"))
    }
}
