package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.data.sync.core.RemoteOperation
import org.qosp.notes.preferences.CloudService
import org.robolectric.RobolectricTestRunner

/** Recovery journal and quarantine behavior through the real repository sync path. */
@RunWith(RobolectricTestRunner::class)
class VaultQuarantineScenariosTest {

    private fun journalOf(h: NoteRepositoryVaultHarness, generation: Long, vararg entries: VaultRecoveryJournalEntry) =
        VaultRecoveryJournal(h.vaultFs.scopeId, generation, entries.toList())

    private fun entry(path: String, backupToken: String?, backupName: String? = backupToken) = VaultRecoveryJournalEntry(
        notePath = path,
        stagingToken = "st-gone",
        backupToken = backupToken,
        backupStorageName = backupName,
        expectedContentHash = VaultContentHash.ofText("never written"),
        phase = VaultRecoveryJournalEntry.PHASE_PUBLISHING,
    )

    @Test
    fun staleGenerationJournal_withBackup_isRestoredAndLaterSaveSucceeds() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            val id = h.insertAndSync(Note(title = "Note", content = "good body"))
            val path = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!
            val store = FsVaultWriteStore(h.context, h.vaultFs)
            val backup = store.saveBackup(path, h.vaultFs.readText(path)!!)
            // The write was killed after the file was truncated; the folder was re-picked since (new generation).
            h.vaultFs.writeText(path, "truncat")
            store.writeJournal(journalOf(h, 0L, entry(path, backup.token, backup.storageName)))

            h.syncNotes()

            assertEquals("good body", h.getNote(id)!!.content.trim())
            assertTrue("good body" in h.vaultFs.readText(path)!!)
            assertEquals(null, store.readJournal())
            assertEquals(false, h.getNote(id)!!.isDeleted)

            h.updateAndSync(h.getNote(id)!!.copy(content = "second body"))
            assertTrue("second body" in h.vaultFs.readText(path)!!)
        } finally {
            h.close()
        }
    }

    @Test
    fun quarantinedPath_isNotImportedNotBinnedAndKeepsRoomContent() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            val id = h.insertAndSync(Note(title = "Note", content = "good body"))
            val path = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!
            val store = FsVaultWriteStore(h.context, h.vaultFs)
            // Nothing to recover from (no backup, no staging): the path is quarantined, never trusted.
            h.vaultFs.writeText(path, "half written")
            h.vaultFs.writeText("Stray.md", "half written too")
            store.writeJournal(journalOf(h, 1L, entry(path, null), entry("Stray.md", null)))


            val note = h.getNote(id)!!
            assertEquals("good body", note.content.trim())
            assertFalse(note.isDeleted)
            assertEquals(1, h.localNoteCount())
        } finally {
            h.close()
        }
    }

    @Test
    fun quarantinedPathLeftOutOfScan_doesNotBinItsNote() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            val id = h.insertAndSync(Note(title = "Note", content = "good body"))
            val path = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!
            val store = FsVaultWriteStore(h.context, h.vaultFs)
            store.writeJournal(journalOf(h, 1L, entry(path, null)))

            repeat(4) { h.syncNotes() }

            assertFalse(h.getNote(id)!!.isDeleted)
            assertEquals("good body", h.getNote(id)!!.content.trim())
        } finally {
            h.close()
        }
    }

    @Test
    fun caseInsensitiveVault_noteAndNoteGetDistinctFilesAndMappings() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            val a = h.insertAndSync(Note(title = "Note", content = "first"))
            val b = h.insertAndSync(Note(title = "note", content = "second"))
            repeat(2) { h.syncNotes() }

            val pathA = h.idMappingDao.getByLocalIdAndProvider(a, CloudService.FILE_STORAGE)!!.storageUri!!
            val pathB = h.idMappingDao.getByLocalIdAndProvider(b, CloudService.FILE_STORAGE)!!.storageUri!!
            assertNotEquals(pathA.lowercase(), pathB.lowercase())
            assertEquals("first", h.getNote(a)!!.content.trim())
            assertEquals("second", h.getNote(b)!!.content.trim())
            assertEquals(2, h.vaultFs.snapshotPaths().count { it.endsWith(".md") })
        } finally {
            h.close()
        }
    }

    @Test
    fun caseInsensitiveVault_newNoteAvoidsPathOfAnotherMappingWhoseFileIsGone() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            val a = h.insertAndSync(Note(title = "Note", content = "first"))
            val pathA = h.idMappingDao.getByLocalIdAndProvider(a, CloudService.FILE_STORAGE)!!.storageUri!!
            // Note A keeps its mapping while its file is momentarily absent (kept by the missing-file safety).
            h.vaultFs.delete(pathA)

            val b = h.insertAndSync(Note(title = "note", content = "second"))
            val pathB = h.idMappingDao.getByLocalIdAndProvider(b, CloudService.FILE_STORAGE)!!.storageUri!!

            assertNotEquals(pathA.lowercase(), pathB.lowercase())
        } finally {
            h.close()
        }
    }

    @Test
    fun staleSyncUpdate_doesNotReplaceNewerEditorSave() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        try {
            val id = h.insertAndSync(Note(title = "Note", content = "old"))
            val path = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!
            val old = h.getNote(id)!!
            val edited = old.copy(content = "new", modifiedDate = old.modifiedDate + 10)
            h.noteDao.update(edited.toEntity())

            h.queueRemote(id, RemoteOperation.Update(edited))
            // The sync decided its push under the vault lock, before the editor save, and enqueues it after.
            h.queueRemote(id, RemoteOperation.Update(old))
            h.flushRemoteActions()

            assertTrue(h.vaultFs.readText(path)!!.contains("new"))
            assertFalse(h.vaultFs.readText(path)!!.contains("old"))
        } finally {
            h.close()
        }
    }
}
