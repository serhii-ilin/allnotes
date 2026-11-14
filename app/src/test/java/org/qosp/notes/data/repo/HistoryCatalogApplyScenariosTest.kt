package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.model.Tag
import org.qosp.notes.data.sync.fs.vault.history.HistoryCatalogApplier
import org.qosp.notes.data.sync.fs.vault.history.HistoryReader
import org.qosp.notes.data.sync.fs.vault.history.StoredMismatch
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner

/** HST-10 through the real repository: other devices' notebook and tag actions reach this device. */
@RunWith(RobolectricTestRunner::class)
class HistoryCatalogApplyScenariosTest {

    private fun TestScope.catalogVault(count: Int) = MultiDeviceVault(this, count) { harness ->
        listOf(
            HistoryCatalogApplier(
                harness.notebookDao,
                harness.noteDao,
                harness.db.tagDao,
                harness.db.noteTagDao,
            ),
        )
    }

    private suspend fun NoteRepositoryVaultHarness.notebookNames(): List<String> =
        notebookDao.getAll().first().map { it.name }.sorted()

    private suspend fun NoteRepositoryVaultHarness.tagNames(): List<String> =
        db.tagDao.getAll().first().map { it.name }.sorted()

    private suspend fun NoteRepositoryVaultHarness.ownEvents() =
        HistoryReader(backend).read().events.filter { it.dev == deviceId() }

    private suspend fun NoteRepositoryVaultHarness.mismatches(): List<StoredMismatch> =
        historyMismatches.readAll().sortedBy { it.eid }

    private suspend fun NoteRepositoryVaultHarness.flushHistory() = folderHistoryService.flush()

    /** Scenario: An empty notebook travels (HST-10 AC1). */
    @Test
    fun anEmptyNotebookTravels() = runTest {
        val vault = catalogVault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.notebookRepository.insert(Notebook("Trips"))
            a.flushHistory()

            vault.syncthing(0, 1)
            b.syncNotes()

            assertEquals(listOf("Trips"), b.notebookNames())
        } finally {
            vault.close()
        }
    }

    /** HST-10 AC1: the same applies to an empty tag. */
    @Test
    fun anEmptyTagTravels() = runTest {
        val vault = catalogVault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.tagRepository.insert(Tag("work"))
            a.flushHistory()

            vault.syncthing(0, 1)
            b.syncNotes()

            assertEquals(listOf("work"), b.tagNames())
        } finally {
            vault.close()
        }
    }

    /** HST-10 AC2: a notebook rename on A renames the same row on B. */
    @Test
    fun aNotebookRenameTravels() = runTest {
        val vault = catalogVault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.notebookRepository.insert(Notebook("Trips"))
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()
            val before = b.notebookDao.getByName("Trips").first()!!.id

            val onA = a.notebookDao.getByName("Trips").first()!!
            a.notebookRepository.update(onA.copy(name = "Travel"))
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()

            assertNull(b.notebookDao.getByName("Trips").first())
            // Events are folded by name, never replayed by renaming a row in place.
            assertNotEquals(before, b.notebookDao.getByName("Travel").first()!!.id)
        } finally {
            vault.close()
        }
    }

    /** HST-10 AC2: a tag rename follows the same rule. */
    @Test
    fun aTagRenameTravels() = runTest {
        val vault = catalogVault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.tagRepository.insert(Tag("work"))
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()
            val before = b.db.tagDao.getByName("work").first()!!.id

            val onA = a.db.tagDao.getByName("work").first()!!
            a.tagRepository.update(onA.copy(name = "job"))
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()

            assertNull(b.db.tagDao.getByName("work").first())
            // Events are folded by name, never replayed by renaming a row in place.
            assertNotEquals(before, b.db.tagDao.getByName("job").first()!!.id)
        } finally {
            vault.close()
        }
    }

    /** HST-10 AC2: a rename whose new name already exists here folds the unused old notebook into it. */
    @Test
    fun aNotebookRenameToAnExistingName_foldsTheUnusedOldOneIntoIt() = runTest {
        val vault = catalogVault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.notebookRepository.insert(Notebook("Trips"))
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()
            b.notebookDao.insert(Notebook("Travel"))

            val onA = a.notebookDao.getByName("Trips").first()!!
            a.notebookRepository.update(onA.copy(name = "Travel"))
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()

            assertEquals(listOf("Travel"), b.notebookNames())
            assertEquals(a.notebookNames(), b.notebookNames())
            assertTrue(b.mismatches().isEmpty())
        } finally {
            vault.close()
        }
    }

    /** HST-10 AC2: the tag variant: the unused old tag folds into the existing one. */
    @Test
    fun aTagRenameToAnExistingName_foldsTheUnusedOldOneIntoIt() = runTest {
        val vault = catalogVault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.tagRepository.insert(Tag("work"))
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()
            b.db.tagDao.insert(Tag("job"))

            val onA = a.db.tagDao.getByName("work").first()!!
            a.tagRepository.update(onA.copy(name = "job"))
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()

            assertEquals(listOf("job"), b.tagNames())
            assertEquals(a.tagNames(), b.tagNames())
            assertTrue(b.mismatches().isEmpty())
        } finally {
            vault.close()
        }
    }

    /** Scenario: A notebook delete does not remove a notebook still in use (HST-10 AC3). */
    @Test
    fun aNotebookDeleteDoesNotRemoveANotebookStillInUse() = runTest {
        val vault = catalogVault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.notebookRepository.insert(Notebook("Work"))
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()

            val workOnB = b.notebookDao.getByName("Work").first()!!
            val noteId = b.insertAndSync(Note(title = "Plan", content = "Body", notebookId = workOnB.id))

            val workOnA = a.notebookDao.getByName("Work").first()!!
            a.notebookRepository.delete(workOnA)
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()

            assertNotNull(b.notebookDao.getByName("Work").first())
            assertFalse(b.getNote(noteId)!!.isDeleted)
            // A used notebook is left in place without a mismatch; it goes once its last note does.
            assertTrue(b.mismatches().isEmpty())

            b.repository.updateNotes(b.getNote(noteId)!!.copy(notebookId = null), sync = true)
            b.flushRemoteActions()
            b.syncNotes()

            assertNull(b.notebookDao.getByName("Work").first())
            assertTrue(b.mismatches().isEmpty())
        } finally {
            vault.close()
        }
    }

    /** HST-10 AC1/AC2: a notebook renamed before the first delivery arrives under its new name, once. */
    @Test
    fun aNotebookRenamedBeforeTheFirstDelivery_arrivesUnderItsNewName() = runTest {
        val vault = catalogVault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.notebookRepository.insert(Notebook("Trips"))
            val tripsOnA = a.notebookDao.getByName("Trips").first()!!
            a.notebookRepository.update(tripsOnA.copy(name = "Travel"))
            a.flushHistory()

            vault.syncthing(0, 1)
            b.syncNotes()
            assertEquals(listOf("Travel"), b.notebookNames())

            b.syncNotes()
            assertEquals(listOf("Travel"), b.notebookNames())
        } finally {
            vault.close()
        }
    }

    /** HST-10 AC2: renaming a notebook does not push its notes; the delivered file move relinks them. */
    @Test
    fun aNotebookRenameDoesNotPushItsNotes() = runTest {
        val vault = catalogVault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.notebookRepository.insert(Notebook("Work"))
            val workOnA = a.notebookDao.getByName("Work").first()!!
            a.insertAndSync(Note(title = "Plan", content = "Body", notebookId = workOnA.id))
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()
            val noteOnB = b.noteDao.getAll(SortMethod.TITLE_ASC).first().single()

            a.notebookRepository.update(workOnA.copy(name = "Travel"))
            a.flushRemoteActions()
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()

            val updated = b.getNote(noteOnB.id)!!
            assertEquals("Body", updated.content.trim())
            assertEquals("Travel", b.notebookDao.getById(updated.notebookId!!).first()!!.name)
            assertEquals(
                "Travel/Plan.md",
                b.idMappingDao.getByLocalIdAndProvider(noteOnB.id, CloudService.FILE_STORAGE)!!.storageUri,
            )
            assertEquals(1, b.localNoteCount())
            assertTrue(b.conflictFiles().isEmpty())
        } finally {
            vault.close()
        }
    }

    /** HST-10 AC3 for tags: a tag still carried by a note here is kept and listed as a mismatch. */
    @Test
    fun aTagDeleteDoesNotRemoveATagStillInUse() = runTest {
        val vault = catalogVault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.tagRepository.insert(Tag("work"))
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()

            val workOnB = b.db.tagDao.getByName("work").first()!!
            val noteId = b.insertAndSync(Note(title = "Plan", content = "Body"))
            b.tagRepository.addTagToNote(workOnB.id, noteId, shouldSync = false)

            a.tagRepository.delete(a.db.tagDao.getByName("work").first()!!)
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()

            assertNotNull(b.db.tagDao.getByName("work").first())
            // A used tag is left in place without a mismatch; it goes once its last note does.
            assertTrue(b.mismatches().isEmpty())

            b.tagRepository.deleteTagFromNote(workOnB.id, noteId, shouldSync = false)
            b.syncNotes()

            assertNull(b.db.tagDao.getByName("work").first())
            assertTrue(b.mismatches().isEmpty())
        } finally {
            vault.close()
        }
    }

    /** HST-10: applying events uses the DAOs, so this device's own log gains nothing. */
    @Test
    fun applying_doesNotRecordNewHistoryEvents() = runTest {
        val vault = catalogVault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.notebookRepository.insert(Notebook("Trips"))
            a.flushHistory()
            vault.syncthing(0, 1)
            val before = b.ownEvents().map { it.eid }

            b.syncNotes()

            assertNotNull(b.notebookDao.getByName("Trips").first())
            assertEquals(before, b.ownEvents().map { it.eid })
            assertTrue(b.historyOutbox.readAll().isEmpty())
        } finally {
            vault.close()
        }
    }

    /** HST-10 AC4: applying the same history twice changes nothing the second time. */
    @Test
    fun applyingTheSameHistoryTwice_changesNothingTheSecondTime() = runTest {
        val vault = catalogVault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.notebookRepository.insert(Notebook("Trips"))
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()
            val createdId = b.notebookDao.getByName("Trips").first()!!.id

            b.syncNotes()
            assertEquals(createdId, b.notebookDao.getByName("Trips").first()!!.id)

            val onA = a.notebookDao.getByName("Trips").first()!!
            a.notebookRepository.update(onA.copy(name = "Travel"))
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()
            val renamedId = b.notebookDao.getByName("Travel").first()!!.id
            // The target fold creates the new name and removes the unused old row; it never renames in place.
            assertNotEquals(createdId, renamedId)

            b.syncNotes()
            assertEquals(renamedId, b.notebookDao.getByName("Travel").first()!!.id)
            assertNull(b.notebookDao.getByName("Trips").first())

            a.notebookRepository.delete(a.notebookDao.getByName("Travel").first()!!)
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()
            assertNull(b.notebookDao.getByName("Travel").first())

            b.syncNotes()
            assertNull(b.notebookDao.getByName("Travel").first())
        } finally {
            vault.close()
        }
    }

    /** HST-11: with the feature off, another device's catalog events change nothing here. */
    @Test
    fun switchedOff_appliesNothing() = runTest {
        val vault = catalogVault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            b.historyApplySettings.setEnabled(false)
            a.notebookRepository.insert(Notebook("Trips"))
            a.flushHistory()
            vault.syncthing(0, 1)

            b.syncNotes()
            assertNull(b.notebookDao.getByName("Trips").first())
            assertTrue(b.mismatches().isEmpty())

            b.historyApplySettings.setEnabled(true)
            b.syncNotes()
            assertNotNull(b.notebookDao.getByName("Trips").first())
        } finally {
            vault.close()
        }
    }

    /** HST-10: a later action of this device shadows an older event from another device for the same name. */
    @Test
    fun aLaterLocalRecreate_shadowsAnOlderRemoteDelete() = runTest {
        val vault = catalogVault(2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.notebookRepository.insert(Notebook("Trips"))
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()

            a.notebookRepository.delete(a.notebookDao.getByName("Trips").first()!!)
            a.flushHistory()
            vault.syncthing(0, 1)
            b.syncNotes()
            assertNull(b.notebookDao.getByName("Trips").first())

            // B re-creates the notebook after seeing A's delete, so B's own event is the later one.
            b.notebookRepository.insert(Notebook("Trips"))
            b.flushHistory()
            b.syncNotes()

            assertNotNull(b.notebookDao.getByName("Trips").first())
            assertTrue(b.mismatches().isEmpty())
        } finally {
            vault.close()
        }
    }
}
