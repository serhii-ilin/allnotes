package org.qosp.notes.data.sync.fs

import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.data.sync.fs.vault.VaultContentHash
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultSyncImport

class StorageBackendPrePushConflictTest {

    @Test
    fun diskEditBeforePush_hashDiffersFromBaseline() {
        val path = "Note.md"
        val local = Note(id = 1L, title = "Note", content = "From app", modifiedDate = 2L)
        val baseline = VaultSyncImport.canonicalFileHash(local, path, null, "")
        val diskYaml = "---\n---\nFrom Syncthing\n"
        val remote = SyncNote(0, path, diskYaml, "Note", 3L)
        val diskHash = VaultSyncImport.logicalHashFromRemoteFile(remote, local, path, null)
        assertNotEquals(baseline, diskHash)
        assertNotEquals("", VaultMappingState(baselineHash = baseline).baselineHash)
    }
}
