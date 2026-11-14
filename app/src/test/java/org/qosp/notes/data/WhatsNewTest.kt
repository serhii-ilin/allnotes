package org.qosp.notes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsNewTest {

    private val catalog = WhatsNew(
        updates = listOf(
            WhatsNewItem(id = 2, title = "Bug Fixes", items = listOf("Fixed a bug")),
            WhatsNewItem(id = 7, title = "New Color Scheme Options", items = listOf("More colors")),
            WhatsNewItem(id = 5, title = "Bug Fixes & Improvements", items = listOf("Improvements")),
        )
    )

    @Test
    fun updateToShow_returnsLatest_whenItIsNewerThanLastShown() {
        assertEquals(7, catalog.updateToShow(lastShownId = 5)?.id)
    }

    @Test
    fun updateToShow_returnsNull_whenLatestWasAlreadyShown() {
        assertNull(catalog.updateToShow(lastShownId = 7))
    }

    @Test
    fun updateToShow_returnsNull_whenLastShownIsNewerThanLatest() {
        assertNull(catalog.updateToShow(lastShownId = 42))
    }

    @Test
    fun updateToShow_returnsLatest_whenNothingWasShownYet() {
        assertEquals(7, catalog.updateToShow(lastShownId = 1)?.id)
    }

    @Test
    fun updateToShow_returnsNull_forEmptyCatalog() {
        assertNull(WhatsNew(updates = emptyList()).updateToShow(lastShownId = 1))
    }

    @Test
    fun latestId_returnsHighestId() {
        assertEquals(7, catalog.latestId)
    }

    @Test
    fun latestId_returnsZero_forEmptyCatalog() {
        assertEquals(0, WhatsNew(updates = emptyList()).latestId)
    }
}
