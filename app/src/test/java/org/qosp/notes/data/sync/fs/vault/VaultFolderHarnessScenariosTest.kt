package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fake-folder integration scenarios (harness-backed).
 */
@RunWith(RobolectricTestRunner::class)
class VaultFolderHarnessScenariosTest {

    @Test
    fun harness_seedAndScan_listsNote() = runTest {
        val h = VaultFolderTestHarness()
        h.seedNoteFile("Hello.md", "Hello", "World")
        val remote = h.backend.getAll(emptySet()).orEmpty()
        assertEquals(1, remote.size)
        assertEquals("Hello.md", remote.first().idStr)
    }

    @Test
    fun blocker1_renameNote_movesOwnedAttachments_andUpdatesPaths() = runTest {
        val h = VaultFolderTestHarness()
        val a = VaultPaths.vaultRelative("Old/a.png")
        val b = VaultPaths.vaultRelative("Old/b.png")
        val note = h.noteWithVaultAttachments(1L, "Old", "Body", listOf(a, b))
        h.seedNoteFile("Old.md", "Old", "Body", listOf("a.png" to byteArrayOf(1), "b.png" to byteArrayOf(2)))
        val mapping = h.mappingWithAttachments(1L, "Old.md", note, listOf(a, b))
        val renamed = note.copy(title = "New")
        val remapped = VaultNoteRelocation.remapNoteAttachments(renamed, "Old.md", "New.md")
        val updated = h.backend.updateNote(remapped, mapping)
        assertEquals("New.md", updated.storageUri)
        assertTrue(h.vaultFs.isFile("New/a.png"))
        assertTrue(h.vaultFs.isFile("New/b.png"))
        assertFalse(h.vaultFs.isFile("Old.md"))
        assertFalse(h.vaultFs.isFile("Old/a.png"))
        val state = VaultMappingState.resolve(updated.extras)!!
        assertTrue(state.vaultAttachmentPathSet.all { it.contains("New/") })
    }

    @Test
    fun onlyBinnedNote_activeMappedPathsEmpty_scanCanSucceed() = runTest {
        val h = VaultFolderTestHarness()
        h.seedNoteFile("Binned.md", "Binned", "gone")
        val scan = h.backend.getAll(emptySet())
        assertEquals(1, scan.orEmpty().size)
    }

}
