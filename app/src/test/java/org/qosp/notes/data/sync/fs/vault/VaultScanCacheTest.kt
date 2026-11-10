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

    @Test
    fun hitOnSameMtimeAndSize() {
        val c = cache()
        c.save(mapOf("a.md" to VaultScanCache.Entry(lastModifiedMs = 1000L, size = 5L, raw = "hello")))
        assertEquals("hello", c.lookup("a.md", 1000L, 5L))
    }

    @Test
    fun missOnChangedMtimeOrSize() {
        val c = cache()
        c.save(mapOf("a.md" to VaultScanCache.Entry(lastModifiedMs = 1000L, size = 5L, raw = "hello")))
        assertNull(c.lookup("a.md", 2000L, 5L))
        assertNull(c.lookup("a.md", 1000L, 6L))
        assertNull(c.lookup("missing.md", 1000L, 5L))
    }

    @Test
    fun savePrunesRemovedPaths() {
        val c = cache()
        c.save(
            mapOf(
                "a.md" to VaultScanCache.Entry(1000L, 1L, "a"),
                "b.md" to VaultScanCache.Entry(1000L, 1L, "b"),
            ),
        )
        c.save(mapOf("a.md" to VaultScanCache.Entry(1000L, 1L, "a")))
        assertEquals(setOf("a.md"), c.load().keys)
        assertNull(c.lookup("b.md", 1000L, 1L))
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
        c.save(mapOf("a.md" to VaultScanCache.Entry(1000L, 5L, "hello")))
        assertTrue(cacheFile().exists())
        assertTrue(cacheFile().length() > 0)
        assertEquals(listOf("scan.json"), tmp.root.list()!!.toList())
        // Content survives a reload from the (renamed) file.
        assertEquals("hello", cache().lookup("a.md", 1000L, 5L))
    }
}
