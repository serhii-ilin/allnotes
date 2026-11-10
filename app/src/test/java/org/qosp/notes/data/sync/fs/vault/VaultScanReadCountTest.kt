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
 * Scan reads each note at most once, and a warm cache skips the read entirely.
 */
@RunWith(RobolectricTestRunner::class)
class VaultScanReadCountTest {

    /** Counts [VaultFs.readText] calls while delegating everything else to an [InMemoryVaultFs]. */
    private class CountingVaultFs(private val delegate: InMemoryVaultFs) : VaultFs by delegate {
        var readTextCalls: Int = 0
            private set

        override fun readText(relativePath: String): String? {
            readTextCalls++
            return delegate.readText(relativePath)
        }
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

    @Test
    fun scanReadsEachNoteOnce_warmCacheReadsNothing() = runTest {
        VaultIo.vaultDispatcher = UnconfinedTestDispatcher(testScheduler)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fs = CountingVaultFs(InMemoryVaultFs(caseInsensitive = true))
        val backend = StorageBackend(
            context,
            StorageConfig(Uri.parse("content://test/vault")),
            notebookDao,
            coordinator,
            vaultFs = fs,
        )
        fs.writeText("A.md", "---\n---\nbody A")
        fs.writeText("B.md", "---\n---\nbody B")

        val first = backend.getAll(emptySet())
        assertNotNull(first)
        assertEquals(2, first!!.size)
        // Cold scan reads each note exactly once.
        assertEquals(2, fs.readTextCalls)

        val second = backend.getAll(emptySet())
        assertNotNull(second)
        assertEquals(2, second!!.size)
        // Warm cache: no additional reads.
        assertEquals(2, fs.readTextCalls)
    }
}
