package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultPathsTest {
    @Test
    fun normalize_rejectsParentTraversal() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultPaths.normalizeNotePath("Work/../secret.md")
        }
    }

    @Test
    fun normalizeNotePath_allowsEllipsisInTitle() {
        assertEquals("Wait....md", VaultPaths.normalizeNotePath("Wait....md"))
    }

    @Test
    fun normalizeNotePath_allowsDoubleDotInFilename() {
        assertEquals("v1..2.md", VaultPaths.normalizeNotePath("v1..2.md"))
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
    fun sanitizeFileName_stripsIllegalChars() {
        val sanitized = VaultPaths.sanitizeFileName("vacation: photo?.jpg")
        assertFalse(sanitized.contains(':'))
        assertFalse(sanitized.contains('?'))
    }

    @Test
    fun normalize_collapsesRepeatedSlashes() {
        assertEquals("Work/Note.md", VaultPaths.normalize("Work//Note.md"))
    }

    @Test
    fun linkResolver_bareImageInParentFolder() {
        val abs = VaultLinkResolver.resolve("Work/Meeting.md", "photo.jpg")
        assertEquals("Work/photo.jpg", abs)
    }

    @Test
    fun isConflictCopyPath_matchesOnlyConflictNames() {
        assertTrue(VaultPaths.isConflictCopyPath(VaultPaths.conflictPath("Work/Plan.md", 1700000000L)))
        assertTrue(VaultPaths.isConflictCopyPath("Plan (conflict 12).txt"))
        assertTrue(VaultPaths.isConflictCopyPath("Work/Plan (conflict 12).MD"))
        assertFalse(VaultPaths.isConflictCopyPath("Plan.md"))
        assertFalse(VaultPaths.isConflictCopyPath("Plan (conflict).md"))
        assertFalse(VaultPaths.isConflictCopyPath("Plan (conflict 12) notes.md"))
        assertFalse(VaultPaths.isConflictCopyPath("Plan (conflict 12)/Inner.md"))
    }
}
