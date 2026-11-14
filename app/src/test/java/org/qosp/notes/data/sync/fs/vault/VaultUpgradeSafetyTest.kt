package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.data.sync.core.Success
import org.qosp.notes.preferences.CloudService
import org.robolectric.RobolectricTestRunner

/** An install upgraded from the old app must not sync into a folder the user has not confirmed. */
@RunWith(RobolectricTestRunner::class)
class VaultUpgradeSafetyTest {

    private val legacyUri = "content://com.android.externalstorage.documents/document/primary%3ANotes%2Fold.md"

    private suspend fun NoteRepositoryVaultHarness.legacyMapping(noteId: Long) {
        idMappingDao.insert(
            IdMapping(
                localNoteId = noteId,
                remoteNoteId = null,
                provider = CloudService.FILE_STORAGE,
                extras = null,
                isDeletedLocally = false,
                storageUri = legacyUri,
            ),
        )
    }

    @Test
    fun unconfirmedFolder_syncChangesNothing() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        h.vaultConfiguredAt = ""
        h.vaultFs.writeText("Remote.md", "---\ntitle: Remote\n---\nremote body")
        val id = h.insertLocal(Note(title = "Local", content = "local body"))
        val filesBefore = h.vaultFs.snapshotPaths().toSet()

        assertEquals(Success, h.repository.syncNotes())
        h.flushRemoteActions()

        assertEquals(filesBefore, h.vaultFs.snapshotPaths().toSet())
        assertEquals(1, h.localNoteCount())
        assertEquals(0, h.mappingCountFor(id))
        assertEquals(0, h.idMappingDao.getCountByCloudService(CloudService.FILE_STORAGE))

        h.vaultConfiguredAt = "1"
        h.syncNotes()
        assertEquals(1, h.mappingCountFor(id))
        assertEquals(2, h.localNoteCount())
    }

    @Test
    fun legacyMapping_bodyMatchWithDifferentTitle_relinksWithoutDuplicates() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        h.vaultFs.writeText("Renamed.md", "---\ntitle: Renamed\n---\nshared body")
        val id = h.insertLocal(Note(title = "Original", content = "shared body"))
        h.legacyMapping(id)

        h.syncNotes()
        h.syncNotes()

        assertEquals(1, h.localNoteCount())
        // The linked file may be renamed to the note's title, but it stays a single file.
        val files = h.vaultFs.notePaths().filter { it.endsWith(".md") }
        assertEquals(1, files.size)
        assertEquals(files.single(), h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri)
    }

    @Test
    fun legacyMapping_bodyMatchesFileOfAnotherNote_doesNotShareThatFile() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val owner = h.insertAndSync(Note(title = "Owner", content = "same body"))
        val ownerPath = h.idMappingDao.getByLocalIdAndProvider(owner, CloudService.FILE_STORAGE)!!.storageUri
        val legacy = h.insertLocal(Note(title = "Legacy", content = "same body"))
        h.legacyMapping(legacy)

        h.syncNotes()
        h.syncNotes()

        assertEquals(ownerPath, h.idMappingDao.getByLocalIdAndProvider(owner, CloudService.FILE_STORAGE)!!.storageUri)
        val legacyPath = h.idMappingDao.getByLocalIdAndProvider(legacy, CloudService.FILE_STORAGE)?.storageUri
        assertTrue("legacy note must get its own file, got $legacyPath", legacyPath != null && legacyPath != ownerPath)
        assertEquals(2, h.localNoteCount())
    }

    @Test
    fun legacyMapping_unmatched_exportsOnlyOnNextSync() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        h.vaultFs.writeText("Other.md", "---\ntitle: Other\n---\nother body")
        val id = h.insertLocal(Note(title = "Lonely", content = "lonely body"))
        h.legacyMapping(id)

        h.syncNotes()
        assertFalse(h.vaultFs.snapshotPaths().any { it.contains("Lonely") })
        assertEquals(0, h.mappingCountFor(id))
        assertEquals(2, h.localNoteCount())

        h.syncNotes()
        assertTrue(h.vaultFs.snapshotPaths().any { it.contains("Lonely") })
        assertEquals(1, h.mappingCountFor(id))
        assertEquals(2, h.localNoteCount())
    }
}
