package org.qosp.notes.data.sync.fs.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.preferences.CloudService

class VaultLegacyMappingClearTest {

    @Test
    fun legacyContentUriMapping_detectedForClearing() {
        val legacy = IdMapping(
            localNoteId = 1L,
            remoteNoteId = null,
            provider = CloudService.FILE_STORAGE,
            extras = null,
            isDeletedLocally = false,
            storageUri = "content://com.android.externalstorage.documents/document/primary%3Atest",
        )
        assertTrue(legacy.storageUri?.contains("://") == true)
    }

    @Test
    fun vaultRelativeMapping_notLegacy() {
        val modern = IdMapping(
            localNoteId = 2L,
            remoteNoteId = null,
            provider = CloudService.FILE_STORAGE,
            extras = null,
            isDeletedLocally = false,
            storageUri = "Work/Note.md",
        )
        assertFalse(modern.storageUri?.contains("://") == true)
    }
}
