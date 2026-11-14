package org.qosp.notes.data.repo

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.preferences.CloudService
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoteRepositoryVaultHarnessSmokeTest {

    @Test
    fun insertAndSync_createsVaultFileAndMapping() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Harness", content = "smoke"))
        val mapping = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)
        assertNotNull(mapping)
        val path = mapping!!.storageUri!!
        assertTrue(h.vaultFs.isFile(path))
    }
}
