package org.qosp.notes.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudServiceMigrationTest {

    @Test
    fun legacyDisabled_mapsToFileStorage() {
        assertEquals(CloudService.FILE_STORAGE, CloudService.DISABLED.toVaultMode())
    }

    @Test
    fun legacyNextcloud_mapsToFileStorage() {
        assertEquals(CloudService.FILE_STORAGE, CloudService.NEXTCLOUD.toVaultMode())
    }

    @Test
    fun fileStorage_unchanged() {
        assertEquals(CloudService.FILE_STORAGE, CloudService.FILE_STORAGE.toVaultMode())
    }
}
