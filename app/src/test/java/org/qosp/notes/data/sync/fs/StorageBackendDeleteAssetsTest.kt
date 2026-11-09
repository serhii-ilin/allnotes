package org.qosp.notes.data.sync.fs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class StorageBackendDeleteAssetsTest {

    @Test
    fun deleteOperation_defaultsToRemovingAssets() {
        val op = org.qosp.notes.data.sync.core.RemoteOperation.Delete(org.qosp.notes.data.model.Note(id = 1L))
        assertTrue(op.removeAssets)
    }

    @Test
    fun binDeleteOperation_retainsAssets() {
        val op = org.qosp.notes.data.sync.core.RemoteOperation.Delete(
            org.qosp.notes.data.model.Note(id = 1L),
            removeAssets = false,
        )
        assertFalse(op.removeAssets)
    }
}
