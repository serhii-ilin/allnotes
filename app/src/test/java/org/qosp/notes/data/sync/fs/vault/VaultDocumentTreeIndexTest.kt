package org.qosp.notes.data.sync.fs.vault

import androidx.documentfile.provider.DocumentFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.data.sync.fs.vault.VaultDocumentTree.IndexedFile

class VaultDocumentTreeIndexTest {

    @After
    fun tearDown() = unmockkObject(VaultDocumentTree)

    @Test
    fun mappedPathVisibleButNotIndexed_failsCompletenessCheck() {
        mockkObject(VaultDocumentTree)
        val root = mockk<DocumentFile>(relaxed = true)
        val found = mockk<DocumentFile> { every { isFile } returns true }
        every { VaultDocumentTree.findByRelativePath(root, "Work/Note.md") } returns found
        val indexed = listOf(IndexedFile("Other/Note.md", mockk(relaxed = true)))
        assertFalse(
            VaultDocumentTree.isIndexCompleteForMappedPaths(root, indexed, setOf("Work/Note.md")),
        )
    }

    @Test
    fun mappedPathAbsentOnDisk_passesCompleteness() {
        mockkObject(VaultDocumentTree)
        val root = mockk<DocumentFile>(relaxed = true)
        every { VaultDocumentTree.findByRelativePath(root, "Gone.md") } returns null
        assertTrue(
            VaultDocumentTree.isIndexCompleteForMappedPaths(root, emptyList(), setOf("Gone.md")),
        )
    }
}
