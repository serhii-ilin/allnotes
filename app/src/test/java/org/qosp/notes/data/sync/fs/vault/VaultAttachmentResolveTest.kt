package org.qosp.notes.data.sync.fs.vault

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Test
import org.qosp.notes.ui.utils.vault.VaultMarkwonContext

class VaultAttachmentResolveTest {

    private val tree = mockk<VaultTreeAccess>(relaxed = true)

    @Test
    fun resolveVaultStoredPath_missingFile_returnsNull() {
        every { tree.resolveFileUri(any()) } returns null
        assertNull(resolveVaultStoredPath(tree, VaultPaths.vaultRelative("Ttt/missing.jpg")))
    }

    @Test
    fun resolveVaultStoredPath_malformedStoredPath_returnsNull() {
        assertNull(resolveVaultStoredPath(tree, "vaultrel:"))
        assertNull(resolveVaultStoredPath(tree, "not-a-vault-path"))
    }

    @Test
    fun resolveMarkwonImage_missingVaultContext_returnsNull() {
        assertNull(resolveMarkwonImage(tree, null, "Ttt/x.jpg"))
    }

    @Test
    fun resolveMarkwonImage_deletedFolderTarget_returnsNull() {
        every { tree.resolveFileUri(any()) } returns null
        val ctx = VaultMarkwonContext("Tt1.md")
        assertNull(resolveMarkwonImage(tree, ctx, "Tt1/x.jpg"))
    }

    @Test
    fun resolveMarkwonImage_traversalRejected_returnsNull() {
        every { tree.resolveFileUri(any()) } returns null
        val ctx = VaultMarkwonContext("Work/Note.md")
        assertNull(resolveMarkwonImage(tree, ctx, "../secret.jpg"))
    }

    @Test
    fun resolveVaultStoredPath_vaultrelLiteral_resolvesWhenTreeNull() {
        every { tree.resolveFileUri("Ttt/x.jpg") } returns null
        assertNull(resolveVaultStoredPath(tree, "vaultrel:Ttt/x.jpg"))
    }
}
