package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Persistent scan cache round-trip + error handling (pure JVM, no Android).
 */
class VaultScanCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cacheFile(): File = File(tmp.root, "scan.json")

    private fun cache(): VaultScanCache = VaultScanCache(cacheFile())

    private fun entry(
        mtime: Long,
        size: Long,
        raw: String,
        scanTime: Long = 10_000L,
    ) = VaultScanCache.Entry(lastModifiedMs = mtime, size = size, raw = raw, scanTimeMs = scanTime)

    @Test
    fun hitOnSameMtimeAndSize_whenOlderThanRacyWindow() {
        val c = cache()
        c.save(mapOf("a.md" to entry(mtime = 1000L, size = 5L, raw = "hello")))
        assertEquals("hello", c.lookup("a.md", 1000L, 5L))
    }

    @Test
    fun missOnChangedMtimeOrSize() {
        val c = cache()
        c.save(mapOf("a.md" to entry(mtime = 1000L, size = 5L, raw = "hello")))
        assertNull(c.lookup("a.md", 2000L, 5L))
        assertNull(c.lookup("a.md", 1000L, 6L))
        assertNull(c.lookup("missing.md", 1000L, 5L))
    }

    @Test
    fun entryWithUnknownMetadata_isNeverReused() {
        val c = cache()
        c.save(
            mapOf(
                "zero-mtime.md" to entry(mtime = 0L, size = 5L, raw = "a"),
                "negative-size.md" to entry(mtime = 1000L, size = -1L, raw = "b"),
            ),
        )
        assertNull(c.lookup("zero-mtime.md", 0L, 5L))
        assertNull(c.lookup("negative-size.md", 1000L, -1L))
    }

    @Test
    fun entryCachedWithinRacyWindow_isNotReused() {
        val c = cache()
        // File mtime 9000, scanned at 9000: still inside the 3s racy window.
        c.save(mapOf("a.md" to entry(mtime = 9000L, size = 5L, raw = "hello", scanTime = 9000L)))
        assertNull(c.lookup("a.md", 9000L, 5L))
    }

    @Test
    fun entryOlderThanRacyWindow_isReused() {
        val c = cache()
        // File mtime 5000, scanned at 10000: older than 3s.
        c.save(mapOf("a.md" to entry(mtime = 5000L, size = 5L, raw = "hello", scanTime = 10_000L)))
        assertEquals("hello", c.lookup("a.md", 5000L, 5L))
    }

    @Test
    fun savePrunesRemovedPaths() {
        val c = cache()
        c.save(
            mapOf(
                "a.md" to entry(1000L, 1L, "a"),
                "b.md" to entry(1000L, 1L, "b"),
            ),
        )
        c.save(mapOf("a.md" to entry(1000L, 1L, "a")))
        assertEquals(setOf("a.md"), c.load().keys)
    }

    @Test
    fun saveSkipsWriteWhenEntriesUnchanged() {
        val c = cache()
        val e = entry(1000L, 5L, "hello")
        c.save(mapOf("a.md" to e))
        // Corrupt the on-disk file behind the cache's back.
        cacheFile().writeText("{ garbage")
        // Saving the SAME entries must be a no-op, leaving the (corrupt) file untouched.
        c.save(mapOf("a.md" to e))
        assertTrue(cacheFile().readText().contains("garbage"))
    }

    @Test
    fun corruptFile_isIgnored() {
        cacheFile().writeText("{ not valid json !!!")
        val c = cache()
        assertTrue(c.load().isEmpty())
        assertNull(c.lookup("a.md", 1L, 1L))
    }

    @Test
    fun missingFile_loadsEmpty() {
        assertTrue(cache().load().isEmpty())
    }

    @Test
    fun save_writesAtomically_leavesNoTempFile() {
        val c = cache()
        c.save(mapOf("a.md" to entry(1000L, 5L, "hello")))
        assertTrue(cacheFile().exists())
        assertTrue(cacheFile().length() > 0)
        assertEquals(listOf("scan.json"), tmp.root.list()!!.toList())
        // Content survives a reload from the (renamed) file.
        assertEquals("hello", cache().lookup("a.md", 1000L, 5L))
    }
}
