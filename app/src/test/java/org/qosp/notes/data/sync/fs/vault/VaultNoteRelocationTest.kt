package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Note

class VaultNoteRelocationTest {

    @Test
    fun remapNoteAttachments_movesVaultRelPrefix() {
        val note = Note(
            title = "Tt1",
            content = "body",
            attachments = listOf(
                Attachment(
                    Attachment.Type.IMAGE,
                    VaultPaths.vaultRelative("Ttt/x.jpg"),
                    "x.jpg",
                ),
            ),
        )
        val remapped = VaultNoteRelocation.remapNoteAttachments(note, "Ttt.md", "Tt1.md")
        assertEquals(VaultPaths.vaultRelative("Tt1/x.jpg"), remapped.attachments.single().path)
    }

    @Test
    fun materializePathsFromPush_pairsSingleLocalPrivateWithDiskVault() {
        val local = listOf(
            Attachment(Attachment.Type.IMAGE, "content://pick/1", "IMG_2024.jpg"),
        )
        val disk = listOf(
            Attachment(Attachment.Type.IMAGE, VaultPaths.vaultRelative("Ttt/IMG_2024.jpg"), "IMG_2024.jpg"),
        )
        val out = VaultNoteRelocation.materializePathsFromPush(local, disk)
        assertTrue(VaultPaths.isVaultRelativeAttachment(out.single().path))
        assertEquals(VaultPaths.vaultRelative("Ttt/IMG_2024.jpg"), out.single().path)
    }

    @Test
    fun applyBaselineVaultPathsIfPending_matchesBySanitizedDescription() {
        val local = listOf(
            Attachment(Attachment.Type.IMAGE, "content://pick/1", "vacation_photo.jpg"),
        )
        val disk = listOf(
            Attachment(Attachment.Type.IMAGE, VaultPaths.vaultRelative("Note/vacation_photo.jpg"), "vacation_photo.jpg"),
        )
        val state = VaultMappingState(
            baselineHash = "abc",
            vaultAttachmentPaths = listOf(VaultPaths.vaultRelative("Note/vacation_photo.jpg")),
        )
        val paired = VaultNoteRelocation.materializePathsFromPush(local, disk)
        val out = VaultNoteRelocation.applyBaselineVaultPathsIfPending(paired, state, disk)
        assertEquals(VaultPaths.vaultRelative("Note/vacation_photo.jpg"), out.single().path)
    }
}
