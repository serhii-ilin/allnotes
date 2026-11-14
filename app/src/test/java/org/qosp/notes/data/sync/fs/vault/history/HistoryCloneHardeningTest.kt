package org.qosp.notes.data.sync.fs.vault.history

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.qosp.notes.data.sync.fs.vault.InMemoryVaultFs
import java.util.UUID

/** Clone detection hardening: conflicting own-id lines rotate, and a retried reid appears exactly once. */
class HistoryCloneHardeningTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun device(
        fs: InMemoryVaultFs = InMemoryVaultFs(),
        name: String = UUID.randomUUID().toString(),
        prefs: TestHistoryPrefs = TestHistoryPrefs(),
    ): TestHistoryDevice = TestHistoryDevice(TestVaultFileAccess(fs), prefs, tmp.newFolder(name))

    /** HST-02: an own-id `eid` with two different contents means another install already used this sequence. */
    @Test
    fun anOwnIdConflictInASyncthingCopy_rotatesTheDevice() = runTest {
        val fs = InMemoryVaultFs()
        val access = TestVaultFileAccess(fs)
        val identity = DeviceIdentity(
            TestHistoryPrefs().repository,
            model = "Pixel 9",
            defaultScope = fs.scopeId,
        )
        val id = identity.deviceId()
        identity.setLastWrittenSeq(1)
        val mine = HistoryEvent(
            eid = "$id:1",
            dev = id,
            seq = 1,
            lc = 1,
            t = at("2026-11-10T12:00:00Z"),
            op = HistoryOps.TAG_CREATE,
            name = "mine",
        )
        val theirs = mine.copy(name = "theirs")
        fs.writeText(eventsPath(id), HistoryJson.json.encodeToString(mine) + "\n")
        fs.writeText(
            "${HistoryPaths.deviceDir(id)}/events-2026-11.sync-conflict-20261110-120000-ABCDEF.jsonl",
            HistoryJson.json.encodeToString(theirs) + "\n",
        )
        assertEquals(setOf("$id:1"), HistoryReader(access).read().conflictingEids)

        val outbox = HistoryOutbox(tmp.newFolder("outbox").resolve(HistoryOutbox.FILE_NAME))
        val writer = HistoryWriter(access, identity, outbox, "1.5.14", now = { at("2026-11-10T12:00:00Z") })
        writer.record(HistoryOps.TAG_CREATE, name = "after")

        val result = writer.flush()

        val newId = (result as FlushResult.Flushed).deviceId
        assertNotEquals(id, newId)
        assertEquals(newId, identity.deviceId())
        assertEquals(
            listOf(HistoryOps.DEVICE_REID, HistoryOps.TAG_CREATE),
            access.events(eventsPath(newId)).map { it.op },
        )
        assertEquals(id, access.events(eventsPath(newId)).first().name)
    }

    /** HST-02 AC1: a failed rotation flush retried once writes one reid and rotates once. */
    @Test
    fun aFailedRotationFlush_retriesWithExactlyOneReidAndOneRotation() = runTest {
        val fs = InMemoryVaultFs()
        val a = device(fs, "a")
        val idA = a.id()
        a.record(HistoryOps.NOTE_BIN, id = "1", path = "Plan.md", to = ".trash/Plan.md")
        a.flush()
        val clone = device(fs, "clone", prefs = TestHistoryPrefs(a.prefs.snapshot()))
        a.record(HistoryOps.NOTEBOOK_CREATE, name = "Trips")
        a.flush()

        clone.record(HistoryOps.TAG_CREATE, name = "clone")
        clone.access.failNextWrite = true
        assertTrue(clone.flush() is FlushResult.Failed)

        val rotatedId = clone.id()
        assertNotEquals(idA, rotatedId)
        assertEquals(HistoryOps.DEVICE_REID, clone.outbox.readAll().first().op)
        assertEquals(1, clone.outbox.readAll().count { it.op == HistoryOps.DEVICE_REID })

        clone.access.failNextWrite = false
        assertEquals(FlushResult.Flushed(2, rotatedId), clone.flush())

        assertEquals(rotatedId, clone.id())
        assertEquals(1, clone.access.events(eventsPath(rotatedId)).count { it.op == HistoryOps.DEVICE_REID })
        assertEquals(
            listOf(HistoryOps.DEVICE_REID, HistoryOps.TAG_CREATE),
            clone.access.events(eventsPath(rotatedId)).map { it.op },
        )
        assertTrue(clone.outbox.readAll().isEmpty())
    }
}
