package org.qosp.notes.data.sync.fs.vault

import androidx.documentfile.provider.DocumentFile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafVaultWriteStoreRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun recoveryItemCount_and_clearAllRecoveryState() {
        val root = DocumentFile.fromFile(tempFolder.newFolder("vault"))
        val store = SafVaultWriteStore(RuntimeEnvironment.getApplication(), root, scopeId = "test-scope")

        assertEquals(0, store.recoveryItemCount())

        store.writeToStaging("a.md", "alpha")
        store.writeToStaging("b.md", "beta body")
        store.saveBackup("a.md", "old alpha")

        assertEquals(3, store.recoveryItemCount())
        assertEquals(3, store.clearAllRecoveryState())
        assertEquals(0, store.recoveryItemCount())
    }
}
