package org.qosp.notes.data.sync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.data.sync.fs.vault.VaultAttachments
import org.qosp.notes.data.sync.fs.vault.VaultPaths
import org.qosp.notes.data.sync.fs.vault.VaultSyncImport

class VaultCreateAttachmentBaselineTest {

    @Test
    fun newNote_createRemote_recordsVaultPathsFromExportedMarkdown() {
        val body = "Hello\n\n![photo](Shot/photo.jpg)"
        val paths = VaultAttachments.parseFromBody(body, "Work/Shot.md").map { it.path }
        assertEquals(1, paths.size)
        assertTrue(paths.single().startsWith(VaultPaths.VAULT_REL_PREFIX))
        assertEquals(VaultPaths.vaultRelative("Work/Shot/photo.jpg"), paths.single())
    }

    @Test
    fun existingNote_updateRemote_recordsVaultPathsFromExportedMarkdown() {
        val body = "![new](Daily/new.jpg)"
        val paths = VaultAttachments.parseFromBody(body, "Notes/Daily.md").map { it.path }
        assertEquals(VaultPaths.vaultRelative("Notes/Daily/new.jpg"), paths.single())
    }
}
