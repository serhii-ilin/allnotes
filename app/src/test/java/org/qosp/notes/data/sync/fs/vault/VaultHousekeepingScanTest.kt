package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Legitimate empty vault trees only contain housekeeping dot dirs. */
class VaultHousekeepingScanTest {

    @Test
    fun onlyDotDirs_areSkippedAndDoNotFailEmptyIndex() {
        listOf(".obsidian", ".stfolder", ".trash", ".git", "Notes").forEach {
            if (it == "Notes") {
                assertFalse(VaultHousekeeping.shouldSkipDirectory(it))
            } else {
                assertTrue(VaultHousekeeping.shouldSkipDirectory(it))
            }
        }
        assertTrue(VaultHousekeeping.shouldSkipDirectory(".allnotes-staging"))
    }

    @Test
    fun dotTitleNote_isIndexed() {
        assertTrue(VaultHousekeeping.shouldIndexFile(".NET notes.md"))
        assertTrue(VaultHousekeeping.shouldSkipDirectory(".hidden-project"))
    }

    @Test
    fun syncConflictFiles_areNotIndexed() {
        assertFalse(VaultHousekeeping.shouldIndexFile("Note.sync-conflict-20240101-120000.md"))
    }

    @Test
    fun androidTrashFiles_areNotIndexed() {
        assertFalse(VaultHousekeeping.shouldIndexFile(".trashed-1700000000-Plan.md"))
        assertFalse(VaultHousekeeping.shouldIndexFile(".trashed-1700000000-Plan.txt"))
        assertEquals("Plan", VaultPaths.noteTitleFromPath(".trashed-1700000000-Plan.md"))
    }
}
