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
 * FMT-09: the date backfill runs after this sync's pulls, so it must use the note as the pull left it. Taking the
 * note from before the pull would write the old `modified` back into the file and flip the note's date on the next
 * sync.
 */
@RunWith(RobolectricTestRunner::class)
class VaultNoteDateBackfillFreshNoteTest {

    private class MutableMtimeVaultFs(private val delegate: InMemoryVaultFs) : VaultFs by delegate {
        var mtimeMs: Long = 1_000_000L

        override fun lastModifiedEpochSec(relativePath: String): Long = mtimeMs / 1000

        override fun listChildren(relativeDir: String): List<VaultFs.VaultFsEntry> =
            delegate.listChildren(relativeDir).map { it.copy(lastModifiedMs = mtimeMs) }
    }

    @Test
    fun aPullInTheSameSync_isWhatTheBackfillWrites_andNothingFlipsBack() = runTest {
        val fs = InMemoryVaultFs(scopeId = "date-fresh-note", caseInsensitive = true)
        val mtime = MutableMtimeVaultFs(fs)
        fs.writeText("Plan.md", "Plan body\n")
        val h = NoteRepositoryVaultHarness(this, fs, mtime)
        h.syncNotes()
        val idLine = fs.readText("Plan.md").orEmpty().lines().single { it.startsWith("allnotes-id:") }

        // An external editor rewrites the body and drops the dates; the file's time moves to 9000 s.
        fs.writeText("Plan.md", "---\n$idLine\n---\nExternal body\n")
        mtime.mtimeMs = 9_000_000L
        h.syncNotes()

        val pulled = h.noteDao.getAll(SortMethod.TITLE_ASC).first().single()
        assertEquals("External body", pulled.content.trim())
        assertEquals("the pull dates the edit by the file", 9_000L, pulled.modifiedDate)
        assertEquals(
            "the backfill wrote the note's date as the pull left it",
            9_000L,
            VaultNoteDocument.parse(fs.readText("Plan.md").orEmpty()).frontMatter.modified,
        )

        h.syncNotes()
        h.syncNotes()

        val after = h.noteDao.getAll(SortMethod.TITLE_ASC).first().single()
        assertEquals("the note's date flipped back", 9_000L, after.modifiedDate)
        assertTrue(h.conflictFiles().isEmpty())
    }
}
