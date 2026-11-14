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
     * A moved file keeps the folder it landed in. The notebook follows that folder so a later push does not
     * move the file back. A dot-folder or other segment that cannot be a notebook name creates no notebook:
     * the file stays where it is and the note has none.
     */
    suspend fun applyFolderNotebook(noteId: Long, relativePath: String) {
        val parent = VaultPaths.parentPath(relativePath)
        val segment = parent.substringAfterLast('/')
        if (segment.isBlank()) clearNotebook(noteId) else applyNotebookName(noteId, segment)
    }

    /**
     * A renamed file keeps its new name. The title follows that name when it still described the old file,
     * so a later push does not rename the file back. A title edited in the app still wins.
     */
    suspend fun applyFileName(noteId: Long, oldPath: String, newPath: String) {
        val newBase = VaultPaths.noteTitleFromPath(newPath)
        if (VaultPaths.noteBaseName(oldPath).equals(newBase, ignoreCase = true)) return
        val entity = noteDao.getById(noteId).firstOrNull()?.toEntity() ?: return
        val titledAsOld = VaultPaths.titleToNotePath(
            entity.title,
            entity.isMarkdownEnabled,
            VaultPaths.parentPath(oldPath),
        )
        if (!titledAsOld.equals(oldPath, ignoreCase = true)) return
        if (entity.title.equals(newBase, ignoreCase = true)) return
        noteDao.update(entity.copy(title = newBase))
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
            fm.notebook != null && fm.notebook.isBlank() -> clearNotebook(noteId)
            fm.notebook != null -> applyNotebookName(noteId, fm.notebook)
            VaultPaths.parentPath(relativePath).isNotBlank() -> {
                val segment = VaultPaths.parentPath(relativePath).substringAfterLast('/')
                if (segment.isNotBlank()) applyNotebookName(noteId, segment)
            }
        }
    }

    /** A name from a folder or front matter that is not a valid segment never creates a notebook. */
    private suspend fun applyNotebookName(noteId: Long, name: String) {
        if (VaultPaths.validateSegmentName(name) != null) {
            clearNotebook(noteId)
            return
        }
        val notebookId = notebookDao.getByName(name).firstOrNull()?.id
            ?: notebookDao.insert(Notebook(name = name))
        val entity = noteDao.getById(noteId).firstOrNull()?.toEntity() ?: return
        if (entity.notebookId == notebookId) return
        noteDao.update(entity.copy(notebookId = notebookId))
    }

    private suspend fun clearNotebook(noteId: Long) {
        val entity = noteDao.getById(noteId).firstOrNull()?.toEntity() ?: return
        if (entity.notebookId != null) noteDao.update(entity.copy(notebookId = null))
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
