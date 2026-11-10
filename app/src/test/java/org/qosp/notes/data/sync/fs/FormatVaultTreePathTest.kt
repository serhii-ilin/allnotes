package org.qosp.notes.data.sync.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatVaultTreePathTest {

    private val external = "com.android.externalstorage.documents"
    private val label = "Internal storage"

    @Test
    fun `primary volume path uses internal storage label`() {
        assertEquals("Internal storage/Documents/Notes", formatVaultTreePath(external, "primary:Documents/Notes", label))
    }

    @Test
    fun `primary volume root is just the label`() {
        assertEquals("Internal storage", formatVaultTreePath(external, "primary:", label))
    }

    @Test
    fun `sd card path uses volume id`() {
        assertEquals("1234-ABCD/Notes", formatVaultTreePath(external, "1234-ABCD:Notes", label))
    }

    @Test
    fun `sd card root is just the volume id`() {
        assertEquals("1234-ABCD", formatVaultTreePath(external, "1234-ABCD:", label))
    }

    @Test
    fun `raw document id returns the remainder for any authority`() {
        assertEquals(
            "/storage/emulated/0/Download/Notes",
            formatVaultTreePath("com.android.providers.downloads.documents", "raw:/storage/emulated/0/Download/Notes", label),
        )
    }

    @Test
    fun `unknown authority returns null`() {
        assertNull(formatVaultTreePath("com.example.provider", "abc:def", label))
    }

    @Test
    fun `null inputs return null`() {
        assertNull(formatVaultTreePath(external, null, label))
        assertNull(formatVaultTreePath(null, "primary:Notes", label))
        assertNull(formatVaultTreePath(null, null, label))
    }

    @Test
    fun `external storage folder name is the last path segment`() {
        assertEquals("Notes", formatExternalStorageFolderName("primary:Documents/Notes", label))
        assertEquals("Notes", formatExternalStorageFolderName("1234-ABCD:Notes", label))
    }

    @Test
    fun `external storage volume root falls back to label or volume id`() {
        assertEquals("Internal storage", formatExternalStorageFolderName("primary:", label))
        assertEquals("1234-ABCD", formatExternalStorageFolderName("1234-ABCD:", label))
    }
}
