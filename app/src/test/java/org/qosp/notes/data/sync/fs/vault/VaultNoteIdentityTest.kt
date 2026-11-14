package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.preferences.CloudService

class VaultNoteIdentityTest {

    private fun note(id: Long, body: String) = Note(id = id, title = "N$id", content = body)

    private fun mapping(id: Long, path: String, uid: String = "") = IdMapping(
        localNoteId = id,
        remoteNoteId = null,
        provider = CloudService.FILE_STORAGE,
        extras = VaultMappingState.encode(VaultMappingState(baselineHash = "h", noteUid = uid)),
        isDeletedLocally = false,
        storageUri = path,
    )

    private fun file(path: String, body: String, id: String? = null, modified: Long = 1L): SyncNote {
        val idLine = id?.let { "allnotes-id: \"$it\"\n" }.orEmpty()
        return SyncNote(
            id = 0,
            idStr = path,
            content = "---\n${idLine}title: \"T\"\n---\n$body\n",
            title = "T",
            lastModified = modified,
        )
    }

    @Test
    fun sameIdAtNewPath_isTheSameNote() {
        val resolution = VaultNoteIdentity.resolve(
            listOf(note(1, "Body")),
            listOf(mapping(1, "Old.md", uid = "u1")),
            listOf(file("Folder/New.md", "Body", id = "u1")),
        )
        val match = resolution.matches.single()
        assertEquals(1L, match.localNoteId)
        assertEquals("Folder/New.md", match.remote.idStr)
        assertTrue(match.moved)
        assertEquals(setOf("Folder/New.md"), resolution.skipImportPaths)
    }

    @Test
    fun twoFilesWithOneId_keepTheMappedFileAndLeaveTheOtherForImport() {
        val resolution = VaultNoteIdentity.resolve(
            listOf(note(1, "Body")),
            listOf(mapping(1, "Plan.md", uid = "u1")),
            listOf(file("Plan.md", "Body", id = "u1"), file("Plan-copy.md", "Body changed", id = "u1", modified = 9)),
        )
        assertEquals(1, resolution.matches.size)
        assertEquals("Plan.md", resolution.matches.single().remote.idStr)
        assertTrue("a copied file must be imported, not skipped", resolution.skipImportPaths.isEmpty())
    }

    @Test
    fun uniqueBody_retargetsANoteThatHasNoId() {
        val resolution = VaultNoteIdentity.resolve(
            listOf(note(1, "Only this")),
            listOf(mapping(1, "Old.md")),
            listOf(file("Renamed.md", "Only this")),
        )
        assertEquals("Renamed.md", resolution.matches.single().remote.idStr)
        assertTrue(resolution.matches.single().moved)
    }

    @Test
    fun twoFilesWithTheSameBody_areNotMatched() {
        val resolution = VaultNoteIdentity.resolve(
            listOf(note(1, "Same")),
            listOf(mapping(1, "Old.md")),
            listOf(file("A.md", "Same"), file("B.md", "Same")),
        )
        assertNull(resolution.remoteFor(1))
        assertTrue(resolution.skipImportPaths.isEmpty())
    }

    @Test
    fun blankBody_isNotMatched() {
        val resolution = VaultNoteIdentity.resolve(
            listOf(note(1, "   ")),
            listOf(mapping(1, "Old.md")),
            listOf(file("A.md", "   ")),
        )
        assertNull(resolution.remoteFor(1))
    }
}
