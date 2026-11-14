package org.qosp.notes.data.sync.fs.vault.history

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.core.ISyncBackend
import org.qosp.notes.data.sync.fs.vault.InMemoryVaultFs
import org.robolectric.RobolectricTestRunner

/** HST-13: a sync must not list and prune the history folder when it already pruned today. */
@RunWith(RobolectricTestRunner::class)
class HistoryPruneOncePerDayTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aSecondFlushOnTheSameUtcDay_doesNotPruneAgain() = runTest {
        val access = TestVaultFileAccess(InMemoryVaultFs(scopeId = "folder-a"))
        val identity = DeviceIdentity(
            TestHistoryPrefs().repository,
            model = "Pixel 9",
            defaultScope = access.historyScope,
        )
        val deviceId = identity.deviceId(access.historyScope)
        access.fs.writeText(eventsPath(deviceId, "2026-11"), eventLine(deviceId, at("2026-11-05T10:00:00Z"), seq = 2))
        access.fs.writeText(eventsPath(deviceId, "2026-07"), eventLine(deviceId, at("2026-07-05T10:00:00Z")))
        var now = at("2026-11-11T12:00:00Z")
        val service = service(access, identity, now = { now })

        service.flush()
        assertNull(access.text(eventsPath(deviceId, "2026-07")))

        access.fs.writeText(eventsPath(deviceId, "2026-06"), eventLine(deviceId, at("2026-06-05T10:00:00Z")))
        service.flush()
        assertNotNull(
            "the second flush pruned again on the same day",
            access.text(eventsPath(deviceId, "2026-06")),
        )

        now = at("2026-11-12T12:00:00Z")
        service.flush()
        assertNull("the next UTC day did not prune", access.text(eventsPath(deviceId, "2026-06")))
    }

    private fun service(
        access: TestVaultFileAccess,
        identity: DeviceIdentity,
        now: () -> Long,
    ): FolderHistoryService {
        val provider = mockk<BackendProvider> {
            every { syncProvider } returns MutableStateFlow<ISyncBackend?>(TestFolderBackend(access))
        }
        return FolderHistoryService(
            identity = identity,
            outbox = HistoryOutbox(tmp.newFolder("outbox").resolve(HistoryOutbox.FILE_NAME)),
            appVersion = "1.5.14",
            backendProvider = provider,
            retention = { 3 },
            now = now,
        )
    }

    private fun eventLine(deviceId: String, t: Long, seq: Long = 1): String = HistoryJson.json.encodeToString(
        HistoryEvent(
            eid = "$deviceId:$seq",
            dev = deviceId,
            seq = seq,
            lc = seq,
            t = t,
            op = HistoryOps.NOTE_BIN,
            id = "3f2a",
            path = "Plan.md",
            to = ".trash/Plan.md",
        ),
    ) + "\n"
}
