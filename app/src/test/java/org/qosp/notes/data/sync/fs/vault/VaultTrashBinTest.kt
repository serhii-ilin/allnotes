package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.preferences.CloudService
import org.robolectric.RobolectricTestRunner

/** The vault bin is `.trash/`: binning moves the file there, restore moves it back. */
@RunWith(RobolectricTestRunner::class)
class VaultTrashBinTest {

    private class Fixture(val h: NoteRepositoryVaultHarness) {
        suspend fun mapping(id: Long) = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!

        suspend fun state(id: Long) = VaultMappingState.resolve(mapping(id).extras)!!

        suspend fun add(title: String, notebookId: Long? = null, withAsset: Boolean = false): Long {
            val id = h.insertAndSync(Note(title = title, content = "Body of $title", notebookId = notebookId))
            if (withAsset) {
                val path = mapping(id).storageUri!!
                val dir = VaultPaths.assetDirectoryForNote(path)
                h.vaultFs.writeBytes("$dir/pic.png", byteArrayOf(1, 2, 3), "image/png")
                h.updateAndSync(
                    h.getNote(id)!!.copy(
                        attachments = listOf(
                            Attachment(Attachment.Type.IMAGE, VaultPaths.vaultRelative("$dir/pic.png"), "pic.png"),
                        ),
                        modifiedDate = 10L,
                    ),
                )
            }
            return id
        }

        suspend fun bin(id: Long) = h.binAndSync(h.getNote(id)!!)

        fun files() = h.vaultFs.notePaths()
    }

    private fun TestScope.fixture() = Fixture(NoteRepositoryVaultHarness(this))

    @Test
    fun binning_movesFileToTrash_andKeepsAssets() = runTest {
        val f = fixture()
        val id = f.add("Plan", withAsset = true)

        f.bin(id)

        assertFalse(f.h.vaultFs.isFile("Plan.md"))
        assertTrue(f.h.vaultFs.isFile(".trash/Plan.md"))
        assertTrue("assets stay so links work after restore", f.h.vaultFs.isFile("Plan/pic.png"))
        val state = f.state(id)
        assertEquals(".trash/Plan.md", state.trashedPath)
        assertEquals("Plan.md", state.binnedVaultPath)
        assertFalse(state.pendingVaultDelete)
        assertTrue(f.h.getNote(id)!!.isDeleted)
        assertEquals("Plan", f.h.getNote(id)!!.title)
    }

    @Test
    fun binning_keepsOriginalFolderUnderTrash_andTakenNamesGetSuffix() = runTest {
        val f = fixture()
        val work = Notebook("Work", f.h.notebookDao.insert(Notebook("Work")))
        val first = f.add("Plan", notebookId = work.id)
        f.bin(first)
        assertTrue(f.h.vaultFs.isFile(".trash/Work/Plan.md"))

        val second = f.add("Plan", notebookId = work.id)
        f.bin(second)

        assertTrue(f.h.vaultFs.isFile(".trash/Work/Plan-2.md"))
        assertTrue(f.h.vaultFs.isFile(".trash/Work/Plan.md"))
        assertEquals(".trash/Work/Plan-2.md", f.state(second).trashedPath)
    }

    @Test
    fun syncAfterBin_neverImportsOrMisses_trashFiles() = runTest {
        val f = fixture()
        val id = f.add("Plan")
        f.bin(id)

        f.h.syncNotes()
        f.h.syncNotes()
        f.h.syncNotes()

        assertEquals(1, f.h.localNoteCount())
        assertTrue(f.h.getNote(id)!!.isDeleted)
        assertEquals("Plan", f.h.getNote(id)!!.title)
        assertTrue(f.h.vaultFs.isFile(".trash/Plan.md"))
        assertEquals(1, f.h.mappingCountFor(id))
    }

    @Test
    fun androidTrashFile_isNotImported_andATrashTitleShowsTheOriginalName() = runTest {
        val f = fixture()
        f.h.vaultFs.writeText(".trashed-1700000000-Plan.md", "---\n---\nsecret\n")
        val ugly = f.h.insertLocal(
            Note(title = ".trashed-1700000000-Plan", content = "secret", isDeleted = true),
        )

        f.h.syncNotes()

        assertEquals(1, f.h.localNoteCount())
        assertEquals("Plan", f.h.getNote(ugly)!!.title)
        assertTrue(f.h.getNote(ugly)!!.isDeleted)
        assertTrue(f.h.vaultFs.isFile(".trashed-1700000000-Plan.md"))
    }

    @Test
    fun importedTrashFile_movesToTheBinUnderItsOriginalTitle_andStaysThere() = runTest {
        val f = fixture()
        val path = ".trashed-1700000000-Plan.md"
        f.h.vaultFs.writeText(path, "---\n---\nsecret\n")
        val id = f.h.insertLocal(Note(title = path.removeSuffix(".md"), content = "secret"))
        f.h.upsertMapping(
            IdMapping(
                localNoteId = id,
                remoteNoteId = null,
                provider = CloudService.FILE_STORAGE,
                extras = VaultMappingState.encode(VaultMappingState(baselineHash = "h")),
                isDeletedLocally = false,
                storageUri = path,
            ),
        )

        f.h.syncNotes()
        f.h.syncNotes()

        val note = f.h.getNote(id)!!
        assertEquals("Plan", note.title)
        assertTrue(note.isDeleted)
        assertEquals(1, f.h.localNoteCount())
        assertNull(f.mapping(id).storageUri)
        assertTrue(f.h.vaultFs.isFile(path))
    }

    @Test
    fun trashFileThatCopiesAnExistingNote_isDropped() = runTest {
        val f = fixture()
        val id = f.add("Plan")
        val uid = f.state(id).noteUid
        val raw = f.h.vaultFs.readText(f.mapping(id).storageUri!!)!!
        val path = ".trashed-99-Plan.md"
        f.h.vaultFs.writeText(path, raw)
        val duplicate = f.h.insertLocal(Note(title = ".trashed-99-Plan", content = "Body of Plan"))
        f.h.upsertMapping(
            IdMapping(
                localNoteId = duplicate,
                remoteNoteId = null,
                provider = CloudService.FILE_STORAGE,
                extras = VaultMappingState.encode(VaultMappingState(baselineHash = "h", noteUid = uid)),
                isDeletedLocally = false,
                storageUri = path,
            ),
        )

        f.h.syncNotes()

        // The trashed copy is not a live note, but it is still the user's text: it stays in the bin.
        val retired = f.h.noteDao.getById(duplicate).first()
        assertNotNull(retired)
        assertTrue(retired!!.isDeleted)
        assertEquals(2, f.h.localNoteCount())
        val duplicateMapping = f.mapping(duplicate)
        assertTrue(duplicateMapping.isDeletedLocally)
        assertNull(duplicateMapping.storageUri)
        assertFalse(f.state(duplicate)!!.userBinned)
        val kept = f.h.getNote(id)!!
        assertEquals("Plan", kept.title)
        assertFalse(kept.isDeleted)
        assertEquals("Plan.md", f.mapping(id).storageUri)
        assertTrue(f.h.vaultFs.isFile(path))
    }

    @Test
    fun dotTitleNote_isKept() = runTest {
        val f = fixture()
        f.h.vaultFs.writeText(".NET notes.md", "---\n---\nhello\n")

        f.h.syncNotes()

        val note = f.h.noteDao.getAll(org.qosp.notes.preferences.SortMethod.TITLE_ASC).first().single()
        assertEquals(".NET notes", note.title)
        assertFalse(note.isDeleted)
    }

    @Test
    fun fileUnderTrash_isNeverImported() = runTest {
        val f = fixture()
        f.h.vaultFs.writeText(".trash/Stray.md", "stray")
        f.h.vaultFs.writeText(".trash/Work/Deep.md", "deep")

        f.h.syncNotes()
        f.h.syncNotes()

        assertEquals(0, f.h.localNoteCount())
    }

    @Test
    fun restore_movesFileBack_andNoteIsLiveWithOneMapping() = runTest {
        val f = fixture()
        val id = f.add("Plan", withAsset = true)
        val before = f.h.vaultFs.readText("Plan.md")
        f.bin(id)

        f.h.restoreAndSync(f.h.getNote(id)!!)
        f.h.syncNotes()
        f.h.syncNotes()

        assertTrue(f.h.vaultFs.isFile("Plan.md"))
        assertFalse(f.h.vaultFs.isFile(".trash/Plan.md"))
        assertEquals(before, f.h.vaultFs.readText("Plan.md"))
        assertFalse(f.h.getNote(id)!!.isDeleted)
        assertEquals(1, f.h.localNoteCount())
        assertEquals(1, f.h.mappingCountFor(id))
        val mapping = f.mapping(id)
        assertEquals("Plan.md", mapping.storageUri)
        assertFalse(mapping.isDeletedLocally)
        val state = f.state(id)
        assertNull(state.trashedPath)
        assertNull(state.binnedVaultPath)
        assertFalse(state.userBinned)
        assertTrue(f.h.conflictFiles().isEmpty())
        assertTrue(f.h.duplicateSuffixFiles().isEmpty())
        assertTrue(f.h.vaultFs.isFile("Plan/pic.png"))
    }

    @Test
    fun restore_whenOriginalPathTaken_usesUniqueSibling() = runTest {
        val f = fixture()
        val id = f.add("Plan")
        f.bin(id)
        f.h.vaultFs.writeText("Plan.md", "someone else's file")

        f.h.restoreAndSync(f.h.getNote(id)!!)

        assertEquals("someone else's file", f.h.vaultFs.readText("Plan.md"))
        assertEquals("Plan-2.md", f.mapping(id).storageUri)
        assertTrue(f.h.vaultFs.isFile("Plan-2.md"))
        assertFalse(f.h.vaultFs.isFile(".trash/Plan.md"))
        assertFalse(f.h.getNote(id)!!.isDeleted)
    }

    @Test
    fun restore_whenTrashFileIsGone_recreatesTheFile() = runTest {
        val f = fixture()
        val id = f.add("Plan")
        f.bin(id)
        f.h.vaultFs.delete(".trash/Plan.md")

        f.h.restoreAndSync(f.h.getNote(id)!!)

        assertTrue(f.h.vaultFs.isFile("Plan.md"))
        assertFalse(f.h.getNote(id)!!.isDeleted)
        assertEquals("Plan.md", f.mapping(id).storageUri)
        assertEquals(1, f.h.mappingCountFor(id))
    }

    @Test
    fun permanentDeleteFromBin_removesTrashFileAndAssets() = runTest {
        val f = fixture()
        val id = f.add("Plan", withAsset = true)
        f.bin(id)

        f.h.repository.permanentlyDeleteNotesInBin()
        f.h.flushRemoteActions()

        assertEquals(emptySet<String>(), f.files())
        assertEquals(0, f.h.localNoteCount())
    }

    @Test
    fun deleteNotesFromBin_removesTrashFileAndAssets() = runTest {
        val f = fixture()
        val id = f.add("Plan", withAsset = true)
        f.bin(id)

        f.h.deleteAndSync(f.h.getNote(id)!!)

        assertEquals(emptySet<String>(), f.files())
    }

    @Test
    fun permanentDelete_neverTouchesAFileNowAtTheOriginalPath() = runTest {
        val f = fixture()
        val id = f.add("Plan")
        f.bin(id)
        f.h.vaultFs.writeText("Plan.md", "someone else's file")

        f.h.repository.permanentlyDeleteNotesInBin()
        f.h.flushRemoteActions()

        assertFalse(f.h.vaultFs.isFile(".trash/Plan.md"))
        assertEquals("someone else's file", f.h.vaultFs.readText("Plan.md"))
    }

    @Test
    fun missingFileBin_thenPermanentDelete_touchesNothingInTheVault() = runTest {
        val f = fixture()
        val id = f.add("Plan")
        val other = f.add("Other")
        f.h.vaultFs.writeText(".trash/Keep.md", "keep")
        f.h.vaultFs.delete("Plan.md")
        f.h.syncNotes()
        f.h.syncNotes()
        assertTrue(f.h.getNote(id)!!.isDeleted)
        assertFalse(f.state(id).userBinned)
        val before = f.files()

        f.h.repository.permanentlyDeleteNotesInBin()
        f.h.flushRemoteActions()

        assertEquals(before, f.files())
        assertNotNull(f.h.getNote(other))
    }

    @Test
    fun legacyPendingDelete_isMovedToTrashOnNextSync() = runTest {
        val f = fixture()
        val id = f.add("Plan")
        val mapping = f.mapping(id)
        val legacy = f.state(id).copy(binnedVaultPath = "Plan.md", pendingVaultDelete = true, userBinned = true)
        f.h.idMappingDao.update(
            mapping.copy(
                storageUri = null,
                isDeletedLocally = true,
                extras = VaultMappingState.encode(legacy),
            ),
        )
        f.h.noteDao.update(f.h.getNote(id)!!.copy(isDeleted = true, deletionDate = 5L).toEntity())

        f.h.syncNotes()

        assertFalse(f.h.vaultFs.isFile("Plan.md"))
        assertTrue(f.h.vaultFs.isFile(".trash/Plan.md"))
        val state = f.state(id)
        assertEquals(".trash/Plan.md", state.trashedPath)
        assertFalse(state.pendingVaultDelete)
    }

    @Test
    fun binWhileFolderUnavailable_isMovedOnceItIsAvailable() = runTest {
        val f = fixture()
        val id = f.add("Plan")
        f.h.vaultFs.writable = false

        f.bin(id)

        assertTrue(f.h.vaultFs.isFile("Plan.md"))
        assertTrue(f.state(id).pendingVaultDelete)
        assertNull(f.state(id).trashedPath)

        f.h.vaultFs.writable = true
        f.h.syncNotes()

        assertFalse(f.h.vaultFs.isFile("Plan.md"))
        assertTrue(f.h.vaultFs.isFile(".trash/Plan.md"))
        assertEquals(".trash/Plan.md", f.state(id).trashedPath)
        assertFalse(f.state(id).pendingVaultDelete)
        assertTrue(f.h.getNote(id)!!.isDeleted)
    }
}
