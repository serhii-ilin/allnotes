package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.model.Tag
import org.qosp.notes.data.sync.fs.vault.history.HistoryCatalogApplier
import org.robolectric.RobolectricTestRunner

/**
 * HST-10 AC2 / HST-12: concurrent renames of one notebook or tag must fold to the same name on every
 * device. Replaying events against each device's current rows kept both names depending on delivery order.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryCatalogConvergenceTest {

    private fun TestScope.vault(count: Int) = MultiDeviceVault(this, count) { h ->
        listOf(HistoryCatalogApplier(h.notebookDao, h.noteDao, h.db.tagDao, h.db.noteTagDao))
    }

    private suspend fun NoteRepositoryVaultHarness.notebookNames(): List<String> =
        notebookDao.getAll().first().map { it.name }.sorted()

    private suspend fun NoteRepositoryVaultHarness.tagNames(): List<String> =
        db.tagDao.getAll().first().map { it.name }.sorted()

    private suspend fun NoteRepositoryVaultHarness.mismatches() = historyMismatches.readAll()

    private suspend fun MultiDeviceVault.deliverAndSync() {
        for (from in devices.indices) {
            for (to in devices.indices) {
                if (from != to) syncthing(from, to)
            }
        }
        devices.forEach { it.syncNotes() }
    }

    /** Scenario: A notebook rename travels (HST-10 AC2), with three devices renaming the same notebook. */
    @Test
    fun concurrentNotebookRenames_convergeToTheSameName() = runTest {
        val vault = vault(3)
        val a = vault[0]
        val b = vault[1]
        val c = vault[2]
        try {
            a.notebookRepository.insert(Notebook("Shared"))
            a.folderHistoryService.flush()
            vault.syncthing(0, 1)
            vault.syncthing(0, 2)
            b.syncNotes()
            c.syncNotes()

            a.notebookRepository.update(a.notebookDao.getByName("Shared").first()!!.copy(name = "FromA"))
            a.folderHistoryService.flush()
            b.notebookRepository.update(b.notebookDao.getByName("Shared").first()!!.copy(name = "FromB"))
            b.folderHistoryService.flush()
            c.notebookRepository.update(c.notebookDao.getByName("Shared").first()!!.copy(name = "FromC"))
            c.folderHistoryService.flush()

            vault.deliverAndSync()
            vault.deliverAndSync()

            val names = vault.devices.map { it.notebookNames() }
            assertEquals("every device computes the same target", names[0], names[1])
            assertEquals("every device computes the same target", names[0], names[2])
            assertEquals("exactly one rename wins", 1, names[0].size)
            assertFalse("the old name is gone", "Shared" in names[0])
            assertTrue(names[0].single() in setOf("FromA", "FromB", "FromC"))
            assertTrue(
                "waiting and losing a rename is not a mismatch",
                vault.devices.all { it.mismatches().isEmpty() },
            )
        } finally {
            vault.close()
        }
    }

    /** Scenario: A tag rename travels (HST-10 AC2), with three devices renaming the same tag. */
    @Test
    fun concurrentTagRenames_convergeToTheSameName() = runTest {
        val vault = vault(3)
        val a = vault[0]
        val b = vault[1]
        val c = vault[2]
        try {
            a.tagRepository.insert(Tag("shared"))
            a.folderHistoryService.flush()
            vault.syncthing(0, 1)
            vault.syncthing(0, 2)
            b.syncNotes()
            c.syncNotes()

            a.tagRepository.update(a.db.tagDao.getByName("shared").first()!!.copy(name = "fromA"))
            a.folderHistoryService.flush()
            b.tagRepository.update(b.db.tagDao.getByName("shared").first()!!.copy(name = "fromB"))
            b.folderHistoryService.flush()
            c.tagRepository.update(c.db.tagDao.getByName("shared").first()!!.copy(name = "fromC"))
            c.folderHistoryService.flush()

            vault.deliverAndSync()
            vault.deliverAndSync()

            val names = vault.devices.map { it.tagNames() }
            assertEquals("every device computes the same target", names[0], names[1])
            assertEquals("every device computes the same target", names[0], names[2])
            assertEquals("exactly one rename wins", 1, names[0].size)
            assertFalse("the old name is gone", "shared" in names[0])
            assertTrue(names[0].single() in setOf("fromA", "fromB", "fromC"))
            assertTrue(
                "waiting and losing a rename is not a mismatch",
                vault.devices.all { it.mismatches().isEmpty() },
            )
        } finally {
            vault.close()
        }
    }
}
