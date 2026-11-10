package org.qosp.notes.ui.whatsnew

import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.data.WhatsNew
import org.qosp.notes.data.WhatsNewItem
import org.qosp.notes.preferences.PreferenceRepository

class WhatsNewViewModelTest {

    private val catalog = WhatsNew(
        updates = listOf(
            WhatsNewItem(id = 5, title = "Bug Fixes & Improvements", items = listOf("Improvements")),
            WhatsNewItem(id = 7, title = "New Color Scheme Options", items = listOf("More colors")),
        )
    )

    /** Stands in for [org.qosp.notes.preferences.PreferenceRepository.getEncryptedString]. */
    private val lastShownId = MutableStateFlow("")

    @Test
    fun `shows latest update when nothing was shown before`() = runTest {
        lastShownId.value = ""

        assertEquals(7, catalog.toShowFlow(lastShownId).first()?.id)
    }

    @Test
    fun `shows latest update when only older ones were shown`() = runTest {
        lastShownId.value = "5"

        assertEquals(7, catalog.toShowFlow(lastShownId).first()?.id)
    }

    @Test
    fun `shows nothing when latest update was already shown`() = runTest {
        lastShownId.value = "7"

        assertNull(catalog.toShowFlow(lastShownId).first())
    }

    @Test
    fun `treats unparsable last shown id as first run`() = runTest {
        lastShownId.value = "not-a-number"

        assertEquals(7, catalog.toShowFlow(lastShownId).first()?.id)
    }

    @Test
    fun `follows preference changes while collected`() = runTest {
        lastShownId.value = ""
        val updates = catalog.toShowFlow(lastShownId)

        assertEquals(7, updates.first()?.id)

        lastShownId.value = "7"
        assertNull(updates.first())
    }

    @Test
    fun `dialog can only be marked shown once across activity recreations`() {
        val model = WhatsNewViewModel(mockk(relaxed = true), mockk<PreferenceRepository>(relaxed = true))

        assertTrue(model.markDialogShown())
        assertFalse(model.markDialogShown())
    }
}
