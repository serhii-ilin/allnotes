package org.qosp.notes.data.sync.fs.vault.history

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.qosp.notes.data.sync.fs.vault.InMemoryVaultFs
import java.util.UUID

/**
 * The writer half of Release A: record into the app-private outbox, flush into this device's own log only,
 * and never lose an action when a write fails.
 */
class HistoryWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun device(
        fs: InMemoryVaultFs = InMemoryVaultFs(),
        name: String = UUID.randomUUID().toString(),
        prefs: TestHistoryPrefs = TestHistoryPrefs(),
    ): TestHistoryDevice = TestHistoryDevice(TestVaultFileAccess(fs), prefs, tmp.newFolder(name))

    /** Scenario: A user bin is recorded in the device's own log only (HST-01, HST-04). */
    @Test
    fun aUserBin_isRecordedInTheDevicesOwnLogOnly() = runTest {
        val fs = InMemoryVaultFs()
        val a = device(fs, "a")
        val b = device(fs, "b")
        val idA = a.id()
        val idB = b.id()

        b.record(HistoryOps.NOTEBOOK_CREATE, name = "Trips")
        assertEquals(FlushResult.Flushed(1, idB), b.flush())
        val bFolderBefore = b.access.pathsUnder(HistoryPaths.deviceDir(idB))
        val bBytesBefore = bFolderBefore.associateWith { b.access.text(it) }

        a.record(HistoryOps.NOTE_BIN, id = "3f2a", path = "Plan.md", to = ".trash/Plan.md")
        assertEquals(FlushResult.Flushed(1, idA), a.flush())

        val lines = a.access.events(eventsPath(idA))
        assertEquals(1, lines.size)
        val event = lines.single()
        assertEquals("3f2a", event.id)
        assertEquals("Plan.md", event.path)
        assertEquals(".trash/Plan.md", event.to)
        assertEquals(HistoryOps.NOTE_BIN, event.op)
        assertEquals(idA, event.dev)
        assertEquals("$idA:1", event.eid)
        assertEquals(1L, event.seq)

        assertEquals(bFolderBefore, b.access.pathsUnder(HistoryPaths.deviceDir(idB)))
        bFolderBefore.forEach { path -> assertEquals(bBytesBefore[path], b.access.text(path)) }
        assertTrue(
            a.access.writes.all { it == HistoryPaths.README || it.startsWith(HistoryPaths.deviceDir(idA)) },
        )
    }

    /** Scenario: A user bin is recorded in the device's own log only (HST-04 AC2: no note text). */
    @Test
    fun recordedEvents_carryOnlyTheDocumentedFields_noNoteText() = runTest {
        val a = device()
        val id = a.id()

        // A note event is built from the note's id and paths only: there is no field a body could travel in.
        a.record(HistoryOps.NOTE_MOVE, id = "3f2a", path = "Work/Plan.md", to = "Home/Plan.md")
        a.flush()

        val raw = a.access.text(eventsPath(id)).orEmpty()
        assertEquals(1, raw.lines().count { it.isNotBlank() })
        val fields = HistoryJson.json.parseToJsonElement(raw.trim()).jsonObject.keys
        assertEquals(setOf("v", "eid", "dev", "seq", "lc", "t", "op", "id", "path", "to"), fields)
    }

    /** Scenario: The README is written once with identical bytes (HST-01 AC3). */
    @Test
    fun theReadme_isWrittenOnceWithIdenticalBytes() = runTest {
        // Two installs that have not received each other's files yet write byte-identical READMEs.
        val a = device(name = "a")
        val b = device(name = "b")
        assertNotEquals(a.id(), b.id())
        a.record(HistoryOps.NOTE_BIN, id = "1", path = "A.md", to = ".trash/A.md")
        b.record(HistoryOps.NOTE_BIN, id = "2", path = "B.md", to = ".trash/B.md")

        a.flush()
        b.flush()

        val readmeA = a.access.text(HistoryPaths.README)
        assertEquals(HistoryWriter.README_TEXT, readmeA)
        assertEquals(readmeA, b.access.text(HistoryPaths.README))
        assertEquals(1, a.access.writeCount(HistoryPaths.README))
        assertEquals(1, b.access.writeCount(HistoryPaths.README))

        // Two writers on one folder: the second one finds the file and leaves it alone.
        val shared = InMemoryVaultFs()
        val c = device(shared, "c")
        val d = device(shared, "d")
        assertNotEquals(c.id(), d.id())
        c.record(HistoryOps.NOTE_BIN, id = "3", path = "C.md", to = ".trash/C.md")
        d.record(HistoryOps.NOTE_BIN, id = "4", path = "D.md", to = ".trash/D.md")
        c.flush()
        d.flush()
        assertEquals(1, c.access.writeCount(HistoryPaths.README) + d.access.writeCount(HistoryPaths.README))
        assertEquals(readmeA, d.access.text(HistoryPaths.README))

        // A later sync keeps the file: it exists, so it is never rewritten.
        a.record(HistoryOps.NOTE_RESTORE, id = "1", path = "A.md")
        a.flush()
        assertEquals(1, a.access.writeCount(HistoryPaths.README))
        assertEquals(readmeA, a.access.text(HistoryPaths.README))

        // Even a README the user edited by hand is left alone.
        val e = device(name = "e")
        e.access.fs.writeText(HistoryPaths.README, "hand edited")
        e.record(HistoryOps.NOTE_BIN, id = "5", path = "E.md", to = ".trash/E.md")
        e.flush()
        assertEquals("hand edited", e.access.text(HistoryPaths.README))
    }

    /** Scenario: A cloned install switches to a new id (HST-02). */
    @Test
    fun aClonedInstall_switchesToANewId() = runTest {
        val fs = InMemoryVaultFs()
        val a = device(fs, "a")
        val idA = a.id()
        a.record(HistoryOps.NOTE_BIN, id = "1", path = "Plan.md", to = ".trash/Plan.md")
        a.flush()
        // Phone C was restored from a copy of A's app data at this point.
        val clone = device(fs, "c", prefs = TestHistoryPrefs(a.prefs.snapshot()))
        // A keeps writing under its own id without C knowing.
        a.record(HistoryOps.NOTE_RESTORE, id = "1", path = "Plan.md")
        a.record(HistoryOps.NOTE_BIN, id = "2", path = "Other.md", to = ".trash/Other.md")
        a.flush()
        val aFolderBefore = a.access.pathsUnder(HistoryPaths.deviceDir(idA))
        val aBytesBefore = aFolderBefore.associateWith { a.access.text(it) }

        // The clone records something of its own, so the next flush has a reason to read the logs.
        clone.record(HistoryOps.TAG_CREATE, name = "clone")
        val result = clone.flush()

        val newId = (result as FlushResult.Flushed).deviceId
        assertNotEquals(idA, newId)
        assertEquals(newId, clone.id())
        val reid = clone.access.events(eventsPath(newId)).first()
        assertEquals(HistoryOps.DEVICE_REID, reid.op)
        assertEquals(idA, reid.name)
        assertEquals(1L, reid.seq)
        assertEquals(newId, reid.dev)
        assertEquals(2L, clone.identity.lastWrittenSeq())
        assertEquals(listOf(HistoryOps.DEVICE_REID, HistoryOps.TAG_CREATE), clone.access.events(eventsPath(newId)).map { it.op })
        assertEquals(aFolderBefore, a.access.pathsUnder(HistoryPaths.deviceDir(idA)))
        aFolderBefore.forEach { path -> assertEquals(aBytesBefore[path], a.access.text(path)) }
    }

    /** HST-05 AC2: a README failure after the events landed must not re-record them on the next flush. */
    @Test
    fun aReadmeFailureAfterTheEvents_doesNotRerateTheIdOrDuplicateTheLog() = runTest {
        val a = device()
        val id = a.id()
        a.record(HistoryOps.NOTE_BIN, id = "3f2a", path = "Plan.md", to = ".trash/Plan.md")
        a.access.failNextOnPath = HistoryPaths.README

        assertTrue(a.flush() is FlushResult.Failed)

        assertEquals(id, a.id())
        assertEquals(listOf("$id:1"), a.access.events(eventsPath(id)).map { it.eid })
        assertEquals(1L, a.identity.lastWrittenSeq())
        assertTrue(a.outbox.readAll().isEmpty())

        // The next flush only finishes the metadata; the event is not written again and the id never changed.
        assertEquals(FlushResult.Flushed(0, id), a.flush())
        assertEquals(id, a.id())
        assertEquals(listOf("$id:1"), a.access.events(eventsPath(id)).map { it.eid })
        assertEquals(HistoryWriter.README_TEXT, a.access.text(HistoryPaths.README))
        assertEquals(id, a.access.manifest(id)?.deviceId)
    }

    /**
     * A process killed between the verified event write and the seq/outbox commit leaves lines under our id
     * that we did write: they must be adopted, not mistaken for a clone.
     */
    @Test
    fun linesFromOurOwnKilledFlush_areAdopted_notTreatedAsAClone() = runTest {
        val a = device()
        val id = a.id()
        a.record(HistoryOps.NOTE_BIN, id = "3f2a", path = "Plan.md", to = ".trash/Plan.md")
        a.flush()
        val written = a.access.text(eventsPath(id))
        // The process died here: the lines are on disk, the seq is not advanced and the outbox still holds the action.
        a.identity.setLastWrittenSeq(0)
        a.outbox.append(
            PendingHistoryEvent(
                op = HistoryOps.NOTE_BIN,
                id = "3f2a",
                path = "Plan.md",
                to = ".trash/Plan.md",
                t = at("2026-11-10T12:00:00Z"),
                scope = a.access.historyScope,
            ),
        )

        assertEquals(FlushResult.Flushed(0, id), a.flush())

        assertEquals(id, a.id())
        assertEquals(written, a.access.text(eventsPath(id)))
        assertEquals(1L, a.identity.lastWrittenSeq())
        assertTrue(a.outbox.readAll().isEmpty())

        // The next action continues after the adopted lines.
        a.record(HistoryOps.TAG_CREATE, name = "work")
        assertEquals(FlushResult.Flushed(1, id), a.flush())
        assertEquals(listOf("$id:1", "$id:2"), a.access.events(eventsPath(id)).map { it.eid })
        assertEquals(id, a.id())
    }

    /** Scenario: A cloned install switches to a new id (HST-02 AC1): a failed flush must not lose the reid. */
    @Test
    fun aClonesReid_survivesAFailedFlush() = runTest {
        val fs = InMemoryVaultFs()
        val a = device(fs, "a")
        val idA = a.id()
        a.record(HistoryOps.NOTE_BIN, id = "1", path = "Plan.md", to = ".trash/Plan.md")
        a.flush()
        val clone = device(fs, "c", prefs = TestHistoryPrefs(a.prefs.snapshot()))
        a.record(HistoryOps.NOTEBOOK_CREATE, name = "Trips")
        a.flush()

        clone.record(HistoryOps.TAG_CREATE, name = "clone")
        clone.access.failAllWrites = true
        assertTrue(clone.flush() is FlushResult.Failed)

        val newId = clone.id()
        assertNotEquals(idA, newId)
        assertEquals(listOf(HistoryOps.DEVICE_REID, HistoryOps.TAG_CREATE), clone.outbox.readAll().map { it.op })
        assertEquals(idA, clone.outbox.readAll().first().name)

        clone.access.failAllWrites = false
        assertEquals(FlushResult.Flushed(2, newId), clone.flush())
        val reid = clone.access.events(eventsPath(newId)).first()
        assertEquals(HistoryOps.DEVICE_REID, reid.op)
        assertEquals(idA, reid.name)
        assertTrue(clone.outbox.readAll().isEmpty())
    }

    /** Scenario: Binning while the folder is unavailable records the event later (HST-05 AC1, AC2). */
    @Test
    fun binningWhileTheFolderIsUnavailable_recordsTheEventLater() = runTest {
        val a = device()
        a.access.failAllWrites = true

        a.record(HistoryOps.NOTE_BIN, id = "3f2a", path = "Plan.md", to = ".trash/Plan.md")
        assertTrue(a.flush() is FlushResult.Failed)
        assertTrue(a.access.fs.snapshotPaths().none { it.startsWith(HistoryPaths.ROOT) })
        assertEquals(1, a.outbox.readAll().size)

        a.access.failAllWrites = false
        val result = a.flush()

        assertEquals(FlushResult.Flushed(1, a.id()), result)
        assertEquals(HistoryOps.NOTE_BIN, a.access.events(eventsPath(a.id())).single().op)
        assertTrue(a.outbox.readAll().isEmpty())
    }

    /** Scenario: Binning while the folder is unavailable records the event later (HST-05 AC2: one failed write). */
    @Test
    fun aFlushThatFailsOnce_keepsTheOutboxAndRetries() = runTest {
        val a = device()
        a.record(HistoryOps.TAG_CREATE, name = "trips")
        a.access.failNextWrite = true

        val failed = a.flush()

        assertTrue(failed is FlushResult.Failed)
        assertEquals(1, a.outbox.readAll().size)
        assertEquals("trips", a.outbox.readAll().single().name)

        assertEquals(FlushResult.Flushed(1, a.id()), a.flush())
        assertEquals(HistoryOps.TAG_CREATE, a.access.events(eventsPath(a.id())).single().op)
        assertTrue(a.outbox.readAll().isEmpty())
    }

    /** HST-05 AC2: a write that does not survive the read-back must not clear the outbox. */
    @Test
    fun aWriteThatDoesNotLand_keepsTheOutbox() = runTest {
        val a = device()
        a.record(HistoryOps.NOTE_PURGE, id = "3f2a", path = "Plan.md")
        a.access.dropWrites = true

        assertTrue(a.flush() is FlushResult.Failed)
        assertEquals(1, a.outbox.readAll().size)
        assertEquals(0L, a.identity.lastWrittenSeq())

        a.access.dropWrites = false
        assertEquals(FlushResult.Flushed(1, a.id()), a.flush())
        assertEquals(1L, a.identity.lastWrittenSeq())
    }

    /** HST-06: the manifest is rewritten only when a field changes, and `lastSeenDay` at most once a day. */
    @Test
    fun theManifest_isRewrittenOnlyWhenChanged_andLastSeenDayAtMostDaily() = runTest {
        val a = device()
        val id = a.id()
        val manifestPath = HistoryPaths.manifestPath(id)

        a.record(HistoryOps.NOTEBOOK_CREATE, name = "Trips")
        a.flush()
        val first = a.access.manifest(id)
        assertEquals(1, first?.format)
        assertEquals(id, first?.deviceId)
        assertEquals("Pixel 9 · ${id.take(6)}", first?.name)
        assertEquals("1.5.14", first?.appVersion)
        assertEquals(HistoryOps.ALL_V1, first?.writes)
        assertEquals(true, first?.partialApplyOk)
        assertEquals("2026-11-10", first?.lastSeenDay)
        assertEquals(1, a.access.writeCount(manifestPath))

        // Same day, no changes: nothing to do, no file touched.
        assertEquals(FlushResult.NothingToDo, a.flush())
        assertEquals(1, a.access.writeCount(manifestPath))

        // Same day, more events: the manifest stays as it is.
        a.record(HistoryOps.NOTEBOOK_DELETE, name = "Trips")
        a.flush()
        assertEquals(1, a.access.writeCount(manifestPath))

        // A new day rewrites only lastSeenDay, once.
        a.now = at("2026-11-11T09:00:00Z")
        a.record(HistoryOps.TAG_CREATE, name = "work")
        a.flush()
        assertEquals(2, a.access.writeCount(manifestPath))
        assertEquals("2026-11-11", a.access.manifest(id)?.lastSeenDay)
        assertEquals(FlushResult.NothingToDo, a.flush())
        assertEquals(2, a.access.writeCount(manifestPath))

        // A changed field rewrites it even with an empty outbox.
        assertEquals(FlushResult.Flushed(0, id), a.writer("1.5.15").flush())
        assertEquals(3, a.access.writeCount(manifestPath))
        assertEquals("1.5.15", a.access.manifest(id)?.appVersion)
        assertEquals("2026-11-11", a.access.manifest(id)?.lastSeenDay)
    }

    /** HST-05 AC1: recording touches nothing in the vault, only the app-private outbox. */
    @Test
    fun recording_touchesNothingInTheVault() = runTest {
        val a = device()
        a.record(HistoryOps.NOTE_BIN, id = "3f2a", path = "Plan.md", to = ".trash/Plan.md")

        assertTrue(a.access.writes.isEmpty())
        assertTrue(a.access.fs.snapshotPaths().none { it.startsWith(HistoryPaths.ROOT) })
        assertEquals(at("2026-11-10T12:00:00Z"), a.outbox.readAll().single().t)
    }

    /** HST-01 AC1: a flush with nothing recorded and no manifest change returns before touching the vault. */
    @Test
    fun anEmptyOutboxAndCurrentManifest_doNothing() = runTest {
        val a = device()
        a.record(HistoryOps.NOTE_BIN, id = "1", path = "Plan.md", to = ".trash/Plan.md")
        a.flush()

        a.access.writes.clear()
        assertEquals(FlushResult.NothingToDo, a.flush())
        assertTrue(a.access.writes.isEmpty())
    }

    /** HST-01 AC1: a no-op flush checks only this device's manifest, not every log */
    @Test
    fun aNoOpFlush_readsOnlyItsOwnManifest() = runTest {
        val a = device()
        a.record(HistoryOps.NOTE_BIN, id = "1", path = "Plan.md", to = ".trash/Plan.md")
        a.flush()

        a.access.reads.clear()
        assertEquals(FlushResult.NothingToDo, a.flush())

        assertEquals(listOf(HistoryPaths.manifestPath(a.id())), a.access.reads)
    }

    /** HST-04: every v1 op round-trips with its documented fields. */
    @Test
    fun everyV1Op_isWrittenWithItsDocumentedFields() = runTest {
        val a = device()
        a.record(HistoryOps.NOTE_BIN, id = "1", path = "Plan.md", to = ".trash/Plan.md")
        a.record(HistoryOps.NOTE_RESTORE, id = "1", path = "Plan.md")
        a.record(HistoryOps.NOTE_PURGE, id = "1", path = "Plan.md")
        a.record(HistoryOps.NOTE_MOVE, id = "1", path = "Plan.md", to = "Ideas.md")
        a.record(HistoryOps.NOTEBOOK_CREATE, name = "Trips")
        a.record(HistoryOps.NOTEBOOK_RENAME, name = "Trips", to = "Travel")
        a.record(HistoryOps.NOTEBOOK_DELETE, name = "Travel")
        a.record(HistoryOps.TAG_CREATE, name = "work")
        a.record(HistoryOps.TAG_RENAME, name = "work", to = "job")
        a.record(HistoryOps.TAG_DELETE, name = "job")
        a.flush()

        val events = a.access.events(eventsPath(a.id()))
        assertEquals(HistoryOps.ALL_V1.minus(HistoryOps.DEVICE_REID), events.map { it.op })
        assertEquals((1L..10L).toList(), events.map { it.seq })
        assertEquals((1L..10L).toList(), events.map { it.lc })
        assertEquals(events.indices.map { "${a.id()}:${it + 1}" }, events.map { it.eid })
    }

    /** HST-03 AC2: the Lamport clock continues above everything this device has already seen, whatever its clock says. */
    @Test
    fun aSlowClock_getsTheNextLamportTick_notAnEarlierPosition() = runTest {
        val fs = InMemoryVaultFs()
        val b = device(fs, "b")
        val idB = b.id()
        b.record(HistoryOps.TAG_CREATE, name = "work")
        b.record(HistoryOps.TAG_CREATE, name = "home")
        b.flush()

        // C's clock is five hours behind B's, but it saw B's log.
        val c = device(fs, "c")
        c.now = at("2026-11-10T07:00:00Z")
        c.record(HistoryOps.NOTE_BIN, id = "1", path = "Plan.md", to = ".trash/Plan.md")
        c.flush()

        val mine = c.access.events(eventsPath(c.id())).single()
        assertEquals(3L, mine.lc)
        assertEquals(at("2026-11-10T07:00:00Z"), mine.t)
        assertEquals(
            listOf("$idB:1", "$idB:2", "${c.id()}:1"),
            HistoryReader(c.access).read().events.map { it.eid },
        )
    }

    /** HST-04 AC3: a note without a allnotes-id records nothing; the engine never invents one. */
    @Test
    fun aNoteWithoutAnId_recordsNothing() = runTest {
        val a = device()

        a.record(HistoryOps.NOTE_BIN, path = "Plan.md", to = ".trash/Plan.md")
        a.record(HistoryOps.NOTE_MOVE, path = "Plan.md", to = "Ideas.md")
        assertTrue(a.outbox.readAll().isEmpty())

        // Notebook and tag events are about a name, not an id, and are unaffected.
        a.record(HistoryOps.NOTEBOOK_CREATE, name = "Trips")
        assertEquals(listOf(HistoryOps.NOTEBOOK_CREATE), a.outbox.readAll().map { it.op })
        assertTrue(a.access.writes.isEmpty())
    }
}
