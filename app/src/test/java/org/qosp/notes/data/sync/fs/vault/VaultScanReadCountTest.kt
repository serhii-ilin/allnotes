package org.qosp.notes.data.sync.fs.vault

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.sync.fs.StorageBackend
import org.qosp.notes.data.sync.fs.StorageConfig

/**
 * Scan reads each note at most once, and a warm cache (for files older than the racy window) skips
 * the read entirely.
 */
@RunWith(RobolectricTestRunner::class)
class VaultScanReadCountTest {

    /** A file mtime far in the past, so it is already outside the racy window at scan time. */
    private companion object {
        const val OLD_MTIME_MS = 1_000_000L
    }

    /** Counts [VaultFs.readText] calls while reporting a stable (old) mtime for listing. */
    private class OldMtimeVaultFs(private val delegate: InMemoryVaultFs) : VaultFs by delegate {
        var readTextCalls: Int = 0
            private set

        override fun readText(relativePath: String): String? {
            readTextCalls++
            return delegate.readText(relativePath)
        }

        override fun listChildren(relativeDir: String): List<VaultFs.VaultFsEntry> =
            delegate.listChildren(relativeDir).map { it.copy(lastModifiedMs = OLD_MTIME_MS) }
    }

    /** Reports an unknown size, which must never be cached (so every scan re-reads). */
    private class UnknownSizeVaultFs(private val delegate: InMemoryVaultFs) : VaultFs by delegate {
        var readTextCalls: Int = 0
            private set

        override fun readText(relativePath: String): String? {
            readTextCalls++
            return delegate.readText(relativePath)
        }

        override fun listChildren(relativeDir: String): List<VaultFs.VaultFsEntry> =
            delegate.listChildren(relativeDir).map { it.copy(lastModifiedMs = OLD_MTIME_MS, size = -1L) }
    }

    private val notebookDao = mockk<NotebookDao>(relaxed = true)
    private val coordinator = mockk<VaultSyncCoordinator>(relaxed = true)
    private lateinit var savedDispatcher: CoroutineDispatcher

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        coEvery { notebookDao.getById(any()) } returns flowOf(null)
        coEvery { coordinator.currentGeneration() } returns 1L
        coEvery { coordinator.isStaleMapping(any()) } returns false
        savedDispatcher = VaultIo.vaultDispatcher
    }

    @After
    fun tearDown() {
        VaultIo.vaultDispatcher = savedDispatcher
    }

    private fun backend(fs: VaultFs, scheduler: TestCoroutineScheduler): StorageBackend {
        VaultIo.vaultDispatcher = UnconfinedTestDispatcher(scheduler)
        val context = ApplicationProvider.getApplicationContext<Context>()
        return StorageBackend(
            context,
            StorageConfig(Uri.parse("content://test/vault")),
            notebookDao,
            coordinator,
            vaultFs = fs,
        )
    }

    @Test
    fun scanReadsEachNoteOnce_warmCacheReadsNothing() = runTest {
        val fs = OldMtimeVaultFs(InMemoryVaultFs(caseInsensitive = true))
        fs.writeText("A.md", "---\n---\nbody A")
        fs.writeText("B.md", "---\n---\nbody B")

        val first = backend(fs, testScheduler).getAll(emptySet())
        assertNotNull(first)
        assertEquals(2, first!!.size)
        // Cold scan reads each note exactly once.
        assertEquals(2, fs.readTextCalls)

        val second = backend(fs, testScheduler).getAll(emptySet())
        assertNotNull(second)
        assertEquals(2, second!!.size)
        // Warm cache: no additional reads.
        assertEquals(2, fs.readTextCalls)
    }

    @Test
    fun unknownSize_isNeverCached_soEveryScanRereads() = runTest {
        val fs = UnknownSizeVaultFs(InMemoryVaultFs(caseInsensitive = true))
        fs.writeText("A.md", "---\n---\nbody A")

        val first = backend(fs, testScheduler).getAll(emptySet())
        assertNotNull(first)
        assertEquals(1, first!!.size)
        assertEquals(1, fs.readTextCalls)

        val second = backend(fs, testScheduler).getAll(emptySet())
        assertNotNull(second)
        assertEquals(1, second!!.size)
        // Unknown size is never stored, so the second scan re-reads.
        assertEquals(2, fs.readTextCalls)
    }
}
