package org.qosp.notes.data.sync.fs.vault.history

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.core.ISyncBackend
import org.qosp.notes.data.sync.fs.vault.InMemoryVaultFs
import org.robolectric.RobolectricTestRunner

/** HST-02/HST-05: identity, sequence and queued actions never cross a folder switch. */
@RunWith(RobolectricTestRunner::class)
class HistoryFolderScopeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val nowMs = at("2026-11-10T12:00:00Z")

    @Test
    fun eachFolderKeepsItsOwnIdAndSeq_andAnotherFoldersQueueNeverLandsThere() = runTest {
        val prefs = TestHistoryPrefs()
        val identity = DeviceIdentity(prefs.repository, model = "Pixel 9")
        val outbox = HistoryOutbox(tmp.newFolder("outbox").resolve(HistoryOutbox.FILE_NAME))
        val a = TestVaultFileAccess(InMemoryVaultFs(scopeId = "folder-a"))
        val b = TestVaultFileAccess(InMemoryVaultFs(scopeId = "folder-b"))
        val providerFlow = MutableStateFlow<ISyncBackend?>(TestFolderBackend(a))
        val provider = mockk<BackendProvider> { every { syncProvider } returns providerFlow }
        val service = FolderHistoryService(identity, outbox, "1.5.14", provider, now = { nowMs })

        service.record(HistoryOps.TAG_CREATE, name = "first")
        service.flush()
        val idA = identity.deviceId(a.historyScope)
        assertEquals(listOf("first"), a.events(eventsPath(idA)).map { it.name })

        // Recorded in A, then the user switches folders before it is flushed.
        service.record(HistoryOps.TAG_RENAME, name = "first", to = "pending-a")
        providerFlow.value = TestFolderBackend(b)
        service.record(HistoryOps.TAG_CREATE, name = "on-b")
        service.flush()

        val idB = identity.deviceId(b.historyScope)
        assertNotEquals(idA, idB)
        assertEquals(listOf("on-b"), b.events(eventsPath(idB)).map { it.name })
        assertEquals(listOf(1L), b.events(eventsPath(idB)).map { it.seq })
        assertTrue(b.events(eventsPath(idB)).none { it.dev == idA })
        assertTrue(outbox.readAll().isEmpty())

        // Back in A: the old id and sequence continue without a gap.
        providerFlow.value = TestFolderBackend(a)
        service.record(HistoryOps.TAG_CREATE, name = "second-a")
        service.flush()

        assertEquals(idA, a.events(eventsPath(idA)).first().dev)
        assertEquals(listOf(1L, 2L), a.events(eventsPath(idA)).map { it.seq })
        assertEquals(listOf("first", "second-a"), a.events(eventsPath(idA)).map { it.name })
        assertEquals(idB, b.events(eventsPath(idB)).single().dev)
    }

    @Test
    fun anEntryWithoutAScope_isDroppedAtFlush() = runTest {
        val a = TestVaultFileAccess(InMemoryVaultFs(scopeId = "folder-a"))
        val provider = mockk<BackendProvider> {
            every { syncProvider } returns MutableStateFlow<ISyncBackend?>(TestFolderBackend(a))
        }
        val outbox = HistoryOutbox(tmp.newFolder("outbox").resolve(HistoryOutbox.FILE_NAME))
        val identity = DeviceIdentity(TestHistoryPrefs().repository, model = "Pixel 9")
        val service = FolderHistoryService(identity, outbox, "1.5.14", provider, now = { nowMs })
        outbox.append(PendingHistoryEvent(op = HistoryOps.TAG_CREATE, name = "legacy", t = nowMs))

        service.flush()

        val idA = identity.deviceId(a.historyScope)
        assertTrue(a.events(eventsPath(idA)).isEmpty())
        assertTrue(outbox.readAll().isEmpty())
    }
}
