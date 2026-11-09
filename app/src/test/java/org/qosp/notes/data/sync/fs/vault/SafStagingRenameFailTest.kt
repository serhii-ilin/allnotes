package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises production [VaultStagingNames] used by [SafVaultWriteStore.ensureStorageName]. */
class SafStagingRenameFailTest {

    @Test
    fun renameFails_usesActualProviderName() {
        val storageName = VaultStagingNames.resolveAfterCreate(
            createdName = "st-1.md",
            desired = "st-1",
            renameSucceeded = false,
            findAfterRename = null,
        )
        assertEquals("st-1.md", storageName)
    }
}
