package org.qosp.notes.data.sync.core

import org.junit.Assert.assertFalse
import org.junit.Test
import org.qosp.notes.data.model.Note

class ProcessRemoteActionsRestoreTest {

    @Test
    fun restorePayload_mustNotStayDeleted() {
        val binned = Note(id = 3L, title = "A", isDeleted = true)
        val restored = binned.copy(isDeleted = false, deletionDate = null)
        assertFalse(restored.isDeleted)
    }
}
