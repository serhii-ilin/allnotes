package org.qosp.notes.data.sync.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.fs.vault.VaultFolderTestHarness
import org.qosp.notes.data.sync.fs.vault.VaultMappingState

@RunWith(RobolectricTestRunner::class)
class ProcessRemoteActionsRestoreTest {

    @Test
    fun restorePayload_mustNotStayDeleted() = runTest {
        val h = VaultFolderTestHarness()
        h.seedNoteFile("Restore.md", "Restore", "Body")
        val note = Note(id = 3L, title = "Restore", content = "Body", isDeleted = false)
        val mapping = h.mappingWithAttachments(3L, "Restore.md", note, emptyList())
        val state = VaultMappingState.resolve(mapping.extras)!!.copy(binnedVaultPath = "Restore.md")
        val binnedMapping = mapping.copy(
            isDeletedLocally = true,
            storageUri = null,
            extras = VaultMappingState.encode(state),
        )
        val restored = note.copy(isDeleted = false, deletionDate = null)
        val updated = h.backend.updateNote(restored, binnedMapping.copy(isDeletedLocally = false, storageUri = "Restore.md"))
        assertFalse(updated.isDeletedLocally)
        assertEquals("Restore.md", updated.storageUri)
    }
}
