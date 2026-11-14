package org.qosp.notes.ui.settings

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.repo.NoteRepositoryVaultHarness
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.fs.vault.VaultNoteDocument
import org.qosp.notes.data.sync.fs.vault.VaultSyncCoordinator
import org.qosp.notes.data.sync.fs.vault.VaultTreeAccess
import org.qosp.notes.preferences.PreferenceRepository
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelConflictsTest {

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

    private fun write(h: NoteRepositoryVaultHarness, path: String, title: String) {
        h.vaultFs.writeText(path, VaultNoteDocument.fromNote(Note(title = title, content = title), path).renderFullText())
    }

    @Test
    fun conflictList_countsOnlyConflictNamedMappedNotes() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        write(h, "Plan.md", "Plan")
        write(h, "Plan (conflict 1700000000).md", "Plan conflicted")
        write(h, "Work/Todo (conflict 5).txt", "Todo conflicted")
        write(h, "Other.md", "Other")
        h.syncNotes()
        val conflicts = viewModel(h).loadVaultConflicts()
        assertEquals(
            listOf("Plan (conflict 1700000000).md", "Work/Todo (conflict 5).txt"),
            conflicts.map { it.path }.sorted(),
        )
        assertEquals(setOf("Plan conflicted", "Todo conflicted"), conflicts.map { it.title }.toSet())
    }

    @Test
    fun conflictList_skipsBinnedNotes() = runTest {
        val h = NoteRepositoryVaultHarness(this)
        write(h, "Plan (conflict 1).md", "Plan conflicted")
        h.syncNotes()
        val note = h.repository.getAll().first().single()
        h.repository.moveNotesToBin(note)
        h.flushRemoteActions()
        assertEquals(emptyList<Any>(), viewModel(h).loadVaultConflicts())
    }
}
