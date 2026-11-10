package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.data.sync.core.GenericError
import org.qosp.notes.data.sync.core.Success
import org.qosp.notes.preferences.CloudService
import org.robolectric.RobolectricTestRunner

/** A file the scan fails to list must never lead to a vault file being deleted. */
@RunWith(RobolectricTestRunner::class)
class VaultMissingFileSafetyTest {

    private class Fixture(val h: NoteRepositoryVaultHarness) {
        val ids = mutableListOf<Long>()
        val paths = mutableListOf<String>()
        val raws = mutableListOf<String>()

        suspend fun add(title: String) {
            val id = h.insertAndSync(Note(title = title, content = "Body of $title"))
            val path = mapping(id)!!.storageUri!!
            ids += id
            paths += path
            raws += h.vaultFs.readText(path)!!
        }

        suspend fun mapping(id: Long) = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)

        fun hide(index: Int) {
            h.vaultFs.delete(paths[index])
        }

        fun restore(index: Int) {
            h.vaultFs.writeText(paths[index], raws[index])
        }

        suspend fun isBinned(index: Int) = h.getNote(ids[index])!!.isDeleted

        suspend fun state(index: Int) = VaultMappingState.resolve(mapping(ids[index])!!.extras)!!

        suspend fun sync() = h.repository.syncNotes().also { h.flushRemoteActions() }
    }

    private suspend fun TestScope.fixture(vararg titles: String): Fixture {
        val f = Fixture(NoteRepositoryVaultHarness(this))
        titles.forEach { f.add(it) }
        return f
    }

    @Test
    fun missingOnce_notBinned_andCountResetsWhenFileReturns() = runTest {
        val f = fixture("One", "Two")
        f.hide(0)
        f.sync()
        assertFalse(f.isBinned(0))
        assertEquals(1, f.state(0).missingScanCount)
        assertEquals(f.paths[0], f.mapping(f.ids[0])!!.storageUri)

        f.restore(0)
        f.sync()
        assertFalse(f.isBinned(0))
        assertEquals(0, f.state(0).missingScanCount)
    }

    @Test
    fun missingOnTwoScans_isBinned_withoutMarkingUserBin() = runTest {
        val f = fixture("One", "Two")
        f.hide(0)
        f.sync()
        f.sync()
        assertTrue(f.isBinned(0))
        assertFalse(f.isBinned(1))
        assertFalse(f.state(0).userBinned)
    }

    @Test
    fun singleNoteVault_emptyListing_isNotBinned() = runTest {
        val f = fixture("Only")
        f.hide(0)
        val result = f.sync()
        assertTrue(result is GenericError)
        assertFalse(f.isBinned(0))
        assertEquals(0, f.state(0).missingScanCount)
    }

    @Test
    fun threeOfFiveMissing_syncProceeds_butKeepsNotesWithoutCounting() = runTest {
        val f = fixture("A", "B", "C", "D", "E")
        (0..2).forEach { f.hide(it) }
        f.h.tree().externalEdit(f.paths[3], "edited elsewhere")
        val r = f.sync()
        assertTrue(r is Success)
        f.sync()
        (0..2).forEach {
            assertFalse(f.isBinned(it))
            assertEquals(0, f.state(it).missingScanCount)
        }
        assertTrue(f.h.getNote(f.ids[3])!!.content.contains("edited elsewhere"))
    }

    @Test
    fun twoOfTenMissing_usesNormalTwoStrikes() = runTest {
        val f = fixture(*(0 until 10).map { "N$it" }.toTypedArray())
        f.hide(0)
        f.hide(1)
        f.sync()
        assertFalse(f.isBinned(0))
        assertEquals(1, f.state(0).missingScanCount)
        f.sync()
        assertTrue(f.isBinned(0))
        assertTrue(f.isBinned(1))
    }

    @Test
    fun twoOfThreeMissing_isUnderThreshold_binnedAfterTwoScans() = runTest {
        val f = fixture("A", "B", "C")
        f.hide(0)
        f.hide(1)
        assertTrue(f.sync() is Success)
        assertFalse(f.isBinned(0))
        assertEquals(1, f.state(0).missingScanCount)
        f.sync()
        assertTrue(f.isBinned(0))
        assertTrue(f.isBinned(1))
        assertFalse(f.isBinned(2))
    }

    @Test
    fun legacyPendingDeleteRow_isTreatedAsUserBinned_andNotReimported() = runTest {
        val f = fixture("One", "Two")
        f.h.repository.moveNotesToBin(f.h.getNote(f.ids[0])!!, sync = false)
        // A row written before userBinned existed: pendingVaultDelete set, no userBinned field.
        val row = f.mapping(f.ids[0])!!
        val legacy = VaultMappingState.encode(VaultMappingState.resolve(row.extras)!!.copy(userBinned = false))
        assertFalse(legacy.contains("userBinned"))
        f.h.idMappingDao.update(row.copy(extras = legacy))

        f.sync()
        assertFalse(f.h.vaultFs.isFile(f.paths[0]))
        f.h.repository.permanentlyDeleteNotesInBin()
        f.sync()
        assertFalse(f.h.vaultFs.isFile(f.paths[0]))
        assertEquals(1, f.h.localNoteCount())
    }

    @Test
    fun noteBinnedByMissingFile_permanentDeleteKeepsFileThatCameBack() = runTest {
        val f = fixture("One", "Two")
        f.hide(0)
        f.sync()
        f.sync()
        assertTrue(f.isBinned(0))
        f.restore(0)

        f.h.repository.deleteNotes(f.h.getNote(f.ids[0])!!, sync = true)
        f.h.flushRemoteActions()
        assertTrue(f.h.vaultFs.isFile(f.paths[0]))
        assertEquals(null, f.mapping(f.ids[0]))
    }

    @Test
    fun noteBinnedByMissingFile_emptyingBinKeepsFileThatCameBack() = runTest {
        val f = fixture("One", "Two")
        f.hide(0)
        f.sync()
        f.sync()
        assertTrue(f.isBinned(0))
        f.restore(0)

        f.h.repository.permanentlyDeleteNotesInBin()
        f.h.flushRemoteActions()
        assertTrue(f.h.vaultFs.isFile(f.paths[0]))
        assertEquals(null, f.mapping(f.ids[0]))
        assertEquals(null, f.h.getNote(f.ids[0]))
    }

    @Test
    fun userBinnedNote_permanentDeleteStillRemovesVaultFile() = runTest {
        val f = fixture("One", "Two")
        // sync = false leaves the file on disk until the delete is flushed, like a bin while the folder is away.
        f.h.repository.moveNotesToBin(f.h.getNote(f.ids[0])!!, sync = false)
        assertTrue(f.state(0).userBinned)
        assertTrue(f.h.vaultFs.isFile(f.paths[0]))

        f.h.repository.permanentlyDeleteNotesInBin()
        f.h.flushRemoteActions()
        assertFalse(f.h.vaultFs.isFile(f.paths[0]))
        assertTrue(f.h.vaultFs.isFile(f.paths[1]))
    }

    @Test
    fun userBinnedNote_deleteNotesStillRemovesVaultFile() = runTest {
        val f = fixture("One", "Two")
        f.h.repository.moveNotesToBin(f.h.getNote(f.ids[0])!!, sync = false)

        f.h.repository.deleteNotes(f.h.getNote(f.ids[0])!!, sync = true)
        f.h.flushRemoteActions()
        assertFalse(f.h.vaultFs.isFile(f.paths[0]))
    }
}
