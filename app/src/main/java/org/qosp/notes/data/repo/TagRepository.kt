package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.qosp.notes.data.dao.NoteTagDao
import org.qosp.notes.data.dao.TagDao
import org.qosp.notes.data.model.NoteTagJoin
import org.qosp.notes.data.model.Tag
import org.qosp.notes.data.sync.fs.vault.history.FolderHistoryService
import org.qosp.notes.data.sync.fs.vault.history.HistoryOps
import org.qosp.notes.di.SyncScope

class TagRepository(
    private val tagDao: TagDao,
    private val noteTagDao: NoteTagDao,
    private val noteRepository: NoteRepository,
    private val syncScope: SyncScope,
    private val folderHistoryService: FolderHistoryService? = null,
) {

    fun getAll(): Flow<List<Tag>> {
        return tagDao.getAll()
    }

    fun getById(tagId: Long): Flow<Tag?> {
        return tagDao.getById(tagId)
    }

    fun getByNoteId(noteId: Long): Flow<List<Tag>> {
        return noteTagDao.getByNoteId(noteId)
    }

    fun getByName(name: String): Flow<Tag?> {
        return tagDao.getByName(name)
    }

    suspend fun insert(tag: Tag): Long {
        val id = tagDao.insert(tag)
        folderHistoryService?.record(HistoryOps.TAG_CREATE, name = tag.name)
        return id
    }

    suspend fun delete(vararg tags: Tag, shouldSync: Boolean = true) {
        val affectedNotes = tags
            .map { noteTagDao.getNotesByTagId(it.id).first() }
            .flatten()

        tagDao.delete(*tags)
        tags.forEach { folderHistoryService?.record(HistoryOps.TAG_DELETE, name = it.name) }

        if (shouldSync) {
            syncScope.launch {
                affectedNotes.forEach { noteRepository.updateNotes(it) }
            }
        }
    }

    suspend fun update(vararg tags: Tag) {
        val renamed = tags.mapNotNull { updated ->
            val before = tagDao.getById(updated.id).first()
            if (before != null && before.name != updated.name) before.name to updated else null
        }
        tagDao.update(*tags)
        renamed.forEach { (oldName, updated) ->
            folderHistoryService?.record(HistoryOps.TAG_RENAME, name = oldName, to = updated.name)
        }
    }

    suspend fun addTagToNote(tagId: Long, noteId: Long, shouldSync: Boolean = true) {
        noteTagDao.insert(NoteTagJoin(tagId, noteId))

        if (shouldSync) {
            syncScope.launch {
                val note = noteRepository.getById(noteId).first() ?: return@launch
                noteRepository.updateNotes(note)
            }
        }
    }

    suspend fun deleteTagFromNote(tagId: Long, noteId: Long, shouldSync: Boolean = true) {
        noteTagDao.delete(NoteTagJoin(tagId, noteId))

        if (shouldSync) {
            syncScope.launch {
                val note = noteRepository.getById(noteId).first() ?: return@launch
                noteRepository.updateNotes(note)
            }
        }
    }

    suspend fun copyTags(fromNoteId: Long, toNoteId: Long) {
        noteTagDao.copyTags(fromNoteId, toNoteId)
    }
}
