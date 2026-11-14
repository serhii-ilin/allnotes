package org.qosp.notes.data.sync.fs.vault.history

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.core.ISyncBackend
import org.qosp.notes.data.sync.fs.vault.InMemoryVaultFs

/** Recording is app-private, scoped to the current folder, and skips note events without an id. */
class FolderHistoryServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun recordSkipsNoteEventsWithoutAnId_andTagsTheCurrentFolderScope() = runTest {
        val access = TestVaultFileAccess(InMemoryVaultFs(scopeId = "folder-a"))
        val providerFlow = MutableStateFlow<ISyncBackend?>(TestFolderBackend(access))
        val provider = mockk<BackendProvider> { every { syncProvider } returns providerFlow }
        val outbox = HistoryOutbox(tmp.newFolder("outbox").resolve(HistoryOutbox.FILE_NAME))
        val service = FolderHistoryService(
            identity = DeviceIdentity(TestHistoryPrefs().repository),
            outbox = outbox,
            appVersion = "1.5.14",
            backendProvider = provider,
        )

        service.record(HistoryOps.NOTE_BIN, id = "3f2a", path = "Plan.md", to = ".trash/Plan.md")
        service.record(HistoryOps.NOTE_MOVE, path = "Plan.md", to = "Ideas.md")
        service.record(HistoryOps.TAG_CREATE, name = "work")

        assertEquals(listOf(HistoryOps.NOTE_BIN, HistoryOps.TAG_CREATE), outbox.readAll().map { it.op })
        assertEquals(listOf("folder-a", "folder-a"), outbox.readAll().map { it.scope })

        // Losing the folder keeps the queued actions for when it comes back.
        providerFlow.value = null
        service.flush()
        assertEquals(2, outbox.readAll().size)
    }

    @Test
    fun recordDoesNothing_whenNoFolderIsConfigured() = runTest {
        val provider = mockk<BackendProvider> {
            every { syncProvider } returns MutableStateFlow<ISyncBackend?>(null)
        }
        val outbox = HistoryOutbox(tmp.newFolder("outbox").resolve(HistoryOutbox.FILE_NAME))
        val service = FolderHistoryService(
            identity = DeviceIdentity(TestHistoryPrefs().repository),
            outbox = outbox,
            appVersion = "1.5.14",
            backendProvider = provider,
        )

        service.record(HistoryOps.TAG_CREATE, name = "work")
        service.record(HistoryOps.NOTE_BIN, id = "3f2a", path = "Plan.md", to = ".trash/Plan.md")

        assertTrue(outbox.readAll().isEmpty())
    }
}
