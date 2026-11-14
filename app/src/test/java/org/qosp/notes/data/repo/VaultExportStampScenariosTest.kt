package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.fs.vault.InMemoryVaultFs
import org.qosp.notes.data.sync.fs.vault.VaultFs
import org.qosp.notes.data.sync.fs.vault.VaultNoteDocument
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner

/**
 * AUD-11: a note that was just exported is not locally edited. Once a mapping has a baseline, hashes decide,
 * so a rename or edit made elsewhere in that window is a pull, never a conflict.
 */
@RunWith(RobolectricTestRunner::class)
class VaultExportStampScenariosTest {

    private fun String.withModified(epoch: Long) =
        replace(Regex("(?m)^modified: \\d+$"), "modified: $epoch")

    private suspend fun NoteRepositoryVaultHarness.pathOf(id: Long) =
        idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!

    private suspend fun NoteRepositoryVaultHarness.onlyNote(): Note =
        noteDao.getAll(SortMethod.TITLE_ASC).first().single()

    /** Counts the note files a sync writes, so a clean sync can prove it touches nothing. */
    private class CountingVaultFs(private val delegate: InMemoryVaultFs) : VaultFs by delegate {
        var noteWrites: Int = 0
            private set

        override fun writeText(relativePath: String, content: String, mimeType: String) {
            if (relativePath.endsWith(".md")) noteWrites++
            delegate.writeText(relativePath, content, mimeType)
        }
    }

    /** Counts Room updates of one note through a trigger, the same way a flow observer would see them. */
    private fun NoteRepositoryVaultHarness.watchNoteUpdates() {
        val db = db.openHelper.writableDatabase
        db.execSQL("CREATE TABLE IF NOT EXISTS test_note_updates (note_id INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS test_note_update AFTER UPDATE ON notes " +
                "BEGIN INSERT INTO test_note_updates (note_id) VALUES (NEW.id); END",
        )
    }

    private fun NoteRepositoryVaultHarness.noteUpdates(id: Long): Int =
        db.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM test_note_updates WHERE note_id = $id")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }

    @Test
    fun justExportedNote_keepsTheFilesModified() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Plan", content = "Body", modifiedDate = 1_000))

        val fileModified = VaultNoteDocument.parse(h.vaultFs.readText(h.pathOf(id)).orEmpty()).frontMatter.modified

        assertEquals(1_000L, fileModified)
        assertEquals("the export stamped the file mtime into Room", 1_000L, h.getNote(id)!!.modifiedDate)
    }

    @Test
    fun titleRenameElsewhere_rightAfterExport_isPulledWithoutAConflictCopy() = runTest {
        val vault = MultiDeviceVault(this, 2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.insertAndSync(Note(title = "Plan", content = "Body", modifiedDate = 1_000))
            vault.syncthing(0, 1)
            b.syncNotes()
            val bId = b.onlyNote().id

            // Another device renames the file before A's next sync.
            val renamed = b.vaultFs.readText("Plan.md").orEmpty().withModified(2_000)
            b.vaultFs.delete("Plan.md")
            b.vaultFs.writeText("Ideas.md", renamed)
            b.syncNotes()

            vault.syncthing(1, 0)
            a.syncNotes()
            vault.syncthing(0, 1)
            b.syncNotes()

            assertEquals(1, b.localNoteCount())
            assertEquals("Ideas", b.getNote(bId)?.title)
            assertEquals(1, a.localNoteCount())
            assertTrue("B has a conflict copy: ${b.conflictFiles()}", b.conflictFiles().isEmpty())
            assertTrue("A has a conflict copy: ${a.conflictFiles()}", a.conflictFiles().isEmpty())
        } finally {
            vault.close()
        }
    }

    @Test
    fun bodyEditElsewhere_rightAfterExport_isPulledWithoutAConflictCopy() = runTest {
        val vault = MultiDeviceVault(this, 2)
        val a = vault[0]
        val b = vault[1]
        try {
            a.insertAndSync(Note(title = "Plan", content = "Body", modifiedDate = 1_000))
            vault.syncthing(0, 1)
            b.syncNotes()
            val bId = b.onlyNote().id

            val edited = b.vaultFs.readText("Plan.md").orEmpty()
                .replace("Body", "Body edited elsewhere")
                .withModified(2_000)
            b.vaultFs.writeText("Plan.md", edited)
            b.syncNotes()

            vault.syncthing(1, 0)
            a.syncNotes()
            vault.syncthing(0, 1)
            b.syncNotes()

            assertEquals(1, b.localNoteCount())
            assertTrue(b.getNote(bId)!!.content.contains("Body edited elsewhere"))
            assertTrue("B has a conflict copy: ${b.conflictFiles()}", b.conflictFiles().isEmpty())
            assertTrue("A has a conflict copy: ${a.conflictFiles()}", a.conflictFiles().isEmpty())
        } finally {
            vault.close()
        }
    }

    @Test
    fun repeatedSyncsWithoutChanges_writeAndUpdateNothing() = runTest {
        val fs = InMemoryVaultFs(caseInsensitive = true)
        val counting = CountingVaultFs(fs)
        val h = NoteRepositoryVaultHarness(this, fs, counting)
        // The body already ends as the file will, so the first sync has nothing of its own to normalize.
        val id = h.insertAndSync(Note(title = "Plan", content = "Body\n", modifiedDate = 1_000))
        h.watchNoteUpdates()

        val writesAfterFirstSync = counting.noteWrites
        val updatesAfterFirstSync = h.noteUpdates(id)
        repeat(3) { h.syncNotes() }

        assertEquals("a clean sync rewrote the note file", writesAfterFirstSync, counting.noteWrites)
        assertEquals("a clean sync updated the note row", updatesAfterFirstSync, h.noteUpdates(id))
        assertEquals(1, h.localNoteCount())
        assertTrue(h.conflictFiles().isEmpty())
    }

    @Test
    fun genuineLocalEdit_isStillPushed() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Plan", content = "Body", modifiedDate = 1_000))
        h.repository.updateNotes(
            h.getNote(id)!!.copy(content = "Local edit", modifiedDate = 2_000),
            sync = false,
        )

        h.syncNotes()

        assertTrue(h.vaultFs.readText(h.pathOf(id)).orEmpty().contains("Local edit"))
        assertEquals(1, h.localNoteCount())
        assertTrue(h.conflictFiles().isEmpty())
    }

    @Test
    fun genuineRemoteEdit_isStillPulled() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Plan", content = "Body", modifiedDate = 1_000))
        val path = h.pathOf(id)
        val edited = h.vaultFs.readText(path).orEmpty()
            .replace("Body", "Remote edit")
            .withModified(2_000)
        h.vaultFs.writeText(path, edited)

        h.syncNotes()

        assertEquals("Remote edit", h.getNote(id)!!.content.trim())
        assertEquals(1, h.localNoteCount())
        assertTrue(h.conflictFiles().isEmpty())
    }

    @Test
    fun legacyMappingWithoutBaseline_stillUsesTheMtimeFallback() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertLocal(Note(title = "Legacy", content = "old", modifiedDate = 1_000))
        h.vaultFs.writeText("Legacy.md", "---\ntitle: Legacy\nmodified: 2000\n---\nnew from disk\n")
        h.upsertMapping(
            IdMapping(
                localNoteId = id,
                remoteNoteId = null,
                provider = CloudService.FILE_STORAGE,
                extras = null,
                isDeletedLocally = false,
                storageUri = "Legacy.md",
            ),
        )

        h.syncNotes()

        assertEquals("new from disk", h.getNote(id)!!.content.trim())
        assertEquals(1, h.localNoteCount())
        assertTrue(h.conflictFiles().isEmpty())
    }
}
