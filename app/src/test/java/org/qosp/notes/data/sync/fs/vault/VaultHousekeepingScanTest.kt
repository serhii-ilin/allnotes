package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Legitimate empty vault trees only contain housekeeping dot dirs. */
class VaultHousekeepingScanTest {

    @Test
    fun onlyDotDirs_areSkippedAndDoNotFailEmptyIndex() {
        listOf(".obsidian", ".stfolder", ".quillpad-staging", ".git").forEach {
            assertTrue(VaultHousekeeping.shouldSkipDirectory(it))
        }
        assertFalse(VaultHousekeeping.shouldSkipDirectory("Notes"))
    }
}
