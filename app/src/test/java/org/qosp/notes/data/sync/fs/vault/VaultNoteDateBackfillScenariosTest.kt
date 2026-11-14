package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.data.sync.core.ISyncBackend
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner

/** A file from another tool gains the full required attribute set on its first sync, once. */
@RunWith(RobolectricTestRunner::class)
class VaultNoteDateBackfillScenariosTest {

    /** Reports a stable old mtime so every import sees the file's own modification time. */
    private class FixedMtimeVaultFs(
        private val delegate: InMemoryVaultFs,
        private val mtimeMs: Long = MTIME_MS,
    ) : VaultFs by delegate {
        override fun readText(relativePath: String): String? = delegate.readText(relativePath)

        override fun lastModifiedEpochSec(relativePath: String): Long = mtimeMs / 1000

        override fun listChildren(relativeDir: String): List<VaultFs.VaultFsEntry> =
            delegate.listChildren(relativeDir).map { it.copy(lastModifiedMs = mtimeMs) }
    }

    /** Counts the writes a sync performs, so a second sync can prove it touches nothing. */
    private class CountingBackend(private val delegate: ISyncBackend) : ISyncBackend by delegate {
        var replaceCalls: Int = 0
            private set
        var pushCalls: Int = 0
            private set

        override suspend fun replaceNoteText(
            relativePath: String,
            text: String,
            expectedRawHash: String,
        ): Boolean {
            replaceCalls++
            return delegate.replaceNoteText(relativePath, text, expectedRawHash)
        }

        override suspend fun createNote(note: Note, linkExisting: Boolean): SyncNote {
            pushCalls++
            return delegate.createNote(note, linkExisting)
        }

        override suspend fun updateNote(note: Note, mapping: IdMapping): IdMapping {
            pushCalls++
            return delegate.updateNote(note, mapping)
        }
    }

    private companion object {
        const val MTIME_MS = 1_000_000L
        const val MTIME_SEC = MTIME_MS / 1000
    }

    private suspend fun NoteRepositoryVaultHarness.pathOf(id: Long): String =
        idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!

    @Test
    fun aFileFromAnotherToolReceivesTheFullRequiredAttributeSet() = runTest {
        val fs = InMemoryVaultFs(scopeId = "full-set", caseInsensitive = true)
        val body = "# Groceries\n\n- milk\n- eggs\n"
        fs.writeText("Groceries.md", body)
        val h = NoteRepositoryVaultHarness(this, fs, FixedMtimeVaultFs(fs))

        h.syncNotes()

        val raw = fs.readText("Groceries.md").orEmpty()
        val doc = VaultNoteDocument.parse(raw)
        assertNotNull("the file did not gain an id", doc.frontMatter.id)
        assertEquals(MTIME_SEC, doc.frontMatter.created)
        assertEquals(MTIME_SEC, doc.frontMatter.modified)
        assertEquals("the body changed", body, doc.body)
        assertEquals("one front-matter block", 2, raw.lines().count { it == "---" })

        val note = h.noteDao.getAll(SortMethod.TITLE_ASC).first().single()
        assertEquals("the note looks created later", MTIME_SEC, note.creationDate)
        assertEquals("the note looks edited by the write", MTIME_SEC, note.modifiedDate)

        val counter = CountingBackend(h.backend)
        h.wrapBackend { counter }
        val before = VaultContentHash.ofText(raw)
        h.syncNotes()

        assertEquals("the second sync rewrote the file", raw, fs.readText("Groceries.md"))
        assertEquals("the content changed", before, VaultContentHash.ofText(fs.readText("Groceries.md").orEmpty()))
        assertEquals("the second sync wrote the file", 0, counter.replaceCalls)
        assertEquals("the second sync pushed the note", 0, counter.pushCalls)
        assertEquals(1, h.localNoteCount())
        assertTrue(h.conflictFiles().isEmpty())
    }

    @Test
    fun anObsidianFileGainsOnlyTheIdAndModified() = runTest {
        val fs = InMemoryVaultFs(scopeId = "obsidian", caseInsensitive = true)
        val raw = "---\naliases: [x]\ncreated: 2024-01-15\n---\nBody\n"
        fs.writeText("Clipped.md", raw)
        val h = NoteRepositoryVaultHarness(this, fs, FixedMtimeVaultFs(fs))

        h.syncNotes()

        val after = fs.readText("Clipped.md").orEmpty()
        val lines = after.lines()
        assertNotNull(VaultNoteDocument.parse(after).frontMatter.id)
        assertEquals(MTIME_SEC, VaultNoteDocument.parse(after).frontMatter.modified)
        assertTrue("aliases changed: $after", lines.contains("aliases: [x]"))
        assertTrue("created changed: $after", lines.contains("created: 2024-01-15"))
        assertFalse("created was rewritten: $after", lines.contains("created: $MTIME_SEC"))

        val note = h.noteDao.getAll(SortMethod.TITLE_ASC).first().single()
        assertEquals(1_705_276_800L, note.creationDate)
        assertEquals(MTIME_SEC, note.modifiedDate)
        assertEquals(1, h.localNoteCount())
    }

    @Test
    fun anUnparseableCreatedIsKeptUnchanged() = runTest {
        val fs = InMemoryVaultFs(scopeId = "unparseable", caseInsensitive = true)
        fs.writeText("Dated.md", "---\ncreated: someday\n---\nBody\n")
        val h = NoteRepositoryVaultHarness(this, fs, FixedMtimeVaultFs(fs))

        h.syncNotes()

        val after = fs.readText("Dated.md").orEmpty()
        val lines = after.lines()
        assertTrue("the unparseable created line was changed: $after", lines.contains("created: someday"))
        assertFalse("a second created line was added: $after", lines.contains("created: $MTIME_SEC"))
        assertEquals(MTIME_SEC, VaultNoteDocument.parse(after).frontMatter.modified)
        assertNotNull(VaultNoteDocument.parse(after).frontMatter.id)
        assertEquals(1, h.localNoteCount())
    }

    @Test
    fun twoPhonesWithTheSameFileAndMtimeWriteIdenticalBytes() = runTest {
        val files = listOf(
            "Groceries.md" to "# Groceries\n\n- milk\n",
            "Clipped.md" to "---\naliases: [x]\ncreated: 2024-01-15\n---\nBody\n",
        )

        suspend fun phone(scope: String): InMemoryVaultFs {
            val fs = InMemoryVaultFs(scopeId = scope, caseInsensitive = true)
            files.forEach { (path, body) -> fs.writeText(path, body) }
            NoteRepositoryVaultHarness(this, fs, FixedMtimeVaultFs(fs)).syncNotes()
            return fs
        }

        val a = phone("phone-a")
        val b = phone("phone-b")

        for ((path, _) in files) {
            val rawA = a.readText(path).orEmpty()
            val rawB = b.readText(path).orEmpty()
            assertEquals("$path differs between phones", rawA, rawB)
            assertNotNull("$path did not gain an id", VaultNoteDocument.parse(rawA).frontMatter.id)
            assertEquals(MTIME_SEC, VaultNoteDocument.parse(rawA).frontMatter.modified)
        }
    }

    @Test
    fun anInAppNoteIsNeverRewrittenByTheBackfill() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Plan", content = "Body"))
        val path = h.pathOf(id)
        val before = h.vaultFs.readText(path).orEmpty()
        val created = VaultNoteDocument.parse(before).frontMatter.created
        val modified = VaultNoteDocument.parse(before).frontMatter.modified
        assertNotNull(created)
        assertNotNull(modified)

        val counter = CountingBackend(h.backend)
        h.wrapBackend { counter }
        h.syncNotes()

        assertEquals(before, h.vaultFs.readText(path).orEmpty())
        assertEquals("the backfill rewrote an in-app note", 0, counter.replaceCalls)
        assertEquals("the note was pushed again", 0, counter.pushCalls)
        assertEquals(created, VaultNoteDocument.parse(h.vaultFs.readText(path).orEmpty()).frontMatter.created)
        assertEquals(modified, VaultNoteDocument.parse(h.vaultFs.readText(path).orEmpty()).frontMatter.modified)
        assertEquals(1, h.localNoteCount())
    }
}
