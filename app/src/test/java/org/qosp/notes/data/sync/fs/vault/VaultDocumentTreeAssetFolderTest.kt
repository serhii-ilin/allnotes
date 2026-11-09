package org.qosp.notes.data.sync.fs.vault

import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultDocumentTreeAssetFolderTest {

    private val paths = setOf(
        "Ttt.md",
        "Ttt/Ttt.md",
        "Work.md",
        "Work/Meeting.md",
        "Projects/Deep/Note.md",
    )

    @Test
    fun assetFolderNote_notIndexedAsTopLevel() {
        assertTrue(VaultDocumentTree.isUnderNoteAssetFolder("Ttt/Ttt/extra.md", paths))
        assertFalse(VaultDocumentTree.isUnderNoteAssetFolder("Ttt/Ttt.md", paths))
    }

    @Test
    fun nestedSubfolderNote_importedWhenSiblingParentNoteExists() {
        assertFalse(VaultDocumentTree.isUnderNoteAssetFolder("Work/Meeting.md", paths))
        assertTrue(VaultDocumentTree.isUnderNoteAssetFolder("Work/photo.jpg", paths))
    }

    @Test
    fun subfolderWithoutSiblingParentNote_isNotAssetFolder() {
        val onlyMeeting = setOf("Projects/Deep/Note.md")
        assertFalse(VaultDocumentTree.isUnderNoteAssetFolder("Projects/Deep/Note.md", onlyMeeting))
        assertFalse(VaultDocumentTree.isUnderNoteAssetFolder("Projects/Orphan/extra.md", onlyMeeting))
    }

    @Test
    fun mappedNestedNote_doesNotFailCompletenessWhenIndexed() {
        val indexed = listOf(
            VaultDocumentTree.IndexedFile("Work/Meeting.md", mockk(relaxed = true)),
            VaultDocumentTree.IndexedFile("Work.md", mockk(relaxed = true)),
        )
        val root = mockk<androidx.documentfile.provider.DocumentFile>(relaxed = true)
        assertTrue(
            VaultDocumentTree.isIndexCompleteForMappedPaths(root, indexed, setOf("Work/Meeting.md")),
        )
    }
}
