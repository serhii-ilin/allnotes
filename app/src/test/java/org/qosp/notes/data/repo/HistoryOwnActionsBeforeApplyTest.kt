package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.sync.core.ISyncBackend
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultPortableMetadata
import org.qosp.notes.data.sync.fs.vault.history.DeviceManifest
import org.qosp.notes.data.sync.fs.vault.history.HistoryApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryCatalogApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryEvent
import org.qosp.notes.data.sync.fs.vault.history.HistoryJson
import org.qosp.notes.data.sync.fs.vault.history.HistoryNoteApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryOps
import org.qosp.notes.data.sync.fs.vault.history.HistoryPaths
import org.qosp.notes.data.sync.fs.vault.history.VaultFileAccess
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * M3: a user action is only in the app-private outbox until a flush. Sync flushes it before the appliers run,
 * so this device's newest action shadows an older event from another device.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryOwnActionsBeforeApplyTest {

    private fun appliersFor(h: NoteRepositoryVaultHarness) = listOf(
        HistoryNoteApplier(
            h.noteDao,
            h.idMappingDao,
            h.db.reminderDao,
            h.notebookDao,
            VaultPortableMetadata(h.noteDao, h.notebookDao, h.db.tagDao, h.db.noteTagDao, h.db.reminderDao),
            h.backendProvider,
        ),
        HistoryCatalogApplier(h.notebookDao, h.noteDao, h.db.tagDao, h.db.noteTagDao),
    )

    private fun TestScope.vault(count: Int): MultiDeviceVault =
        MultiDeviceVault(this, count) { h -> appliersFor(h) }

    private suspend fun NoteRepositoryVaultHarness.noteByTitle(title: String): Note =
        noteDao.getAll(SortMethod.TITLE_ASC).first().single { it.title == title }

    private suspend fun NoteRepositoryVaultHarness.notebookNames(): List<String> =
        notebookDao.getAll().first().map { it.name }.sorted()

    private suspend fun NoteRepositoryVaultHarness.mappingState(noteId: Long): VaultMappingState? =
        idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE)
            ?.let { VaultMappingState.resolve(it.extras) }

    /**
     * Scenario: The user's own unflushed notebook create shadows an older remote delete.
     * Without the flush before the appliers, B's empty notebook is deleted by A's older event.
     */
    @Test
    fun aNewEmptyNotebookOnThisDevice_survivesAnOlderRemoteDelete() = runTest {
        val vault = vault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.insertAndSync(Note(title = "Keep", content = "Other"))

            a.notebookRepository.insert(Notebook("Trips"))
            a.folderHistoryService.flush()
            vault.syncthing(0, 1)
            b.syncNotes()
            assertTrue(b.notebookNames().contains("Trips"))

            a.notebookRepository.delete(a.notebookDao.getByName("Trips").first()!!)
            a.folderHistoryService.flush()
            vault.syncthing(0, 1)
            b.syncNotes()
            assertFalse("the remote delete removed B's notebook", b.notebookNames().contains("Trips"))

            // B creates the empty notebook again; the event is only in the outbox when sync starts.
            b.notebookRepository.insert(Notebook("Trips"))
            b.syncNotes()

            assertTrue(
                "B's newer create is flushed before the appliers and shadows A's older delete",
                b.notebookNames().contains("Trips"),
            )
        } finally {
            vault.close()
        }
    }

    /**
     * Scenario: a bin whose file move failed keeps the note binned although an older remote restore is the
     * latest event in the logs. The pending local bin must not be undone by an older event from another device.
     */
    @Test
    fun aLocalBinWithAFailedMove_isNotUndoneByAnOlderRemoteRestore() = runTest {
        val wired = mutableListOf<HistoryApplier>()
        val h = NoteRepositoryVaultHarness(this, historyAppliers = wired)
        wired += appliersFor(h)
        lateinit var provider: RefusingTrashBackend
        try {
            val plan = h.insertAndSync(Note(title = "Plan", content = "Body"))
            h.insertAndSync(Note(title = "Keep", content = "Other"))
            val uid = h.mappingState(plan)!!.noteUid
            h.writeForeignRestoreEvent(uid, "Plan.md")

            h.wrapBackend { backend -> RefusingTrashBackend(backend).also { provider = it } }
            // The folder refuses the move, so the bin stays pending and no bin event can be recorded yet.
            provider.refuseTrash = true
            h.repository.moveNotesToBin(h.getNote(plan)!!, sync = true)
            h.flushRemoteActions()
            assertTrue(h.getNote(plan)!!.isDeleted)
            assertTrue(h.mappingState(plan)!!.pendingVaultDelete)
            assertTrue("no bin event is recorded before the move", h.historyOutbox.readAll().isEmpty())

            h.repository.syncNotes()

            assertTrue("the pending local bin is not undone by an older remote restore", h.getNote(plan)!!.isDeleted)
            assertTrue(h.mappingState(plan)!!.pendingVaultDelete)

            // Once the move can happen, the bin is recorded and still wins over the older remote restore.
            provider.refuseTrash = false
            h.repository.syncNotes()
            assertTrue(h.getNote(plan)!!.isDeleted)
            assertFalse(h.mappingState(plan)!!.pendingVaultDelete)
            assertTrue(h.vaultFs.isFile(".trash/Plan.md"))
            assertTrue(
                "the local bin event was flushed",
                h.folderHistory.load().entries.any { it.op == HistoryOps.NOTE_BIN },
            )
        } finally {
            h.close()
        }
    }

    private fun NoteRepositoryVaultHarness.writeForeignRestoreEvent(uid: String, path: String) {
        val device = "device-d"
        val t = System.currentTimeMillis()
        vaultFs.writeText(
            HistoryPaths.manifestPath(device),
            HistoryJson.json.encodeToString(
                DeviceManifest(
                    deviceId = device,
                    name = "Other",
                    appVersion = "1.0",
                    writes = HistoryOps.ALL_V1,
                    partialApplyOk = true,
                ),
            ),
        )
        vaultFs.writeText(
            "${HistoryPaths.deviceDir(device)}/${HistoryPaths.monthlyEventsFile(t)}",
            HistoryJson.json.encodeToString(
                HistoryEvent(
                    v = 1,
                    eid = "$device:1",
                    dev = device,
                    seq = 1,
                    lc = 1,
                    t = t,
                    op = HistoryOps.NOTE_RESTORE,
                    id = uid,
                    path = path,
                ),
            ) + "\n",
        )
    }

    /** Delegates everything but can refuse the note-file move, leaving `pendingVaultDelete` set. */
    private class RefusingTrashBackend(private val delegate: ISyncBackend) : ISyncBackend by delegate,
        VaultFileAccess by (delegate as VaultFileAccess) {

        @Volatile
        var refuseTrash: Boolean = false

        override suspend fun trashNote(mapping: IdMapping): String? {
            if (refuseTrash) throw IOException("vault is read-only")
            return delegate.trashNote(mapping)
        }
    }
}
