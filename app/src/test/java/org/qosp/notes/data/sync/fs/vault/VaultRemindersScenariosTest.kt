package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Reminder
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.preferences.CloudService
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class VaultRemindersScenariosTest {
    private val future = Instant.now().epochSecond + 86_400

    private suspend fun NoteRepositoryVaultHarness.pathOf(id: Long) =
        idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!

    private suspend fun NoteRepositoryVaultHarness.reminders(id: Long) = db.reminderDao.getByNoteId(id).first()

    @Test
    fun futureReminder_isWrittenToTheFile_onceAndCleanly() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Call", content = "x"))
        h.db.reminderDao.insert(Reminder("Call Bob", id, future))
        h.syncNotes()
        val raw = h.vaultFs.readText(h.pathOf(id)).orEmpty()
        assertTrue(raw, raw.contains("name: \"Call Bob\""))
        assertEquals(listOf(VaultReminder("Call Bob", future)), VaultNoteDocument.parse(raw).frontMatter.reminders)
        // A second sync neither rewrites the file nor makes conflict copies.
        h.syncNotes()
        assertEquals(raw, h.vaultFs.readText(h.pathOf(id)))
        assertTrue(h.conflictFiles().isEmpty())
    }

    @Test
    fun expiredReminder_isNotExported() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Old", content = "x"))
        h.db.reminderDao.insert(Reminder("past", id, 1000L))
        h.updateAndSync(h.getNote(id)!!.copy(content = "y", modifiedDate = h.getNote(id)!!.modifiedDate + 1))
        assertFalse(h.vaultFs.readText(h.pathOf(id)).orEmpty().contains("reminders"))
    }

    @Test
    fun freshDatabase_importsReminderFromFile_skippingExpired() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val doc = VaultNoteDocument.fromNote(Note(title = "Imp", content = "x"), "Imp.md", exportNotebookExplicitly = true)
        val fm = doc.frontMatter.copy(
            reminders = listOf(VaultReminder("Soon", future), VaultReminder("Gone", 1000L)),
        )
        h.vaultFs.writeText("Imp.md", doc.copy(frontMatter = fm).renderFullText())
        h.syncNotes()
        val id = h.repository.getAll().first().single().id
        assertEquals(listOf("Soon"), h.reminders(id).map { it.name })
        assertEquals(future, h.reminders(id).single().date)
    }

    @Test
    fun clearingTheLastReminder_writesAnEmptyKey() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Call", content = "x"))
        h.db.reminderDao.insert(Reminder("Call Bob", id, future))
        h.syncNotes()
        h.db.reminderDao.deleteById(h.reminders(id).single().id)
        h.syncNotes()
        val raw = h.vaultFs.readText(h.pathOf(id)).orEmpty()
        assertTrue(raw, raw.contains("reminders: []"))
        assertFalse(raw.contains("Call Bob"))
    }

    @Test
    fun restore_putsRemindersFromTheFileBack() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Call", content = "x"))
        h.db.reminderDao.insert(Reminder("Call Bob", id, future))
        h.syncNotes()
        h.binAndSync(h.getNote(id)!!)
        assertTrue(h.reminders(id).isEmpty())

        h.restoreAndSync(h.getNote(id)!!)

        assertEquals(listOf("Call Bob"), h.reminders(id).map { it.name })
        assertTrue(h.vaultFs.readText(h.pathOf(id)).orEmpty().contains("Call Bob"))
        assertFalse(h.getNote(id)!!.isDeleted)
    }

    @Test
    fun removingReminderFromFile_deletesTheRow() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Call", content = "x"))
        h.db.reminderDao.insert(Reminder("Call Bob", id, future))
        h.syncNotes()
        val path = h.pathOf(id)
        // Obsidian-style edit: the key stays but its entries are removed.
        val raw = h.vaultFs.readText(path).orEmpty()
        h.vaultFs.writeText(path, raw.replace(Regex("reminders:\n(  .*\n)+"), "reminders: []\n"))
        h.syncNotes()
        assertTrue(h.reminders(id).isEmpty())
    }

    @Test
    fun fileWithoutRemindersKey_keepsRoomReminders() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Call", content = "x"))
        h.db.reminderDao.insert(Reminder("Keep me", id, future))
        h.syncNotes()
        val path = h.pathOf(id)
        // Another tool rewrites the file without a reminders key.
        h.vaultFs.writeText(path, "---\ntitle: \"Call\"\n---\nedited elsewhere\n")
        h.syncNotes()
        assertTrue(h.getNote(id)!!.content.contains("edited elsewhere"))
        assertEquals(listOf("Keep me"), h.reminders(id).map { it.name })
    }

    @Test
    fun upgrade_noteWithExistingReminder_exportsOnceWithoutConflict() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val tree = h.tree()
        val note = Note(title = "Old", content = "Body", modifiedDate = 1L)
        h.vaultFs.writeText("Old.md", VaultNoteDocument.fromNote(note, "Old.md", exportNotebookExplicitly = true).renderFullText())
        val id = h.insertLocal(note)
        // Baseline as an earlier version wrote it: no reminders in the file or the hash.
        h.upsertMapping(tree.mappingWithAttachments(id, "Old.md", h.getNote(id)!!, emptyList()))
        h.db.reminderDao.insert(Reminder("Pre-existing", id, future))
        h.syncNotes()
        h.syncNotes()
        assertTrue(h.vaultFs.readText("Old.md").orEmpty().contains("Pre-existing"))
        assertTrue(h.conflictFiles().isEmpty())
        assertEquals(listOf("Pre-existing"), h.reminders(id).map { it.name })
    }
}
