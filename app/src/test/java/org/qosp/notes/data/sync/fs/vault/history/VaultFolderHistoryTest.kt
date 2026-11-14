package org.qosp.notes.data.sync.fs.vault.history

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.preferences.CloudService
import org.robolectric.RobolectricTestRunner

/** The real [FolderHistory] over logs written by several devices. */
@RunWith(RobolectricTestRunner::class)
class VaultFolderHistoryTest {

    private suspend fun NoteRepositoryVaultHarness.noteUid(noteId: Long): String =
        VaultMappingState.resolve(
            idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE)!!.extras,
        )!!.noteUid

    private fun NoteRepositoryVaultHarness.writeManifest(
        deviceId: String,
        name: String,
        partialApplyOk: Boolean = false,
    ) {
        val manifest = DeviceManifest(
            deviceId = deviceId,
            name = name,
            appVersion = "1.5.14",
            writes = HistoryOps.ALL_V1,
            partialApplyOk = partialApplyOk,
            lastSeenDay = "2026-11-10",
        )
        vaultFs.writeText(
            HistoryPaths.manifestPath(deviceId),
            HistoryJson.json.encodeToString(manifest) + "\n",
        )
    }

    private fun NoteRepositoryVaultHarness.writeLog(deviceId: String, vararg lines: String) {
        vaultFs.writeText(
            "${HistoryPaths.deviceDir(deviceId)}/${HistoryPaths.monthlyEventsFile(AT)}",
            lines.joinToString("\n") + "\n",
        )
    }

    private fun event(
        deviceId: String,
        seq: Long,
        lc: Long,
        op: String,
        id: String? = null,
        path: String? = null,
        to: String? = null,
        name: String? = null,
        v: Int = HistoryOps.VERSION,
    ): String = HistoryJson.json.encodeToString(
        HistoryEvent(
            v = v,
            eid = "$deviceId:$seq",
            dev = deviceId,
            seq = seq,
            lc = lc,
            t = AT + lc * 1000,
            op = op,
            id = id,
            path = path,
            to = to,
            name = name,
        ),
    )

    /** Scenario: The Folder history screen lists merged events (HST-07). */
    @Test
    fun theFolderHistoryScreen_listsMergedEvents() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            val noteId = h.insertAndSync(Note(title = "Plan", content = "Body"))
            val uid = h.noteUid(noteId)
            val deviceA = "aaaaaa11-1111-1111-1111-111111111111"
            val deviceB = "bbbbbb22-2222-2222-2222-222222222222"
            h.writeManifest(deviceA, "Pixel 9")
            // This app already writes the full label into the manifest; it must not be doubled.
            h.writeManifest(deviceB, "Pixel 9 · ${deviceB.take(6)}")
            h.writeLog(
                deviceA,
                event(deviceA, 1, 1, HistoryOps.NOTE_BIN, id = uid, path = "Plan.md", to = ".trash/Plan.md"),
                event(deviceA, 2, 3, "note.pin", id = uid, path = "Plan.md"),
                event(deviceA, 3, 4, HistoryOps.NOTE_RESTORE, id = uid, path = "Plan.md"),
                "this is not json",
            )
            h.writeLog(
                deviceB,
                event(deviceB, 1, 2, HistoryOps.NOTEBOOK_CREATE, name = "Trips"),
            )

            val snapshot = h.folderHistory.load()

            assertEquals(1, snapshot.skippedLines)
            assertTrue(snapshot.mismatches.isEmpty())
            assertEquals(
                listOf(HistoryOps.NOTE_RESTORE, "note.pin", HistoryOps.NOTEBOOK_CREATE, HistoryOps.NOTE_BIN),
                snapshot.entries.map { it.op },
            )
            assertEquals(listOf(true, false, true, true), snapshot.entries.map { it.knownOp })
            assertEquals(listOf("Plan", "Plan", null, "Plan"), snapshot.entries.map { it.noteTitle })
            assertEquals(
                listOf("Pixel 9 · ${deviceA.take(6)}", "Pixel 9 · ${deviceA.take(6)}", "Pixel 9 · ${deviceB.take(6)}", "Pixel 9 · ${deviceA.take(6)}"),
                snapshot.entries.map { it.deviceLabel },
            )
            assertEquals(".trash/Plan.md", snapshot.entries.last().to)
            assertEquals("Trips", snapshot.entries[2].name)
        } finally {
            h.close()
        }
    }

    /** Scenario: Reading tolerates newer versions and damage (HST-06). */
    @Test
    fun readingToleratesNewerVersionsAndDamage() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            val deviceC = "cccccc33-3333-3333-3333-333333333333"
            // No manifest at all: the device is display-only, but its lines still show.
            h.writeLog(
                deviceC,
                event(deviceC, 1, 1, "note.pin", v = 2),
                "{broken",
                """{"v":1,"eid":"$deviceC:2","dev":"$deviceC","seq":2,"lc":2,"t":$AT,"op":"tag.create","name":"work","color":"red"}""",
            )

            val snapshot = h.folderHistory.load()

            assertEquals(1, snapshot.skippedLines)
            assertEquals(listOf("tag.create", "note.pin"), snapshot.entries.map { it.op })
            assertEquals("Unknown device · ${deviceC.take(6)}", snapshot.entries.first().deviceLabel)
            assertFalse(snapshot.entries.last().knownOp)
            assertEquals("work", snapshot.entries.first().name)
        } finally {
            h.close()
        }
    }

    /** Scenario: The Folder history screen lists merged events (HST-07 AC3): no folder is an empty state. */
    @Test
    fun aMissingHistoryFolder_showsAnEmptyState() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            h.vaultFs.snapshotPaths()
                .filter { it.startsWith(HistoryPaths.ROOT) }
                .forEach { h.vaultFs.delete(it) }

            val snapshot = h.folderHistory.load()

            assertTrue(snapshot.entries.isEmpty())
            assertEquals(0, snapshot.skippedLines)
            assertTrue(snapshot.mismatches.isEmpty())
            assertNull(snapshot.entries.firstOrNull())
        } finally {
            h.close()
        }
    }

    private companion object {
        const val AT = 1_762_780_800_000L // 2026-11-10T12:00:00Z
    }
}
