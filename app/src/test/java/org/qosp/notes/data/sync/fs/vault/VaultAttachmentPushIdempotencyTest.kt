package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Note

/**
 * Simulates debounced pushes where Room still holds a content:// attachment but disk already
 * has the vault file and mapping records the vault path.
 */
class VaultAttachmentPushIdempotencyTest {

    private val path = "Ttt.md"

    @Test
    fun mergeNoteAfterPush_dirty_keepsBody_butUpgradesAttachmentPath() {
        val privateUri = "content://media/external/images/42"
        val vaultStored = VaultPaths.vaultRelative("Ttt/snap.jpg")
        val local = Note(
            id = 1L,
            title = "Ttt",
            content = "same",
            attachments = listOf(Attachment(Attachment.Type.IMAGE, privateUri, "snap.jpg")),
        )
        val disk = Note(
            title = "Ttt",
            content = "same",
            attachments = listOf(Attachment(Attachment.Type.IMAGE, vaultStored, "snap.jpg")),
        )
        val baseline = VaultSyncImport.canonicalFileHash(
            local.copy(attachments = emptyList()),
            path,
            null,
            "",
        )
        val state = VaultMappingState(
            baselineHash = baseline,
            vaultAttachmentPaths = listOf(vaultStored),
        )
        assertTrue(VaultSyncImport.isLocallyDirty(local, path, state, null))
        val merged = VaultSyncImport.mergeNoteAfterPush(local, disk, state, path, null)
        assertEquals("same", merged.content)
        assertEquals(vaultStored, merged.attachments.single().path)
    }
}
