package org.qosp.notes.ui

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.preferences.PreferenceRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelUpgradePromptTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun model(configuredAt: String = ""): ActivityViewModel {
        val prefs = mockk<PreferenceRepository>(relaxed = true)
        coEvery { prefs.getEncryptedString(PreferenceRepository.STORAGE_LOCATION) } returns flowOf("content://vault")
        coEvery { prefs.getEncryptedString(PreferenceRepository.VAULT_CONFIGURED_AT) } returns flowOf(configuredAt)
        val notes = mockk<NoteRepository>(relaxed = true)
        // Short-circuits the Nextcloud preference lookup, which a mock cannot serve.
        coEvery { notes.countIdMappings(any()) } returns 1
        return ActivityViewModel(
            notes, mockk(relaxed = true), prefs, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        )
    }

    @Test
    fun prompt_staysAvailableToASecondCollectorUntilResolved() = runTest(dispatcher) {
        val model = model()
        assertTrue(model.checkVaultUpgradePrompt())
        val first = model.vaultUpgradePrompt.first { it != null }
        // A rotation destroys the dialog and a new activity collects again: the prompt is still there.
        assertEquals(first, model.vaultUpgradePrompt.first())
        assertTrue(model.checkVaultUpgradePrompt())
        assertNotNull(model.vaultUpgradePrompt.value)
        assertEquals("content://vault", first!!.location)
    }

    @Test
    fun resolving_clearsItAndItIsNotAskedAgainThisProcess() = runTest(dispatcher) {
        val model = model()
        assertTrue(model.checkVaultUpgradePrompt())
        model.resolveVaultUpgradePrompt()
        assertNull(model.vaultUpgradePrompt.value)
        assertFalse(model.checkVaultUpgradePrompt())
        assertNull(model.vaultUpgradePrompt.value)
    }

    @Test
    fun confirmedFolder_neverPrompts() = runTest(dispatcher) {
        val model = model(configuredAt = "1")
        assertFalse(model.checkVaultUpgradePrompt())
        assertNull(model.vaultUpgradePrompt.value)
    }
}
