package org.qosp.notes.data.sync.fs.vault.history

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.qosp.notes.data.sync.fs.vault.InMemoryVaultFs

/**
 * The reading half of Release A: merged order from every device, duplicate and damaged lines handled without
 * ever guessing, and newer formats kept display-only unless their device says otherwise.
 */
class HistoryReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fs = InMemoryVaultFs()
    private val access = TestVaultFileAccess(fs)

    private fun event(
        dev: String,
        seq: Long,
        lc: Long = seq,
        t: Long = at("2026-11-10T12:00:00Z"),
        op: String = HistoryOps.NOTE_BIN,
        id: String? = "3f2a",
        path: String? = "Plan.md",
        to: String? = ".trash/Plan.md",
        name: String? = null,
        v: Int = HistoryOps.VERSION,
    ): HistoryEvent = HistoryEvent(
        v = v,
        eid = "$dev:$seq",
        dev = dev,
        seq = seq,
        lc = lc,
        t = t,
        op = op,
        id = id,
        path = path,
        to = to,
        name = name,
    )

    private fun writeLog(
        events: List<HistoryEvent>,
        deviceId: String = events.first().dev,
        fileName: String = HistoryPaths.monthlyEventsFile(events.first().t),
    ) {
        val path = "${HistoryPaths.deviceDir(deviceId)}/$fileName"
        val existing = fs.readText(path).orEmpty()
        val separator = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
        fs.writeText(path, existing + separator + events.joinToString("\n") { HistoryJson.json.encodeToString(it) } + "\n")
    }

    private fun writeRaw(deviceId: String, fileName: String, text: String) {
        fs.writeText("${HistoryPaths.deviceDir(deviceId)}/$fileName", text)
    }

    private fun writeManifest(deviceId: String, partialApplyOk: Boolean = true, raw: String? = null) {
        val body = raw ?: HistoryJson.json.encodeToString(
            DeviceManifest(
                deviceId = deviceId,
                name = "Pixel 9 · ${deviceId.take(6)}",
                appVersion = "1.5.14",
                writes = HistoryOps.ALL_V1,
                partialApplyOk = partialApplyOk,
                lastSeenDay = "2026-11-10",
            ),
        )
        fs.writeText(HistoryPaths.manifestPath(deviceId), body)
    }

    /** Scenario: A Syncthing conflict copy of a log is read once (HST-03 AC1). */
    @Test
    fun aSyncthingConflictCopyOfALog_isReadOnce() = runTest {
        val a = "device-a"
        writeManifest(a)
        writeLog(listOf(event(a, seq = 7, lc = 7)))
        writeRaw(
            a,
            "events-2026-11.sync-conflict-20261110-120000-ABCDEF.jsonl",
            HistoryJson.json.encodeToString(event(a, seq = 7, lc = 7)) + "\n",
        )

        val result = HistoryReader(access).read()

        assertEquals(1, result.events.size)
        assertEquals("$a:7", result.events.single().eid)
        assertEquals(0, result.skippedLines)
    }

    /** Scenario: Wall-clock skew does not reorder history (HST-03 AC2, AC3). */
    @Test
    fun wallClockSkew_doesNotReorderHistory() = runTest {
        val a = "device-a"
        val b = "device-b"
        writeManifest(a)
        writeManifest(b)
        // B's clock is 5 hours behind, but B saw A's log, so its event has the higher Lamport clock.
        writeLog(listOf(event(a, seq = 1, lc = 1, t = at("2026-11-10T12:00:00Z"), op = HistoryOps.NOTE_BIN)))
        writeLog(
            listOf(
                event(
                    b,
                    seq = 1,
                    lc = 2,
                    t = at("2026-11-10T07:00:00Z"),
                    op = HistoryOps.NOTE_RESTORE,
                    to = null,
                ),
            ),
        )

        val first = HistoryReader(access).read()
        val second = HistoryReader(access).read()

        assertEquals(listOf(HistoryOps.NOTE_BIN, HistoryOps.NOTE_RESTORE), first.events.map { it.op })
        assertEquals(first.events.map { it.eid }, second.events.map { it.eid })
        assertEquals(first.events.map { it.eid }, HistoryReader(access).read().events.map { it.eid })
    }

    /** HST-03 AC3: a device that lists the same files in another order still computes the same history. */
    @Test
    fun everyReaderComputesTheSameOrder() = runTest {
        val a = "device-a"
        val b = "device-b"
        val eventsA = listOf(event(a, seq = 1, lc = 1), event(a, seq = 2, lc = 3))
        val eventsB = listOf(
            event(b, seq = 1, lc = 2),
            event(b, seq = 2, lc = 2, op = HistoryOps.NOTE_RESTORE, to = null),
        )

        fun vaultWith(events: List<HistoryEvent>): InMemoryVaultFs {
            val vault = InMemoryVaultFs()
            for (event in events) {
                val path = "${HistoryPaths.deviceDir(event.dev)}/${HistoryPaths.monthlyEventsFile(event.t)}"
                val existing = vault.readText(path).orEmpty()
                vault.writeText(path, existing + HistoryJson.json.encodeToString(event) + "\n")
            }
            return vault
        }

        val first = HistoryReader(TestVaultFileAccess(vaultWith(eventsA + eventsB))).read()
        val reversed = HistoryReader(TestVaultFileAccess(vaultWith((eventsB + eventsA).reversed()))).read()

        assertEquals(listOf("$a:1", "$b:1", "$b:2", "$a:2"), first.events.map { it.eid })
        assertEquals(first.events.map { it.eid }, reversed.events.map { it.eid })
    }

    /** Scenario outline row HST-06: an event with an extra field "color" is shown normally. */
    @Test
    fun extraField_isShownNormally() = runTest {
        val c = "device-c"
        writeManifest(c)
        writeRaw(
            c,
            "events-2026-11.jsonl",
            """{"v":1,"eid":"$c:1","dev":"$c","seq":1,"lc":1,"t":1731265000123,"op":"note.bin",""" +
                """"id":"3f2a","path":"Plan.md","to":".trash/Plan.md","color":"amber"}""" + "\n",
        )

        val result = HistoryReader(access).read()

        assertEquals(1, result.items.size)
        assertTrue(result.items.single().knownOp)
        assertTrue(result.items.single().applicable)
        assertEquals("3f2a", result.items.single().event.id)
    }

    /** Scenario outline row HST-06: an event with op "note.pin" is shown but never applied. */
    @Test
    fun unknownOp_isShownButNeverApplied() = runTest {
        val c = "device-c"
        writeManifest(c)
        writeLog(listOf(event(c, seq = 1, op = "note.pin", id = "3f2a", path = "Plan.md", to = null)))

        val result = HistoryReader(access).read()

        val item = result.items.single()
        assertEquals("note.pin", item.event.op)
        assertFalse(item.knownOp)
        assertFalse(item.applicable)
    }

    /** Scenario outline row HST-06: a line that is not valid JSON is skipped and counted. */
    @Test
    fun malformedLine_isSkippedAndCounted() = runTest {
        val c = "device-c"
        writeManifest(c)
        writeRaw(
            c,
            "events-2026-11.jsonl",
            "{not json at all\n" + HistoryJson.json.encodeToString(event(c, seq = 1)) + "\n}\n",
        )

        val result = HistoryReader(access).read()

        assertEquals(1, result.events.size)
        assertEquals(2, result.skippedLines)
    }

    /** Scenario outline row HST-06: a "v": 2 event while C has no device.json is display-only. */
    @Test
    fun newerVersionEventWithoutManifest_isDisplayOnly() = runTest {
        val c = "device-c"
        writeLog(listOf(event(c, seq = 1, v = 2)))

        val result = HistoryReader(access).read()

        assertEquals(1, result.items.size)
        assertTrue(result.items.single().knownOp)
        assertFalse(result.items.single().applicable)
    }

    /** HST-06 AC2: a v: 2 device with partialApplyOk false affects nothing. */
    @Test
    fun newerVersionEventWithPartialApplyFalse_isDisplayOnly() = runTest {
        val c = "device-c"
        writeManifest(c, partialApplyOk = false)
        writeLog(listOf(event(c, seq = 1, v = 2)))

        assertFalse(HistoryReader(access).read().items.single().applicable)
    }

    /** HST-06 AC2: with partialApplyOk true its known ops apply. */
    @Test
    fun newerVersionEventWithPartialApplyTrue_appliesKnownOps() = runTest {
        val c = "device-c"
        writeManifest(c, partialApplyOk = true)
        writeLog(listOf(event(c, seq = 1, v = 2)))

        assertTrue(HistoryReader(access).read().items.single().applicable)
    }

    /** HST-06 AC3: a device folder without device.json is display-only. */
    @Test
    fun deviceWithoutManifest_isDisplayOnly() = runTest {
        val c = "device-c"
        writeLog(listOf(event(c, seq = 1)))

        val result = HistoryReader(access).read()

        assertEquals(1, result.items.size)
        assertFalse(result.items.single().applicable)
    }

    /** HST-06 AC1: an unreadable manifest is treated like a missing one. */
    @Test
    fun unreadableManifest_isDisplayOnly() = runTest {
        val c = "device-c"
        writeManifest(c, raw = "{ truncated")
        writeLog(listOf(event(c, seq = 1)))

        assertFalse(HistoryReader(access).read().items.single().applicable)
    }

    /** HST-07 AC3: a missing `.allnotes/` is an empty result, not an error. */
    @Test
    fun missingHistoryFolder_isAnEmptyResult() = runTest {
        val result = HistoryReader(access).read()

        assertTrue(result.events.isEmpty())
        assertTrue(result.manifests.isEmpty())
        assertEquals(0, result.skippedLines)
        assertEquals(0L, result.maxLc)
        assertEquals(0L, result.maxSeqFor("any"))
    }

    /** HST-03: maxLc and maxSeqFor are what the writer's clone guard and Lamport clock build on. */
    @Test
    fun maxLcAndMaxSeqFor_reportTheHighestSeenValues() = runTest {
        val a = "device-a"
        val b = "device-b"
        writeManifest(a)
        writeManifest(b)
        writeLog(listOf(event(a, seq = 1, lc = 4), event(a, seq = 2, lc = 9)))
        writeLog(listOf(event(b, seq = 1, lc = 6), event(b, seq = 2, lc = 7)))

        val result = HistoryReader(access).read()

        assertEquals(9L, result.maxLc)
        assertEquals(2L, result.maxSeqFor(a))
        assertEquals(2L, result.maxSeqFor(b))
        assertEquals(0L, result.maxSeqFor("device-never-seen"))
    }
}
