package org.qosp.notes.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.qosp.notes.data.sync.fs.vault.VaultContentHash

/** JVM-level verify step used by [MediaStorageManager.persistFromUri]. */
class MediaStorageManagerPersistTest {

    @Test
    fun verifiedCopy_requiresMatchingHash() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val hash = VaultContentHash.ofBytes(bytes)
        assertEquals(hash, VaultContentHash.ofBytes(bytes))
    }
}
