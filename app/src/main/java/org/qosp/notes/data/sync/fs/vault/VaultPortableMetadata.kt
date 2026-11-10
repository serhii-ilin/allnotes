package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import org.qosp.notes.data.dao.NoteDao
import org.qosp.notes.data.dao.NoteTagDao
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.dao.ReminderDao
import org.qosp.notes.data.dao.TagDao
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.model.NoteTagJoin
import org.qosp.notes.data.model.Reminder
import org.qosp.notes.data.model.Tag
import org.qosp.notes.data.sync.core.SyncNote

class VaultPortableMetadata(
    private val noteDao: NoteDao,
    private val notebookDao: NotebookDao,
    private val tagDao: TagDao,
    private val noteTagDao: NoteTagDao,
    private val reminderDao: ReminderDao,
) {

    suspend fun applyFromRemote(noteId: Long, syncNote: SyncNote) {
        val doc = VaultNoteDocument.parse(syncNote.content.orEmpty())
        val fm = doc.frontMatter
        applyNotebook(noteId, syncNote.idStr, fm)
        reconcileTags(noteId, fm)
        reconcileReminders(noteId, fm)
    }

    /**
     * Makes Room's reminders match the file. A file without a `reminders` key (written by another tool) never
     * removes any; expired entries are not imported. Alarms for removed rows are dropped when they fire.
     */
    private suspend fun reconcileReminders(noteId: Long, fm: VaultFrontMatter) {
        val desired = fm.reminders ?: return
        val now = java.time.Instant.now().epochSecond
        val wanted = desired.filter { it.date > now }.toSet()
        val current = reminderDao.getByNoteId(noteId).firstOrNull().orEmpty()
        for (reminder in current) {
            if (VaultReminder(reminder.name, reminder.date) !in wanted) reminderDao.deleteById(reminder.id)
        }
        val have = current.map { VaultReminder(it.name, it.date) }.toSet()
        for (entry in wanted - have) {
            reminderDao.insert(Reminder(name = entry.name, noteId = noteId, date = entry.date))
        }
    }

    private suspend fun applyNotebook(noteId: Long, relativePath: String, fm: VaultFrontMatter) {
        when {
            fm.notebook != null && fm.notebook.isBlank() -> {
                val entity = noteDao.getById(noteId).firstOrNull()?.toEntity() ?: return
                if (entity.notebookId != null) {
                    noteDao.update(entity.copy(notebookId = null))
                }
            }
            fm.notebook != null -> {
                assignNotebook(noteId, fm.notebook)
            }
            VaultPaths.parentPath(relativePath).isNotBlank() -> {
                val segment = VaultPaths.parentPath(relativePath).substringAfterLast('/')
                if (segment.isNotBlank()) assignNotebook(noteId, segment)
            }
        }
    }

    private suspend fun assignNotebook(noteId: Long, name: String) {
        val notebookId = notebookDao.getByName(name).firstOrNull()?.id
            ?: notebookDao.insert(Notebook(name = name))
        val entity = noteDao.getById(noteId).firstOrNull()?.toEntity() ?: return
        if (entity.notebookId == notebookId) return
        noteDao.update(entity.copy(notebookId = notebookId))
    }

    private suspend fun reconcileTags(noteId: Long, fm: VaultFrontMatter) {
        if (fm.tags == null) return
        val desired = fm.tags.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val current = noteTagDao.getByNoteId(noteId).firstOrNull().orEmpty()
        val currentByName = current.associateBy { it.name }
        for (tag in current) {
            if (tag.name !in desired) {
                noteTagDao.delete(NoteTagJoin(tagId = tag.id, noteId = noteId))
            }
        }
        for (name in desired) {
            val tagId = currentByName[name]?.id
                ?: tagDao.getByName(name).firstOrNull()?.id
                ?: tagDao.insert(Tag(name = name))
            noteTagDao.insert(NoteTagJoin(tagId = tagId, noteId = noteId))
        }
    }
}
