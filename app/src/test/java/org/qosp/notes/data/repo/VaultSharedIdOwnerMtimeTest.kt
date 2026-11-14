package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.fs.vault.InMemoryVaultFs
import org.qosp.notes.data.sync.fs.vault.VaultFs
import org.qosp.notes.data.sync.fs.vault.VaultMappingState
import org.qosp.notes.data.sync.fs.vault.VaultNoteDocument
import org.qosp.notes.data.sync.fs.vault.VaultNoteIds
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod
import org.robolectric.RobolectricTestRunner

/**
 * A file copied with `cp` keeps the original's `created` and gets a newer mtime. The copy's name may sort first,
 * so only the mtime tells the two apart; every device must pick the same original without looking at its own
 * mappings.
 */
@RunWith(RobolectricTestRunner::class)
class VaultSharedIdOwnerMtimeTest {

    private companion object {
        const val SHARED_ID = "shared-id"
        const val CREATED = 1_700_000_000L
        const val ORIGINAL_PATH = "Note.md"
        const val COPY_PATH = "Note (copy).md"
        const val ORIGINAL_MTIME_MS = 1_000_000L
        const val COPY_MTIME_MS = 9_000_000L
    }

    /** The copy is newer, as `cp` leaves it. */
    private class CopyMtimeVaultFs(private val delegate: InMemoryVaultFs) : VaultFs by delegate {
        private fun mtimeMs(path: String): Long? = when (path) {
            ORIGINAL_PATH -> ORIGINAL_MTIME_MS
            COPY_PATH -> COPY_MTIME_MS
            else -> null
        }

        override fun lastModifiedEpochSec(relativePath: String): Long =
            mtimeMs(relativePath)?.div(1000) ?: delegate.lastModifiedEpochSec(relativePath)

        override fun listChildren(relativeDir: String): List<VaultFs.VaultFsEntry> =
            delegate.listChildren(relativeDir).map { entry ->
                mtimeMs(entry.relativePath)?.let { entry.copy(lastModifiedMs = it) } ?: entry
            }
    }

    private suspend fun NoteRepositoryVaultHarness.pathOf(title: String): String {
        val id = noteDao.getAll(SortMethod.TITLE_ASC).first().single { it.title == title }.id
        return idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!.storageUri!!
    }

    private fun sharedFile(title: String): String = "---\n" +
        "allnotes-id: \"$SHARED_ID\"\n" +
        "created: $CREATED\n" +
        "modified: $CREATED\n" +
        "---\n" +
        "Body of $title\n"

    private suspend fun TestScope.phone(scope: String, importOrder: List<String>): NoteRepositoryVaultHarness {
        val fs = InMemoryVaultFs(scopeId = scope, caseInsensitive = true)
        val h = NoteRepositoryVaultHarness(this, fs, CopyMtimeVaultFs(fs))
        importOrder.forEach { title ->
            h.insertAndSync(Note(title = title, content = "Body of $title"))
        }
        for (title in listOf("Note", "Note (copy)")) {
            val id = h.noteDao.getAll(SortMethod.TITLE_ASC).first().single { it.title == title }.id
            val mapping = h.idMappingDao.getByLocalIdAndProvider(id, CloudService.FILE_STORAGE)!!
            h.vaultFs.writeText(mapping.storageUri!!, sharedFile(title))
            val state = VaultMappingState.resolve(mapping.extras)!!
            h.idMappingDao.update(
                mapping.copy(extras = VaultMappingState.encode(state.copy(noteUid = SHARED_ID))),
            )
        }
        return h
    }

    @Test
    fun copiedFileWithANewerMtime_leavesTheIdOnTheOriginal_onBothDevices() = runTest {
        // B imported the copy first; that ordering must not decide who keeps the shared id.
        val a = phone("phone-a", listOf("Note", "Note (copy)"))
        val b = phone("phone-b", listOf("Note (copy)", "Note"))

        a.syncNotes()
        b.syncNotes()

        val originalA = a.vaultFs.readText(ORIGINAL_PATH)!!
        val originalB = b.vaultFs.readText(ORIGINAL_PATH)!!
        val copyA = a.vaultFs.readText(COPY_PATH)!!
        val copyB = b.vaultFs.readText(COPY_PATH)!!

        assertEquals(SHARED_ID, VaultNoteDocument.parse(originalA).frontMatter.id)
        assertEquals(SHARED_ID, VaultNoteDocument.parse(originalB).frontMatter.id)
        assertEquals(
            "the copy must get its own path-derived id",
            VaultNoteIds.forFile(COPY_PATH, CREATED),
            VaultNoteDocument.parse(copyA).frontMatter.id,
        )
        assertEquals(VaultNoteDocument.parse(copyA).frontMatter.id, VaultNoteDocument.parse(copyB).frontMatter.id)
        assertNotEquals(
            VaultNoteDocument.parse(originalA).frontMatter.id,
            VaultNoteDocument.parse(copyA).frontMatter.id,
        )
        assertEquals("the two devices wrote different originals", originalA, originalB)
        assertEquals("the two devices wrote different copies", copyA, copyB)
        assertEquals(ORIGINAL_PATH, a.pathOf("Note"))
        assertEquals(ORIGINAL_PATH, b.pathOf("Note"))
        assertTrue(a.conflictFiles().isEmpty())
        assertTrue(b.conflictFiles().isEmpty())
    }
}
