package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.fs.vault.history.ApplyOutcome
import org.qosp.notes.data.sync.fs.vault.history.HistoryApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryReadResult
import org.qosp.notes.preferences.CloudService
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** The Release B hook inside a real sync: what appliers see, and that their changes reach the same pass. */
@RunWith(RobolectricTestRunner::class)
class HistoryApplyHookTest {

    private class Spy(private val onApply: suspend () -> Boolean = { false }) : HistoryApplier {
        val seen = mutableListOf<Pair<String, Set<String>>>()

        override suspend fun apply(history: HistoryReadResult, ownDeviceId: String, scannedPaths: Set<String>): ApplyOutcome {
            seen += ownDeviceId to scannedPaths
            return ApplyOutcome(changed = onApply())
        }
    }

    @Test
    fun appliers_seeTheScannedNotePaths_andThisFoldersOwnId() = runTest {
        val spy = Spy()
        val h = NoteRepositoryVaultHarness(this, historyAppliers = listOf(spy))
        h.insertAndSync(Note(title = "Plan", content = "body"))
        spy.seen.clear()

        h.syncNotes()

        val (ownId, scanned) = spy.seen.single()
        assertEquals(setOf("Plan.md"), scanned)
        assertEquals(h.historyIdentity.deviceId(h.backend.historyScope), ownId)
    }

    @Test
    fun aNoteBinnedByAnApplier_isNotPushedOrExportedInTheSamePass() = runTest {
        lateinit var h: NoteRepositoryVaultHarness
        var binned = false
        val spy = Spy {
            if (binned) return@Spy false
            val note = h.noteDao.getAll(org.qosp.notes.preferences.SortMethod.TITLE_ASC).first().single()
            h.noteDao.update(note.toEntity().copy(isDeleted = true, deletionDate = Instant.now().epochSecond))
            binned = true
            true
        }
        h = NoteRepositoryVaultHarness(this, historyAppliers = listOf(spy))
        // A note that was never exported: without the re-read after the applier, this pass would export it.
        h.insertLocal(Note(title = "Draft", content = "never exported"))

        h.syncNotes()

        assertTrue(h.vaultFs.notePaths().none { it.startsWith("Draft") })
        assertEquals(null, h.idMappingDao.getByLocalIdAndProvider(h.noteDao.getAll(org.qosp.notes.preferences.SortMethod.TITLE_ASC).first().single().id, CloudService.FILE_STORAGE)?.storageUri)
    }
}
