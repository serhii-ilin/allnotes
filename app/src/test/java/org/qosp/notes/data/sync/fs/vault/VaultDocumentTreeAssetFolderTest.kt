package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultDocumentTreeAssetFolderTest {

    @Test
    fun assetFolderNote_notIndexedAsTopLevel() {
        assertTrue(VaultDocumentTree.isUnderNoteAssetFolder("Ttt/Ttt/extra.md"))
        assertFalse(VaultDocumentTree.isUnderNoteAssetFolder("Ttt/Ttt.md"))
    }
}
