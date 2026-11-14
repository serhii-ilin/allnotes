package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.fs.vault.InMemoryVaultFs
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultNoteDocument
import org.qosp.notes.data.sync.fs.vault.VaultNoteIds
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner

/**
 * Two mapped files carry one id. Which file keeps it must depend on the files, not on the order each device
 * happened to create its local mappings in.
 */
@RunWith(RobolectricTestRunner::class)
class VaultNoteIdOwnerOrderTest {

    private companion object {
        const val SHARED_ID = "shared-id"
        const val OLDER_CREATED = 1_700_000_000L
        const val NEWER_CREATED = 1_700_000_100L
    }

    private suspend fun TestScope.phone(
        scope: String,
        importOrder: List<String>,
        createdByTitle: Map<String, Long> = mapOf("Alpha" to OLDER_CREATED, "Beta" to NEWER_CREATED),
    ): NoteRepositoryVaultHarness {
        val fs = InMemoryVaultFs(scopeId = scope, caseInsensitive = true)
        val h = NoteRepositoryVaultHarness(this, fs)
        importOrder.forEach { title ->
            h.insertAndSync(Note(title = title, content = "Body of $title"))
        }
        val idsByTitle = h.noteDao.getAll(SortMethod.TITLE_ASC).first().associate { it.title to it.id }
        for (title in listOf("Alpha", "Beta")) {
            val mapping = h.idMappingDao.getByLocalIdAndProvider(idsByTitle.getValue(title), CloudService.FILE_STORAGE)!!
            h.vaultFs.writeText(mapping.storageUri!!, sharedFile(title, createdByTitle.getValue(title)))
            val state = VaultMappingState.resolve(mapping.extras)!!
            h.idMappingDao.update(
                mapping.copy(extras = VaultMappingState.encode(state.copy(noteUid = SHARED_ID))),
            )
        }
        return h
    }

    private fun sharedFile(title: String, created: Long): String {
        return "---\n" +
            "allnotes-id: \"$SHARED_ID\"\n" +
            "created: $created\n" +
            "modified: $created\n" +
            "---\n" +
            "Body of $title\n"
    }

    @Test
    fun oppositeMappingOrders_keepTheSameFile_andReIdentifyTheSameOtherFile() = runTest {
        // On B the newer file is imported first, so the old "lowest local mapping id" rule picked the other file.
        val a = phone("phone-a", importOrder = listOf("Alpha", "Beta"))
        val b = phone("phone-b", importOrder = listOf("Beta", "Alpha"))

        a.syncNotes()
        b.syncNotes()

        val alphaA = a.vaultFs.readText("Alpha.md")!!
        val alphaB = b.vaultFs.readText("Alpha.md")!!
        val betaA = a.vaultFs.readText("Beta.md")!!
        val betaB = b.vaultFs.readText("Beta.md")!!

        assertEquals("both devices write the same older file", alphaA, alphaB)
        assertEquals("both devices write the same copy", betaA, betaB)
        assertEquals(SHARED_ID, VaultNoteDocument.parse(alphaA).frontMatter.id)
        assertEquals(SHARED_ID, VaultNoteDocument.parse(alphaB).frontMatter.id)
        val copyId = VaultNoteIds.forFile("Beta.md", NEWER_CREATED)
        assertNotEquals(SHARED_ID, copyId)
        assertEquals(copyId, VaultNoteDocument.parse(betaA).frontMatter.id)
        assertEquals(copyId, VaultNoteDocument.parse(betaB).frontMatter.id)
    }

    @Test
    fun equalCreatedTimes_useTheNormalizedPath_onBothDevices() = runTest {
        val created = mapOf("Alpha" to OLDER_CREATED, "Beta" to OLDER_CREATED)
        val a = phone("phone-c", importOrder = listOf("Alpha", "Beta"), createdByTitle = created)
        val b = phone("phone-d", importOrder = listOf("Beta", "Alpha"), createdByTitle = created)

        a.syncNotes()
        b.syncNotes()

        val alphaA = a.vaultFs.readText("Alpha.md")!!
        val alphaB = b.vaultFs.readText("Alpha.md")!!
        val betaA = a.vaultFs.readText("Beta.md")!!
        val betaB = b.vaultFs.readText("Beta.md")!!
        assertEquals(alphaA, alphaB)
        assertEquals(betaA, betaB)
        assertEquals(SHARED_ID, VaultNoteDocument.parse(alphaA).frontMatter.id)
        val copyId = VaultNoteIds.forFile("Beta.md", OLDER_CREATED)
        assertEquals(copyId, VaultNoteDocument.parse(betaA).frontMatter.id)
        assertEquals(copyId, VaultNoteDocument.parse(betaB).frontMatter.id)
    }
}
