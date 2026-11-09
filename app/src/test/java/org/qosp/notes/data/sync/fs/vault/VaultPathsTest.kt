package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultPathsTest {
    @Test
    fun normalize_rejectsParentTraversal() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultPaths.normalizeNotePath("Work/../secret.md")
        }
    }

    @Test
    fun assetDirectory_besideNote() {
        assertEquals("Work/Meeting", VaultPaths.assetDirectoryForNote("Work/Meeting.md"))
    }

    @Test
    fun vaultRelative_prefix() {
        assertEquals("vaultrel:Work/a.jpg", VaultPaths.vaultRelative("Work/a.jpg"))
        assertEquals("Work/a.jpg", VaultPaths.toVaultRelative("vaultrel:Work/a.jpg"))
    }

    @Test
    fun uniqueName_avoidsCollision() {
        val name = VaultPaths.uniqueName("photo.jpg", setOf("photo.jpg"))
        assertEquals("photo-2.jpg", name)
    }

    @Test
    fun linkResolver_bareImageInParentFolder() {
        val abs = VaultLinkResolver.resolve("Work/Meeting.md", "photo.jpg")
        assertEquals("Work/photo.jpg", abs)
    }
}
