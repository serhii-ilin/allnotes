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

/** Review hardening for the reader: clone evidence, hostile clocks and a manifest outside its own folder. */
class HistoryReaderHardeningTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val access = TestVaultFileAccess(InMemoryVaultFs())

    private fun event(
        dev: String,
        seq: Long,
        lc: Long = seq,
        op: String = HistoryOps.TAG_CREATE,
        name: String? = "work",
        t: Long = at("2026-11-10T12:00:00Z"),
    ): HistoryEvent = HistoryEvent(
        eid = "$dev:$seq",
        dev = dev,
        seq = seq,
        lc = lc,
        t = t,
        op = op,
        name = name,
    )

    private fun writeLog(events: List<HistoryEvent>, deviceId: String = events.first().dev, fileName: String? = null) {
        val path = "${HistoryPaths.deviceDir(deviceId)}/${fileName ?: HistoryPaths.monthlyEventsFile(events.first().t)}"
        val text = events.joinToString("\n") { HistoryJson.json.encodeToString(it) } + "\n"
        access.fs.writeText(path, text)
    }

    private fun writeManifest(folder: String, declaredId: String = folder) {
        val manifest = DeviceManifest(
            deviceId = declaredId,
            name = "Pixel 9 · ${declaredId.take(6)}",
            appVersion = "1.5.14",
            writes = HistoryOps.ALL_V1,
            partialApplyOk = true,
            lastSeenDay = "2026-11-10",
        )
        access.fs.writeText(HistoryPaths.manifestPath(folder), HistoryJson.json.encodeToString(manifest))
    }

    /** HST-02: two clones writing one `eid` with different content is exactly the evidence the writer needs. */
    @Test
    fun theSameEidWithDifferentContent_isReportedAsCloneEvidence() = runTest {
        val device = "device-a"
        writeManifest(device)
        writeLog(listOf(event(device, seq = 1, name = "first")))
        writeLog(
            listOf(event(device, seq = 1, name = "other")),
            deviceId = device,
            fileName = "events-2026-11.sync-conflict-20261110-120000-ABCDEF.jsonl",
        )

        val result = HistoryReader(access).read()

        assertEquals(setOf("$device:1"), result.conflictingEids)
        // The merged history still keeps exactly one line.
        assertEquals(1, result.events.size)
    }

    /** HST-03: a corrupt clock must not overflow this device's next Lamport tick. */
    @Test
    fun aHostileLamportClock_isIgnoredForMaxLc() = runTest {
        val device = "device-a"
        writeManifest(device)
        writeLog(
            listOf(
                event(device, seq = 1, lc = Long.MAX_VALUE),
                event(device, seq = 2, lc = 7),
            ),
        )

        val result = HistoryReader(access).read()

        assertEquals(7L, result.maxLc)
        assertTrue(result.maxLc < Long.MAX_VALUE / 2)
    }

    /** HST-06: only a manifest in its own folder may make that folder's events applicable. */
    @Test
    fun aManifestCopiedIntoAnotherDevicesFolder_isNotTrustedForIt() = runTest {
        val a = "device-a"
        val b = "device-b"
        writeManifest(a)
        writeLog(listOf(event(a, seq = 1)))
        // Syncthing (or a user) copies A's manifest into B's folder; B has no manifest of its own.
        writeManifest(b, declaredId = a)
        writeLog(listOf(event(b, seq = 1)))

        val result = HistoryReader(access).read()

        assertFalse(result.manifests.containsKey(b))
        assertTrue(result.items.single { it.event.dev == a }.applicable)
        assertFalse(result.items.single { it.event.dev == b }.applicable)
    }
}
