package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import org.qosp.notes.data.dao.NoteDao
import org.qosp.notes.data.dao.NoteTagDao
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.dao.TagDao
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.model.NoteTagJoin
import org.qosp.notes.data.model.Tag
import org.qosp.notes.data.sync.core.SyncNote

/**
 * Applies portable YAML metadata to Room (tags, notebook). Does not trigger remote sync.
 */
class VaultPortableMetadata(
    private val noteDao: NoteDao,
    private val notebookDao: NotebookDao,
    private val tagDao: TagDao,
    private val noteTagDao: NoteTagDao,
) {

    suspend fun applyFromRemote(noteId: Long, syncNote: SyncNote) {
        val doc = VaultNoteDocument.parse(syncNote.content.orEmpty())
        val fm = doc.frontMatter
        applyNotebook(noteId, syncNote.idStr, fm)
        applyTags(noteId, fm)
    }

    private suspend fun applyNotebook(noteId: Long, relativePath: String, fm: VaultFrontMatter) {
        val notebookName = fm.notebook?.takeIf { it.isNotBlank() }
            ?: VaultPaths.parentPath(relativePath).takeIf { it.isNotBlank() }
            ?: return
        val segment = notebookName.substringAfterLast('/')
        if (segment.isBlank()) return
        val notebookId = notebookDao.getByName(segment).firstOrNull()?.id
            ?: notebookDao.insert(Notebook(name = segment))
        val entity = noteDao.getById(noteId).firstOrNull()?.toEntity() ?: return
        if (entity.notebookId == notebookId) return
        noteDao.update(entity.copy(notebookId = notebookId))
    }

    private suspend fun applyTags(noteId: Long, fm: VaultFrontMatter) {
        if (fm.tags.isEmpty()) return
        val existing = noteTagDao.getByNoteId(noteId).firstOrNull().orEmpty().associateBy { it.name }
        for (name in fm.tags) {
            if (name.isBlank()) continue
            val tagId = existing[name]?.id
                ?: tagDao.getByName(name).firstOrNull()?.id
                ?: tagDao.insert(Tag(name = name))
            val join = NoteTagJoin(tagId = tagId, noteId = noteId)
            runCatching { noteTagDao.insert(join) }
        }
    }
}
