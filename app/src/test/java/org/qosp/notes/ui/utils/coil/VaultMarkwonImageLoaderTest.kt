package org.qosp.notes.ui.utils.coil

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.qosp.notes.data.sync.fs.vault.VaultPaths
import org.qosp.notes.data.sync.fs.vault.VaultTreeAccess
import org.qosp.notes.ui.utils.vault.VaultMarkwonContext

class VaultMarkwonImageLoaderTest {

    private val tree = mockk<VaultTreeAccess>(relaxed = true)

    @Test
    fun blankDestination_returnsNull() {
        assertNull(VaultMarkwonImageLoader.resolveLoadData(null, null, tree))
        assertNull(VaultMarkwonImageLoader.resolveLoadData("", null, tree))
    }

    @Test
    fun vaultrel_missingFile_returnsNull_notRawString() {
        every { tree.resolveFileUri(any()) } returns null
        val data = VaultMarkwonImageLoader.resolveLoadData(
            VaultPaths.vaultRelative("Ttt/missing.jpg"),
            VaultMarkwonContext("Ttt.md"),
            tree,
        )
        assertNull(data)
    }

    @Test
    fun relativePath_missingTree_returnsNull_notRelativeString() {
        every { tree.resolveFileUri(any()) } returns null
        assertNull(
            VaultMarkwonImageLoader.resolveLoadData(
                "Ttt/x.jpg",
                VaultMarkwonContext("Ttt.md"),
                tree,
            ),
        )
    }

    @Test
    fun relativePath_withoutVaultContext_returnsNull() {
        assertNull(VaultMarkwonImageLoader.resolveLoadData("photo.jpg", null, tree))
    }

    @Test
    fun httpUrl_passesThrough() {
        assertEquals(
            "https://example.com/x.png",
            VaultMarkwonImageLoader.resolveLoadData("https://example.com/x.png", null, tree),
        )
    }
}
