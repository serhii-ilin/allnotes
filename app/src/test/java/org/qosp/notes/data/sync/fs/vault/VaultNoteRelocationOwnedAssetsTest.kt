package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Note

class VaultNoteRelocationOwnedAssetsTest {

    @Test
    fun remapNoteAttachments_rewritesAllOwnedVaultRels() {
        val note = Note(
            title = "Tt1",
            attachments = listOf(
                Attachment(Attachment.Type.IMAGE, VaultPaths.vaultRelative("Ttt/a.jpg"), "a.jpg"),
                Attachment(Attachment.Type.IMAGE, VaultPaths.vaultRelative("Ttt/b.jpg"), "b.jpg"),
            ),
        )
        val out = VaultNoteRelocation.remapNoteAttachments(note, "Ttt.md", "Tt1.md")
        assertEquals(
            VaultPaths.vaultRelative("Tt1/a.jpg"),
            out.attachments[0].path,
        )
        assertEquals(
            VaultPaths.vaultRelative("Tt1/b.jpg"),
            out.attachments[1].path,
        )
    }

    @Test
    fun foreignPathInOldFolder_notListedAsOwned() {
        val ownedRel = setOf("Ttt/owned.jpg")
        val foreignRel = VaultPaths.normalize("Ttt/syncthing-conflict.pdf")
        assertFalse(foreignRel in ownedRel)
    }

    @Test
    fun assetDirectoryChangesWithTitle() {
        assertEquals("Ttt", VaultPaths.assetDirectoryForNote("Ttt.md"))
        assertEquals("Tt1", VaultPaths.assetDirectoryForNote("Tt1.md"))
        assertFalse(
            VaultPaths.assetDirectoryForNote("Ttt.md") ==
                VaultPaths.assetDirectoryForNote("Tt1.md"),
        )
    }
}
