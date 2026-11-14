package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.history.HistoryOps
import org.qosp.notes.preferences.CloudService
import org.robolectric.RobolectricTestRunner

/** Notes imported from Android's own `.trashed-` files are bins without a AllNotes user action. */
@RunWith(RobolectricTestRunner::class)
class VaultAndroidTrashRetirementTest {

    private class Fixture(val h: NoteRepositoryVaultHarness) {
        suspend fun mapping(id: Long) = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)

        suspend fun state(id: Long) = mapping(id)?.let { VaultMappingState.resolve(it.extras) }

        suspend fun addTrashedMapping(path: String, content: String, uid: String? = null): Long {
            h.vaultFs.writeText(path, content)
            val id = h.insertLocal(Note(title = path.removeSuffix(".md"), content = "secret"))
            h.upsertMapping(
                IdMapping(
                    localNoteId = id,
                    remoteNoteId = null,
                    provider = CloudService.FILE_STORAGE,
                    extras = VaultMappingState.encode(VaultMappingState(baselineHash = "h", noteUid = uid.orEmpty())),
                    isDeletedLocally = false,
                    storageUri = path,
                ),
            )
            return id
        }
    }

    private fun TestScope.fixture() = Fixture(NoteRepositoryVaultHarness(this))

    @Test
    fun emptyingTheBin_removesARetiredTrashRow_andLeavesTheTrashedFileAlone() = runTest {
        val f = fixture()
        val path = ".trashed-1700000000-Plan.md"
        val id = f.addTrashedMapping(path, "---\n---\nsecret\n")
        val before = f.h.vaultFs.openRead(path)!!.use { it.readBytes() }

        f.h.syncNotes()
        f.h.syncNotes()

        val note = f.h.getNote(id)!!
        assertTrue(note.isDeleted)
        assertEquals("Plan", note.title)
        val state = f.state(id)!!
        assertFalse("Android's trash is not a user bin", state.userBinned)
        assertEquals(path, state.binnedVaultPath)
        assertNull(state.trashedPath)
        assertFalse("the retired row must not ask for a vault delete", state.pendingVaultDelete)
        assertTrue(f.h.historyOutbox.readAll().none { it.op == HistoryOps.NOTE_PURGE || it.op == HistoryOps.NOTE_BIN })

        f.h.repository.permanentlyDeleteNotesInBin()
        f.h.flushRemoteActions()

        assertNull(f.h.noteDao.getById(id).first())
        assertNull(f.mapping(id))
        assertTrue(f.h.historyOutbox.readAll().none { it.op == HistoryOps.NOTE_PURGE })
        assertTrue("Android's file must never be deleted", f.h.vaultFs.isFile(path))
        assertArrayEquals(before, f.h.vaultFs.openRead(path)!!.use { it.readBytes() })
    }

    @Test
    fun deletingARetiredTrashRowFromTheBin_removesItWithoutTouchingTheFile() = runTest {
        val f = fixture()
        val path = ".trashed-1700000000-Plan.md"
        val id = f.addTrashedMapping(path, "---\n---\nsecret\n")
        f.h.syncNotes()
        val before = f.h.vaultFs.openRead(path)!!.use { it.readBytes() }

        f.h.deleteAndSync(f.h.getNote(id)!!)

        assertNull(f.h.noteDao.getById(id).first())
        assertNull(f.mapping(id))
        assertTrue(f.h.historyOutbox.readAll().none { it.op == HistoryOps.NOTE_PURGE })
        assertArrayEquals(before, f.h.vaultFs.openRead(path)!!.use { it.readBytes() })
    }

    @Test
    fun aTrashedCopyOfALiveNote_landsInTheBin_insteadOfBeingHardDeleted() = runTest {
        val f = fixture()
        val live = f.h.insertAndSync(Note(title = "Plan", content = "Body of Plan"))
        val uid = f.state(live)!!.noteUid
        val liveRaw = f.h.vaultFs.readText("Plan.md")!!
        val path = ".trashed-99-Plan.md"
        val duplicate = f.addTrashedMapping(path, liveRaw, uid)
        val before = f.h.vaultFs.openRead(path)!!.use { it.readBytes() }

        f.h.syncNotes()

        val retired = f.h.getNote(duplicate)!!
        assertTrue(retired.isDeleted)
        assertEquals("Plan", retired.title)
        val mapping = f.mapping(duplicate)!!
        assertTrue(mapping.isDeletedLocally)
        assertNull(mapping.storageUri)
        val state = f.state(duplicate)!!
        assertFalse(state.userBinned)
        assertEquals(path, state.binnedVaultPath)
        assertNull(state.trashedPath)
        val liveNote = f.h.getNote(live)!!
        assertFalse(liveNote.isDeleted)
        assertEquals("Plan.md", f.mapping(live)!!.storageUri)
        assertTrue(f.h.historyOutbox.readAll().none { it.op == HistoryOps.NOTE_PURGE || it.op == HistoryOps.NOTE_BIN })

        f.h.repository.permanentlyDeleteNotesInBin()
        f.h.flushRemoteActions()

        assertNull(f.h.noteDao.getById(duplicate).first())
        assertNull(f.mapping(duplicate))
        assertNotNull(f.h.getNote(live))
        assertTrue(f.h.historyOutbox.readAll().none { it.op == HistoryOps.NOTE_PURGE })
        assertArrayEquals(before, f.h.vaultFs.openRead(path)!!.use { it.readBytes() })
    }

    @Test
    fun restoringARetiredTrashNote_writesAFreshFile_andLeavesAndroidsTrashAlone() = runTest {
        val f = fixture()
        val path = ".trashed-1700000000-Plan.md"
        val id = f.addTrashedMapping(path, "---\n---\nsecret\n")
        f.h.syncNotes()
        val before = f.h.vaultFs.openRead(path)!!.use { it.readBytes() }

        f.h.restoreAndSync(f.h.getNote(id)!!)

        assertFalse(f.h.getNote(id)!!.isDeleted)
        assertEquals("Plan.md", f.mapping(id)!!.storageUri)
        assertTrue(f.h.vaultFs.isFile("Plan.md"))
        assertArrayEquals("Android's file must not be renamed", before, f.h.vaultFs.openRead(path)!!.use { it.readBytes() })
    }
}
