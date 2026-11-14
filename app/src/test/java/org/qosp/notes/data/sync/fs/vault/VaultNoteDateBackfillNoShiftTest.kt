package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner

/**
 * FMT-09: completing a file from another tool must not date a note by the file's current mtime. A note that
 * was imported earlier already has its own dates, and those are what the file must get.
 */
@RunWith(RobolectricTestRunner::class)
class VaultNoteDateBackfillNoShiftTest {

    /** A vault whose reported modification time the test can move, as an external edit would. */
    private class MutableMtimeVaultFs(private val delegate: InMemoryVaultFs) : VaultFs by delegate {
        var mtimeMs: Long = T1_MS

        override fun lastModifiedEpochSec(relativePath: String): Long = mtimeMs / 1000

        override fun listChildren(relativeDir: String): List<VaultFs.VaultFsEntry> =
            delegate.listChildren(relativeDir).map { it.copy(lastModifiedMs = mtimeMs) }
    }

    private companion object {
        const val T1_MS = 1_000_000L
        const val T1_SEC = T1_MS / 1000
        const val T2_MS = 9_000_000L
        const val T2_SEC = T2_MS / 1000
    }

    private suspend fun NoteRepositoryVaultHarness.singleNote() =
        noteDao.getAll(SortMethod.TITLE_ASC).first().single()

    /** The front-matter id line survives an external editor that rewrites the body. */
    private fun idLine(raw: String): String = raw.lines().single { it.startsWith("allnotes-id:") }

    @Test
    fun externalEditAfterImport_doesNotMoveCreationDate_onTheNextSync() = runTest {
        val fs = InMemoryVaultFs(scopeId = "date-no-shift", caseInsensitive = true)
        val mtime = MutableMtimeVaultFs(fs)
        fs.writeText("Plan.md", "Plan body\n")
        val h = NoteRepositoryVaultHarness(this, fs, mtime)

        h.syncNotes()
        val id = h.singleNote().id
        assertEquals(T1_SEC, h.singleNote().creationDate)
        val idLine = idLine(fs.readText("Plan.md").orEmpty())

        // An external editor rewrites the file and drops the dates, while the note keeps its import time.
        fs.writeText("Plan.md", "---\n$idLine\n---\nExternal body\n")
        mtime.mtimeMs = T2_MS
        h.syncNotes()

        assertEquals("the external body did not arrive", "External body", h.singleNote().content.trim())
        assertEquals("the pull moved the note's creation time", T1_SEC, h.singleNote().creationDate)

        // The sync after the backfill must not pull the file's mtime back into the note.
        h.syncNotes()

        assertEquals("the backfill moved the note's creation time", T1_SEC, h.singleNote().creationDate)
        assertEquals(T1_SEC, VaultNoteDocument.parse(fs.readText("Plan.md").orEmpty()).frontMatter.created)
        assertTrue(h.conflictFiles().isEmpty())
        assertEquals(id, h.singleNote().id)
    }

    @Test
    fun externalEditAfterImport_withALocalEdit_isPushed_notConflicted() = runTest {
        val fs = InMemoryVaultFs(scopeId = "date-local-edit", caseInsensitive = true)
        val mtime = MutableMtimeVaultFs(fs)
        fs.writeText("Plan.md", "Plan body\n")
        val h = NoteRepositoryVaultHarness(this, fs, mtime)

        h.syncNotes()
        val id = h.singleNote().id
        val idLine = idLine(fs.readText("Plan.md").orEmpty())

        fs.writeText("Plan.md", "---\n$idLine\n---\nExternal body\n")
        mtime.mtimeMs = T2_MS
        h.syncNotes()

        // The user edits locally before the next sync, so the file's mtime must not create a conflict.
        val note = h.singleNote()
        h.repository.updateNotes(
            note.copy(content = "Local edit", modifiedDate = T2_SEC + 100),
            sync = false,
        )
        h.syncNotes()

        assertEquals("the local edit was not pushed", "Local edit", h.singleNote().content.trim())
        assertEquals("the backfill moved the note's creation time", T1_SEC, h.singleNote().creationDate)
        assertTrue("a false conflict copy appeared: ${h.conflictFiles()}", h.conflictFiles().isEmpty())
        assertEquals(id, h.singleNote().id)
        assertEquals(1, h.localNoteCount())
    }
}
