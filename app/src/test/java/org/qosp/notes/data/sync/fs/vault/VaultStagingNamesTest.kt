package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultStagingNamesTest {

    @Test
    fun resolveAfterCreate_renameFails_keepsProviderName() {
        assertEquals(
            "st-1.md",
            VaultStagingNames.resolveAfterCreate(
                createdName = "st-1.md",
                desired = "st-1",
                renameSucceeded = false,
                findAfterRename = null,
            ),
        )
    }

    @Test
    fun resolveAfterCreate_renameSucceeded_usesFindResult() {
        assertEquals(
            "st-1",
            VaultStagingNames.resolveAfterCreate(
                createdName = "st-1.md",
                desired = "st-1",
                renameSucceeded = true,
                findAfterRename = "st-1",
            ),
        )
    }
}
