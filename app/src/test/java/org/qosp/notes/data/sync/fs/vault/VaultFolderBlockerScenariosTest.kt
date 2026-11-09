package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.NoteAction
import org.qosp.notes.data.sync.core.RemoteOperation
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod
import kotlinx.coroutines.flow.first
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VaultFolderBlockerScenariosTest {

    @Test
    fun blocker2_addAttachment_type_obsidianEdit_pullsWithoutConflict() = runTest {
        val h = VaultFolderSyncHarness(this)
        val tree = h.tree()
        val path = "Note.md"
        tree.seedNoteFile(path, "Note", "Body")
        val noteId = h.insertNote(Note(title = "Note", content = "Body", modifiedDate = 1L))
        h.upsertMapping(tree.mappingWithAttachments(noteId, path, h.getNote(noteId)!!, emptyList()))
        h.syncRound()
        val attPath = VaultPaths.vaultRelative("${VaultPaths.assetDirectoryForNote(path)}/pic.png")
        tree.vaultFs.writeBytes(VaultPaths.toVaultRelative(attPath), byteArrayOf(1, 2), "image/png")
        val withAtt = h.getNote(noteId)!!.copy(
            content = "Body\n\ntyped",
            attachments = listOf(Attachment(Attachment.Type.IMAGE, attPath, "pic.png")),
            modifiedDate = 2L,
        )
        h.noteDao.update(withAtt.toEntity())
        h.vaultPushUpdate(withAtt)
        tree.externalEdit(path, "Body\n\ntyped\n\nFrom Obsidian")
        h.refreshVaultIndex()
        val result = h.syncRound()
        assertTrue(result.localUpdates.any { it is NoteAction.Update })
        assertEquals(0, result.vaultConflicts.size)
        assertEquals(0, h.conflictFiles().size)
        val m = h.idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE)!!
        assertFalse(VaultSyncImport.isLocallyDirty(h.getNote(noteId)!!, path, VaultMappingState.resolve(m.extras), null))
    }

    @Test
    fun blocker2_quoteInTitle_fiveSaves_zeroConflictFiles() = runTest {
        val h = VaultFolderSyncHarness(this)
        val title = "Say \"Hi\""
        val note = Note(id = 0, title = title, content = "X")
        val id = h.insertNote(note)
        val live = h.getNote(id)!!
        h.vaultPushCreate(live)
        val path = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!
        var cur = h.getNote(id)!!
        repeat(5) {
            cur = cur.copy(content = "X$it", modifiedDate = cur.modifiedDate + 1)
            h.noteDao.update(cur.toEntity())
            h.vaultPushUpdate(cur)
        }
        assertEquals(0, h.conflictFiles().size)
        assertTrue(h.vaultFs.isFile(path))
    }

    @Test
    fun blocker2_notebookQuote_fiveSaves_zeroConflictFiles() = runTest {
        val h = VaultFolderSyncHarness(this)
        val tree = h.tree()
        val path = "Nb.md"
        val note = Note(title = "Nb", content = "Z")
        val seeded = VaultNoteDocument.fromNote(
            note,
            path,
            assignedNotebookName = "Team \"A\"",
            exportNotebookExplicitly = true,
        ).renderFullText()
        tree.vaultFs.writeText(path, seeded)
        val id = h.insertNote(note.copy(id = 0))
        h.upsertMapping(tree.mappingWithAttachments(id, path, h.getNote(id)!!, emptyList()))
        var cur = h.getNote(id)!!
        repeat(5) {
            cur = cur.copy(content = "Z$it", modifiedDate = cur.modifiedDate + 1)
            h.noteDao.update(cur.toEntity())
            h.vaultPushUpdate(cur)
        }
        assertEquals(0, h.conflictFiles().size)
    }

    @Test
    fun blocker2_leadingBlankLine_fiveSaves_zeroConflictFiles() = runTest {
        val h = VaultFolderSyncHarness(this)
        val note = Note(id = 0, title = "Blank", content = "\n\nHello")
        val id = h.insertNote(note)
        h.vaultPushCreate(h.getNote(id)!!)
        var cur = h.getNote(id)!!
        repeat(5) {
            cur = cur.copy(content = "\n\nHello$it", modifiedDate = cur.modifiedDate + 1)
            h.noteDao.update(cur.toEntity())
            h.vaultPushUpdate(cur)
        }
        assertEquals(0, h.conflictFiles().size)
    }

    @Test
    fun blocker2_externalEditThenAppEdit_oneConflictCopy() = runTest {
        val h = VaultFolderSyncHarness(this)
        val tree = h.tree()
        val id = h.insertNote(Note(title = "C", content = "Base", modifiedDate = 1L))
        h.vaultPushCreate(h.getNote(id)!!)
        val path = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!
        tree.externalEdit(path, "From PC")
        h.refreshVaultIndex()
        val edited = h.getNote(id)!!.copy(content = "App edit", modifiedDate = 99L)
        h.noteDao.update(edited.toEntity())
        h.vaultPushUpdate(edited)
        assertEquals(1, h.conflictFiles().size)
    }

    @Test
    fun blocker3_existingFolder_noDuplicates_noSuffixFiles() = runTest {
        val h = VaultFolderSyncHarness(this)
        val tree = h.tree()
        tree.seedNoteFile("Work/A.md", "A", "one", emptyList())
        val rawB = "---\nmodified: 1\ncreated: 1\n---\ntwo"
        tree.vaultFs.writeText("Work/B.md", rawB)
        val idA = h.insertNote(Note(title = "A", content = "one", modifiedDate = 100L))
        val idB = h.insertNote(Note(title = "B", content = "two", modifiedDate = 999L))
        h.upsertMapping(
            tree.mappingWithAttachments(
                idA,
                "Work/A.md",
                h.getNote(idA)!!,
                emptyList(),
            ),
        )
        h.refreshVaultIndex()
        repeat(3) { h.syncRound() }
        assertEquals(0, h.duplicateSuffixFiles().size)
        assertEquals(2, h.noteDao.getAll(SortMethod.TITLE_ASC).first().size)
        assertTrue(h.idMappingDao.getByLocalIdAndProvider(idB, CloudService.FILE_STORAGE) != null)
        assertEquals(1, h.mappingCountFor(idA))
        assertEquals(1, h.mappingCountFor(idB))
    }

    @Test
    fun blocker4_7_legacyContentUriRow_threeSyncs_oneMappingNoJunk() = runTest {
        val h = VaultFolderSyncHarness(this)
        val tree = h.tree()
        tree.seedNoteFile("Work/Foo.md", "Foo", "Body")
        val id = h.insertNote(Note(title = "Foo", content = "Body"))
        h.idMappingDao.insert(
            IdMapping(
                localNoteId = id,
                remoteNoteId = null,
                provider = CloudService.FILE_STORAGE,
                extras = null,
                isDeletedLocally = false,
                storageUri = "content://com.example/tree/doc/123",
            ),
        )
        h.refreshVaultIndex()
        repeat(3) { h.syncRound() }
        val mapping = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!
        assertEquals(1, h.mappingCountFor(id))
        assertEquals("Work/Foo.md", VaultPaths.vaultMappingPath(mapping.storageUri))
        assertTrue(h.contentUriFolders().isEmpty())
    }

    @Test
    fun blocker4_binRestoreWorkFoo_editPermanentDelete_oneFileLifecycle() = runTest {
        val h = VaultFolderSyncHarness(this)
        val tree = h.tree()
        val path = "Work/Foo.md"
        tree.seedNoteFile(path, "Foo", "Body")
        val id = h.insertNote(Note(title = "Foo", content = "Body"))
        h.vaultPushCreate(h.getNote(id)!!)
        h.vaultPushDelete(h.getNote(id)!!, removeAssets = false)
        noteUndelete(h, id)
        h.vaultPushUpdate(h.getNote(id)!!)
        assertEquals(path, h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri)
        val edited = h.getNote(id)!!.copy(content = "Edited")
        h.noteDao.update(edited.toEntity())
        h.vaultPushUpdate(edited)
        h.vaultPushDelete(edited, removeAssets = true)
        assertFalse(h.vaultFs.isFile(path))
        assertEquals(0, h.mappingCountFor(id))
    }

    @Test
    fun blocker4_binRestoreRenameSync_oneMapping_staysActive() = runTest {
        val h = VaultFolderSyncHarness(this)
        val tree = h.tree()
        tree.seedNoteFile("R.md", "R", "B")
        val id = h.insertNote(Note(title = "R", content = "B"))
        h.vaultPushCreate(h.getNote(id)!!)
        val renamed = h.getNote(id)!!.copy(title = "R2")
        h.noteDao.update(renamed.toEntity())
        h.vaultPushUpdate(renamed)
        h.syncRound()
        assertEquals(1, h.mappingCountFor(id))
        assertFalse(h.getNote(id)!!.isDeleted)
    }

    @Test
    fun processRemoteActions_createAfterBin_doesNotRestoreNote() = runTest {
        val h = VaultFolderSyncHarness(this)
        val id = h.insertNote(Note(title = "Stale", content = "X"))
        h.noteDao.update(h.getNote(id)!!.toEntity().copy(isDeleted = true, deletionDate = 1L))
        h.push(id, RemoteOperation.Create(h.getNote(id)!!))
        assertTrue(h.getNote(id)!!.isDeleted)
    }

    private suspend fun noteUndelete(h: VaultFolderSyncHarness, id: Long) {
        val n = h.getNote(id)!!
        h.noteDao.update(n.toEntity().copy(isDeleted = false, deletionDate = null))
        val m = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!
        val state = VaultMappingState.resolve(m.extras) ?: VaultMappingState("")
        h.idMappingDao.update(
            m.copy(
                storageUri = state.binnedVaultPath ?: m.storageUri,
                isDeletedLocally = false,
                extras = VaultMappingState.encode(state.copy(binnedVaultPath = null)),
            ),
        )
    }
}
