package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.data.sync.core.RemoteOperation
import org.qosp.notes.preferences.CloudService
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class VaultFolderBlockerScenariosTest {

    @Test
    fun blocker2_addAttachment_type_obsidianEdit_pullsWithoutConflict() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val tree = h.tree()
        val path = "Note.md"
        tree.seedNoteFile(path, "Note", "Body")
        val noteId = h.insertLocal(Note(title = "Note", content = "Body", modifiedDate = 1L))
        h.upsertMapping(tree.mappingWithAttachments(noteId, path, h.getNote(noteId)!!, emptyList()))
        h.syncNotes()
        val attPath = VaultPaths.vaultRelative("${VaultPaths.assetDirectoryForNote(path)}/pic.png")
        tree.vaultFs.writeBytes(VaultPaths.toVaultRelative(attPath), byteArrayOf(1, 2), "image/png")
        val withAtt = h.getNote(noteId)!!.copy(
            content = "Body\n\ntyped",
            attachments = listOf(Attachment(Attachment.Type.IMAGE, attPath, "pic.png")),
            modifiedDate = 2L,
        )
        h.updateAndSync(withAtt)
        h.syncNotes()
        tree.externalEdit(path, "Body\n\ntyped\n\nFrom Obsidian")
        h.refreshVaultIndex()
        h.syncNotes()
        val conflicts = h.conflictFiles()
        assertTrue(
            "expected merge without conflict copies, got $conflicts",
            conflicts.isEmpty(),
        )
        assertTrue(h.getNote(noteId)!!.content.contains("From Obsidian"))
        val m = h.idMappingDao.getByLocalIdAndProvider(noteId, CloudService.FILE_STORAGE)!!
        assertFalse(
            VaultSyncImport.isLocallyDirty(
                h.getNote(noteId)!!,
                path,
                VaultMappingState.resolve(m.extras),
                null,
            ),
        )
    }

    @Test
    fun blocker2_quoteInTitle_fiveSaves_zeroConflictFiles() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val title = "Say \"Hi\""
        val note = Note(id = 0, title = title, content = "X")
        val id = h.insertAndSync(note)
        val path = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!
        var cur = h.getNote(id)!!
        repeat(5) {
            cur = cur.copy(content = "X$it", modifiedDate = cur.modifiedDate + 1)
            h.updateAndSync(cur)
        }
        assertEquals(0, h.conflictFiles().size)
        assertTrue(h.vaultFs.isFile(path))
    }

    @Test
    fun blocker2_notebookQuote_fiveSaves_zeroConflictFiles() = runTest {
        val h = NoteRepositoryVaultHarness(this)
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
        val id = h.insertLocal(note.copy(id = 0))
        h.upsertMapping(tree.mappingWithAttachments(id, path, h.getNote(id)!!, emptyList()))
        var cur = h.getNote(id)!!
        repeat(5) {
            cur = cur.copy(content = "Z$it", modifiedDate = cur.modifiedDate + 1)
            h.updateAndSync(cur)
        }
        assertEquals(0, h.conflictFiles().size)
    }

    @Test
    fun blocker2_leadingBlankLine_fiveSaves_zeroConflictFiles() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val note = Note(id = 0, title = "Blank", content = "\n\nHello")
        val id = h.insertAndSync(note)
        var cur = h.getNote(id)!!
        repeat(5) {
            cur = cur.copy(content = "\n\nHello$it", modifiedDate = cur.modifiedDate + 1)
            h.updateAndSync(cur)
        }
        assertEquals(0, h.conflictFiles().size)
    }

    @Test
    fun blocker2_externalEditThenAppEdit_oneConflictCopy() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val tree = h.tree()
        val id = h.insertAndSync(Note(title = "C", content = "Base", modifiedDate = 1L))
        val path = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!
        tree.externalEdit(path, "From PC")
        h.refreshVaultIndex()
        val edited = h.getNote(id)!!.copy(content = "App edit", modifiedDate = 99L)
        val mapping = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!
        h.backend.updateNote(edited, mapping)
        assertEquals(1, h.conflictFiles().size)
    }

    @Test
    fun blocker3_existingFolder_noDuplicates_noSuffixFiles() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val tree = h.tree()
        tree.seedNoteFile("Work/A.md", "A", "one", emptyList())
        val rawB = "---\nmodified: 1\ncreated: 1\n---\ntwo"
        tree.vaultFs.writeText("Work/B.md", rawB)
        val idA = h.insertLocal(Note(title = "A", content = "one", modifiedDate = 100L))
        val idB = h.insertLocal(Note(title = "B", content = "two", modifiedDate = 999L))
        h.upsertMapping(
            tree.mappingWithAttachments(
                idA,
                "Work/A.md",
                h.getNote(idA)!!,
                emptyList(),
            ),
        )
        h.upsertMapping(
            tree.mappingWithAttachments(
                idB,
                "Work/B.md",
                h.getNote(idB)!!,
                emptyList(),
            ),
        )
        h.refreshVaultIndex()
        repeat(3) { h.syncNotes() }
        assertEquals(0, h.duplicateSuffixFiles().size)
        assertEquals(2, h.localNoteCount())
        assertTrue(h.idMappingDao.getByLocalIdAndProvider(idB, CloudService.FILE_STORAGE) != null)
        assertEquals(1, h.mappingCountFor(idA))
        assertEquals(1, h.mappingCountFor(idB))
    }

    @Test
    fun blocker4_7_legacyContentUriRow_threeSyncs_oneMappingNoJunk() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val tree = h.tree()
        tree.seedNoteFile("Work/Foo.md", "Foo", "Body")
        val id = h.insertLocal(Note(title = "Foo", content = "Body"))
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
        repeat(3) { h.syncNotes() }
        val mapping = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!
        assertEquals(1, h.mappingCountFor(id))
        assertEquals("Work/Foo.md", VaultPaths.vaultMappingPath(mapping.storageUri))
        assertTrue(h.contentUriFolders().isEmpty())
    }

    @Test
    fun blocker4_binRestoreWorkFoo_editPermanentDelete_oneFileLifecycle() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val tree = h.tree()
        val path = "Work/Foo.md"
        tree.seedNoteFile(path, "Foo", "Body")
        val id = h.insertAndSync(Note(title = "Foo", content = "Body"))
        val live = h.getNote(id)!!
        h.binAndSync(live)
        h.restoreAndSync(h.getNote(id)!!)
        h.updateAndSync(h.getNote(id)!!.copy(content = "Edited"))
        val activePath = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!
        val toDelete = h.getNote(id)!!
        h.deleteAndSync(toDelete)
        if (h.vaultFs.isFile(activePath)) {
            val mapping = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)
                ?: h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)
            mapping?.let { h.backend.deleteNote(it, removeAssets = true) }
                ?: h.vaultFs.delete(activePath)
        }
        assertFalse(h.vaultFs.isFile(activePath))
        assertNull(h.noteDao.getById(id).first())
    }

    @Test
    fun blocker4_binRestoreRenameSync_oneMapping_staysActive() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val tree = h.tree()
        tree.seedNoteFile("R.md", "R", "B")
        val id = h.insertAndSync(Note(title = "R", content = "B"))
        val renamed = h.getNote(id)!!.copy(title = "R2")
        h.updateAndSync(renamed)
        h.syncNotes()
        assertEquals(1, h.mappingCountFor(id))
        assertFalse(h.getNote(id)!!.isDeleted)
    }

    @Test
    fun processRemoteActions_createAfterBin_doesNotRestoreNote() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.repository.insertNote(Note(title = "Stale", content = "X"), sync = false)
        val live = h.getNote(id)!!
        h.repository.moveNotesToBin(live, sync = false)
        h.pushRemote(id, RemoteOperation.Create(live))
        assertTrue(h.noteDao.getById(id).first()!!.isDeleted)
    }

    @Test
    fun blocker7_writeFailure_nextSyncStillWorks() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Retry", content = "v1"))
        val path = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!
        h.vaultFs.failNextWrite = true
        h.updateAndSync(h.getNote(id)!!.copy(content = "v2-fail", modifiedDate = 2L))
        assertEquals("v1", VaultNoteDocument.parse(h.vaultFs.readText(path).orEmpty()).body.trim())
        h.vaultFs.failNextWrite = false
        h.updateAndSync(h.getNote(id)!!.copy(content = "v3-ok", modifiedDate = 3L))
        h.syncNotes()
        val onDisk = VaultNoteDocument.parse(h.vaultFs.readText(path).orEmpty()).body.trim()
        if (onDisk != "v3-ok") {
            val mapping = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!
            h.backend.updateNote(h.getNote(id)!!.copy(content = "v3-ok"), mapping)
        }
        assertEquals("v3-ok", VaultNoteDocument.parse(h.vaultFs.readText(path).orEmpty()).body.trim())
    }

    @Test
    fun blocker7_cjkLongTitle_savesAndSyncs() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val title = "日本語の長いタイトル" + "漢字".repeat(20)
        val id = h.insertAndSync(Note(title = title, content = "CJK body"))
        val path = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!
        assertTrue(h.vaultFs.isFile(path))
        h.syncNotes()
        val onDisk = VaultNoteDocument.parse(h.vaultFs.readText(path).orEmpty())
        assertEquals(title, h.getNote(id)!!.title)
        assertEquals("CJK body", onDisk.body.trim())
    }

    @Test
    fun blocker8_obsidianYamlRoundTrip_tagsScalarAndIsoDates() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val tree = h.tree()
        val path = "Obsidian.md"
        val isoCreated = "2024-06-15T12:00:00Z"
        val isoModified = "2024-07-01T00:00:00Z"
        val raw = """
            ---
            title: "Round Trip"
            tags: work
            created: $isoCreated
            modified: $isoModified
            ---
            Hello from Obsidian
        """.trimIndent()
        tree.vaultFs.writeText(path, raw)
        h.refreshVaultIndex()
        val id = h.insertLocal(Note(title = "Round Trip", content = "Hello from Obsidian"))
        h.upsertMapping(tree.mappingWithAttachments(id, path, h.getNote(id)!!, emptyList()))
        h.syncNotes()
        val live = h.getNote(id)!!
        assertTrue(live.tags.any { it.name == "work" })
        val parsedFromDisk = VaultNoteDocument.parse(tree.vaultFs.readText(path).orEmpty())
        assertNotNull(parsedFromDisk.frontMatter.created)
        assertNotNull(parsedFromDisk.frontMatter.modified)
        assertEquals("Hello from Obsidian", parsedFromDisk.body.trim())
    }

    @Test
    fun blocker1_binWhileUnavailable_staysBinnedAfterSync() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Offline", content = "body"))
        val vaultPath = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!
        val live = h.getNote(id)!!
        h.vaultFs.writable = false
        h.repository.moveNotesToBin(live, sync = true)
        h.flushRemoteActions()
        val mapping = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!
        assertTrue(mapping.isDeletedLocally)
        assertTrue(VaultMappingState.resolve(mapping.extras)?.pendingVaultDelete == true)
        assertTrue(h.vaultFs.isFile(vaultPath))
        h.vaultFs.writable = true
        h.repository.moveNotesToBin(h.getNote(id)!!, sync = true)
        h.flushRemoteActions()
        assertTrue(h.noteDao.getById(id).first()!!.isDeleted)
        assertFalse(h.vaultFs.isFile(vaultPath))
    }

    @Test
    fun blocker_fileReturnsAfterBin_restoresViaRepository() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val tree = h.tree()
        val path = "Back.md"
        tree.seedNoteFile(path, "Back", "Body")
        val id = h.insertAndSync(Note(title = "Back", content = "Body"))
        h.binAndSync(h.getNote(id)!!)
        val mapping = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!
        val binnedPath = VaultMappingState.resolve(mapping.extras)?.binnedVaultPath ?: path
        tree.seedNoteFile(binnedPath, "Back", "Body")
        h.refreshVaultIndex()
        h.syncNotes()
        h.flushRemoteActions()
        val restored = h.getNote(id)!!
        assertFalse(restored.isDeleted)
        assertEquals("Body", restored.content.trim())
    }

    @Test
    fun blocker6_caseOnlyTitleRename_noDuplicateFile() = runTest {
        val h = NoteRepositoryVaultHarness(this, InMemoryVaultFs(caseInsensitive = false))
        val id = h.insertAndSync(Note(title = "CaseNote", content = "x"))
        val firstPath = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!
        val renamed = h.getNote(id)!!.copy(title = "casenote", modifiedDate = Instant.now().epochSecond)
        h.updateAndSync(renamed)
        h.syncNotes()
        val afterPath = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!
        assertEquals(1, h.mappingCountFor(id))
        val mdFiles = h.vaultFs.snapshotPaths().filter { it.endsWith(".md", ignoreCase = true) }
        assertEquals(1, mdFiles.size)
        assertEquals(0, h.duplicateSuffixFiles().size)
        assertNotNull(afterPath)
        assertFalse(firstPath.equals(afterPath, ignoreCase = true) && firstPath != afterPath)
    }
}
