package org.qosp.notes.data.sync.fs.vault.history

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * [VaultFileAccess] as the app gets it: `StorageBackend` over the real vault tree, so a history write uses the
 * same crash-safe, generation-fenced path as a note write.
 */
@RunWith(RobolectricTestRunner::class)
class VaultHistoryStorageBackendTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun event(deviceId: String) = HistoryEvent(
        eid = "$deviceId:1",
        dev = deviceId,
        seq = 1,
        lc = 1,
        t = at("2026-11-10T12:00:00Z"),
        op = HistoryOps.NOTE_BIN,
        id = "3f2a",
        path = "Plan.md",
        to = ".trash/Plan.md",
    )

    @Test
    fun vaultFileAccess_writesThroughTheRecoveryPath() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            val deviceId = "device-a"
            val path = eventsPath(deviceId)
            val line = HistoryJson.json.encodeToString(event(deviceId)) + "\n"

            h.backend.writeVaultFile(path, line)

            assertEquals(line, h.backend.readVaultFile(path))
            assertEquals(
                listOf(path),
                h.backend.listVaultDir(HistoryPaths.deviceDir(deviceId)).map { it.relativePath },
            )
            // writeRaw journals the write under vault-recovery/ before publishing it.
            assertTrue(File(h.context.filesDir, "vault-recovery/${h.vaultFs.scopeId}").isDirectory)

            // The crash-safe path refuses an empty publish; a plain vault write would have accepted it.
            try {
                h.backend.writeVaultFile(path, "")
                fail("expected the recovery path to refuse an empty write")
            } catch (expected: IOException) {
                assertEquals(line, h.backend.readVaultFile(path))
            }
        } finally {
            h.close()
        }
    }

    /** HST-01/HST-05: the writer's log lands in the real vault tree through the backend. */
    @Test
    fun aFlushThroughTheRealBackend_writesTheLogAndTheManifest() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            val identity = DeviceIdentity(
                TestHistoryPrefs().repository,
                model = "Pixel 9",
                defaultScope = h.backend.historyScope,
            )
            val outbox = HistoryOutbox(File(tmp.newFolder("outbox"), HistoryOutbox.FILE_NAME))
            val writer = HistoryWriter(h.backend, identity, outbox, "1.5.14", now = { at("2026-11-10T12:00:00Z") })
            val deviceId = identity.deviceId()

            writer.record(HistoryOps.NOTE_BIN, id = "3f2a", path = "Plan.md", to = ".trash/Plan.md")
            assertEquals(FlushResult.Flushed(1, deviceId), writer.flush())

            val history = HistoryReader(h.backend).read()
            assertEquals(listOf(HistoryOps.NOTE_BIN), history.events.map { it.op })
            assertTrue(history.items.single().applicable)
            assertEquals(deviceId, history.manifests[deviceId]?.deviceId)
            assertEquals(HistoryWriter.README_TEXT, h.vaultFs.readText(HistoryPaths.README))
            assertTrue(outbox.readAll().isEmpty())
        } finally {
            h.close()
        }
    }

    /** Scenario: The note scan ignores the history folder (HST-01 AC4). */
    @Test
    fun theNoteScan_ignoresTheHistoryFolder() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            val deviceId = "device-a"
            h.vaultFs.writeText(
                eventsPath(deviceId),
                HistoryJson.json.encodeToString(event(deviceId)) + "\n",
            )
            h.vaultFs.writeText(".allnotes/devices/$deviceId/Hidden.md", "---\ntitle: Hidden\n---\nsecret\n")
            h.vaultFs.writeText("Plan.md", "---\ntitle: Plan\n---\nbody\n")

            h.syncNotes()

            assertEquals(1, h.localNoteCount())
        } finally {
            h.close()
        }
    }

    @Test
    fun theDefaultOutboxFile_livesInAppPrivateStorage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertEquals(
            File(context.filesDir, "vault-history/outbox.jsonl"),
            HistoryOutbox.defaultFile(context),
        )
    }
}
