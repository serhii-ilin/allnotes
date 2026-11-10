package org.qosp.notes.data.sync.fs.vault

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun sameTreeUnderDifferentUriEncodings_sharesOneScope() {
        val a = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ANotes")
        val b = Uri.parse("content://com.android.externalstorage.documents/tree/primary:Notes")
        assertEquals(SafVaultWriteStore.vaultScopeFromUri(a), SafVaultWriteStore.vaultScopeFromUri(b))
        assertNotEquals(
            SafVaultWriteStore.vaultScopeFromUri(a),
            SafVaultWriteStore.vaultScopeFromUri(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AOther")),
        )
    }

    @Test
    fun legacyScopeRecoveryDir_isMigratedAndItsJournalStaysOurs() {
        val context = RuntimeEnvironment.getApplication()
        val root = DocumentFile.fromTreeUri(
            context,
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ANotes"),
        )!!
        val legacyScope = VaultContentHash.ofText(root.uri.toString())
        val newScope = SafVaultWriteStore.vaultScopeFromRoot(root)
        assertNotEquals(legacyScope, newScope)

        val legacyStore = SafVaultWriteStore(context, root, scopeId = legacyScope)
        val backup = legacyStore.saveBackup("a.md", "good")
        legacyStore.writeJournal(
            VaultRecoveryJournal(
                legacyScope, 1L,
                listOf(
                    VaultRecoveryJournalEntry(
                        notePath = "a.md",
                        stagingToken = "st-gone",
                        backupToken = backup.token,
                        backupStorageName = backup.storageName,
                        expectedContentHash = VaultContentHash.ofText("new"),
                        phase = VaultRecoveryJournalEntry.PHASE_PUBLISHING,
                    ),
                ),
            ),
        )

        val store = SafVaultWriteStore(context, root)

        assertEquals(newScope, store.vaultScopeId())
        assertEquals(newScope, store.readJournal()?.vaultScopeId)
        assertEquals("good", store.readBackup(backup))
        assertFalse(java.io.File(context.filesDir, "vault-recovery/$legacyScope").exists())
        // Not treated as a foreign journal: recovery keeps it (nothing on disk to restore onto).
        VaultRecoveryService.recoverPending(store, 1L)
        assertEquals(listOf("a.md"), store.readJournal()?.entries?.map { it.notePath })
    }
}
