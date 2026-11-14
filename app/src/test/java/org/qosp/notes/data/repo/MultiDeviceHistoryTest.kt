package org.qosp.notes.data.repo

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.model.Tag
import org.qosp.notes.data.sync.fs.vault.history.HistoryOps
import org.qosp.notes.data.sync.fs.vault.history.HistoryReader
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.preferences.CloudService
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/** History events travel through a Syncthing-like folder between independent installs. */
@RunWith(RobolectricTestRunner::class)
class MultiDeviceHistoryTest {

    private suspend fun NoteRepositoryVaultHarness.uid(noteId: Long): String =
        VaultMappingState.resolve(
            idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE)!!.extras,
        )!!.noteUid

    /** A bin on A is delivered to B's folder and shown there, even before Release B applies it. */
    @Test
    fun aBinOnAPhone_appearsInTheOtherPhonesFolderHistory() = runTest {
        val vault = MultiDeviceVault(this, 2)
        val a = vault[0]
        val b = vault[1]
        try {
            val noteId = a.insertAndSync(Note(title = "Plan", content = "Body"))
            vault.syncthing(0, 1)
            b.syncNotes()

            a.binAndSync(a.getNote(noteId)!!)
            vault.syncthing(0, 1)

            val snapshot = b.folderHistory.load()
            val entry = snapshot.entries.single { it.op == HistoryOps.NOTE_BIN }
            assertEquals("Plan", entry.noteTitle)
            assertEquals("Plan.md", entry.path)
            assertEquals(".trash/Plan.md", entry.to)
            assertEquals(a.historyIdentity.label(a.deviceId()), entry.deviceLabel)
            assertTrue(entry.knownOp)
        } finally {
            vault.close()
        }
    }

    /** Scenario: Three phones converge whatever the delivery order (HST-12, Release A logs only). */
    @Test
    fun threePhones_convergeWhateverTheDeliveryOrder() = runTest {
        for (seed in listOf(1, 2, 3)) {
            val vault = MultiDeviceVault(this, 3)
            try {
                vault[0].notebookRepository.insert(Notebook("Trips"))
                vault[1].tagRepository.insert(Tag("work"))
                vault[2].notebookRepository.insert(Notebook("Home"))
                vault[2].tagRepository.insert(Tag("home"))
                vault.devices.forEach { it.folderHistoryService.flush() }

                val deliveries = listOf(
                    0 to 1,
                    0 to 2,
                    1 to 0,
                    1 to 2,
                    2 to 0,
                    2 to 1,
                ).shuffled(Random(seed))
                repeat(2) {
                    deliveries.forEach { (from, to) -> vault.syncthing(from, to) }
                }

                val merged = vault.devices.map { device ->
                    HistoryReader(device.backend).read().events.map { it.eid }
                }
                assertTrue("seed $seed: every phone merges the same events", merged.all { it == merged[0] })
                assertTrue(merged[0].isNotEmpty())

                val screens = vault.devices.map { device ->
                    device.folderHistory.load().entries.map {
                        listOf(it.timeMs, it.deviceLabel, it.op, it.path, it.name)
                    }
                }
                assertTrue("seed $seed: every phone shows the same history", screens.all { it == screens[0] })
                assertTrue(
                    "seed $seed: no log conflicts",
                    vault.devices.all { device ->
                        device.vaultFs.snapshotPaths().none { it.contains(".sync-conflict-") }
                    },
                )
            } finally {
                vault.close()
            }
        }
    }
}
