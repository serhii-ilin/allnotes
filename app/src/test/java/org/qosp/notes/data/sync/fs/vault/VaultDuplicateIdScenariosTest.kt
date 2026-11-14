package org.qosp.notes.data.sync.fs.vault

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.preferences.SortMethod
import org.qosp.notes.ui.settings.SettingsViewModel
import org.robolectric.RobolectricTestRunner

/** A shared id never hides a file: an extra copy is imported as its own note with its own id. */
@RunWith(RobolectricTestRunner::class)
class VaultDuplicateIdScenariosTest {

    private suspend fun NoteRepositoryVaultHarness.pathOf(id: Long): String =
        idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!

    private suspend fun NoteRepositoryVaultHarness.noteIdAt(path: String): Long =
        idMappingDao.getAllByCloudService(CloudService.FILE_STORAGE)
            .single { VaultPaths.vaultMappingPath(it.storageUri) == path }
            .localNoteId

    private fun NoteRepositoryVaultHarness.idAt(path: String): String? =
        VaultNoteDocument.parse(vaultFs.readText(path).orEmpty()).frontMatter.id

    private suspend fun NoteRepositoryVaultHarness.liveNotes(): List<Note> =
        noteDao.getAll(SortMethod.TITLE_ASC).first().filterNot { it.isDeleted }

    private fun viewModel(h: NoteRepositoryVaultHarness): SettingsViewModel {
        val prefs = mockk<PreferenceRepository>(relaxed = true)
        coEvery { prefs.getEncryptedString(any()) } returns flowOf("3")
        return SettingsViewModel(
            h.context,
            prefs,
            h.repository,
            mockk<BackendProvider>(relaxed = true),
            mockk<VaultTreeAccess>(relaxed = true),
            VaultSyncCoordinator(prefs),
            mockk<org.qosp.notes.ui.reminders.ReminderManager>(relaxed = true),
        )
    }

    @Test
    fun copiedFile_isImportedAsItsOwnNote_withAPathDerivedId() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Plan", content = "Body"))
        val planRaw = h.vaultFs.readText("Plan.md")!!
        val created = VaultNoteDocument.parse(planRaw).frontMatter.created
        h.vaultFs.writeText("Plan copy.md", planRaw)

        h.syncNotes()

        assertEquals(2, h.localNoteCount())
        assertEquals("Plan.md", h.pathOf(id))
        val copyId = h.noteIdAt("Plan copy.md")
        assertNotEquals(id, copyId)
        assertFalse(h.getNote(copyId)!!.isDeleted)
        assertEquals("Body", h.getNote(copyId)!!.content.trim())
        assertEquals(
            VaultNoteIds.forFile("Plan copy.md", created),
            h.idAt("Plan copy.md"),
        )
        val planAfter = h.vaultFs.readText("Plan.md")
        val copyAfter = h.vaultFs.readText("Plan copy.md")

        h.syncNotes()

        assertEquals("the third sync rewrote the original", planAfter, h.vaultFs.readText("Plan.md"))
        assertEquals("the third sync rewrote the copy", copyAfter, h.vaultFs.readText("Plan copy.md"))
        assertEquals(2, h.localNoteCount())
    }

    @Test
    fun conflictCopy_carriesItsOwnId_andIsListedInSettings() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val tree = h.tree()
        val id = h.insertAndSync(Note(title = "C", content = "Base", modifiedDate = 1L))
        val path = h.pathOf(id)
        tree.externalEdit(path, "From PC")
        val edited = h.getNote(id)!!.copy(content = "App edit", modifiedDate = 99L)
        val mapping = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!
        h.backend.updateNote(edited, mapping)
        val conflictPath = h.conflictFiles().single()

        assertNotEquals(h.idAt(path), h.idAt(conflictPath))

        h.syncNotes()

        assertEquals(2, h.localNoteCount())
        assertFalse(h.getNote(id)!!.isDeleted)
        assertEquals(path, h.pathOf(id))
        val conflicts = viewModel(h).loadVaultConflicts()
        assertEquals(listOf(conflictPath), conflicts.map { it.path })
    }

    @Test
    fun resurrectedOldPathAndEditedNewPath_bothTextsSurvive_withDistinctIds() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        val id = h.insertAndSync(Note(title = "Plan", content = "Original body"))
        val planRaw = h.vaultFs.readText("Plan.md")!!
        assertFalse("the note title must come from the file name", planRaw.contains("title:"))
        // Syncthing resurrected the old path under a new title while the renamed file was edited.
        h.vaultFs.writeText("Ideas.md", planRaw.replaceFirst("---\n", "---\ntitle: \"Ideas\"\n"))
        h.vaultFs.writeText("Plan.md", planRaw.replace("Original body", "Edited body"))

        h.syncNotes()
        h.syncNotes()

        assertEquals(2, h.localNoteCount())
        assertFalse(h.getNote(id)!!.isDeleted)
        assertEquals("Plan.md", h.pathOf(id))
        val bodies = h.liveNotes().map { it.content.trim() }
        assertTrue("edited text lost: $bodies", bodies.any { it == "Edited body" })
        assertTrue("resurrected text lost: $bodies", bodies.any { it == "Original body" })
        assertNotEquals(h.idAt("Plan.md"), h.idAt("Ideas.md"))
        assertEquals(2, h.liveNotes().size)
    }

    @Test
    fun goneMappedPath_twoFilesWithItsId_newestKeepsTheNote_otherIsImported() = runTest {
        val fs = InMemoryVaultFs(caseInsensitive = true)
        val h = NoteRepositoryVaultHarness(this, fs, OffsetMtimeVaultFs(fs))
        val id = h.insertAndSync(Note(title = "Plan", content = "Body"))
        val planRaw = h.vaultFs.readText("Plan.md")!!
        h.vaultFs.writeText("Older.md", planRaw)
        h.vaultFs.writeText("Newest.md", planRaw)
        h.vaultFs.delete("Plan.md")

        h.syncNotes()
        h.syncNotes()

        assertEquals(2, h.localNoteCount())
        assertEquals("Newest.md", h.pathOf(id))
        val other = h.noteIdAt("Older.md")
        assertNotEquals(id, other)
        assertFalse(h.getNote(id)!!.isDeleted)
        assertFalse(h.getNote(other)!!.isDeleted)
        assertNotNull(h.idAt("Older.md"))
        assertNotEquals(h.idAt("Older.md"), h.idAt("Newest.md"))
    }

    /** Makes the mtime ordering explicit so "newest" does not depend on writes landing in different seconds. */
    private class OffsetMtimeVaultFs(private val delegate: InMemoryVaultFs) : VaultFs by delegate {
        override fun listChildren(relativeDir: String): List<VaultFs.VaultFsEntry> =
            delegate.listChildren(relativeDir).map { entry ->
                when (entry.relativePath) {
                    "Older.md" -> entry.copy(lastModifiedMs = OLD_MTIME_MS)
                    "Newest.md" -> entry.copy(lastModifiedMs = NEW_MTIME_MS)
                    else -> entry
                }
            }

        override fun lastModifiedEpochSec(relativePath: String): Long = when (relativePath) {
            "Older.md" -> OLD_MTIME_MS / 1000
            "Newest.md" -> NEW_MTIME_MS / 1000
            else -> delegate.lastModifiedEpochSec(relativePath)
        }

        private companion object {
            const val OLD_MTIME_MS = 1_000_000L
            const val NEW_MTIME_MS = 2_000_000L
        }
    }
}
