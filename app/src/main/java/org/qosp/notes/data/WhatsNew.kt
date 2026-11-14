package org.qosp.notes.data

import kotlinx.serialization.Serializable

@Serializable
data class WhatsNew(
    val updates: List<WhatsNewItem>
)

@Serializable
data class WhatsNewItem(
    val id: Int,
    val title: String,
    val items: List<String>
)

/** Highest update id in the catalog, or 0 when there are no updates. */
val WhatsNew.latestId: Int get() = updates.maxOfOrNull { it.id } ?: 0

/** The newest update, but only if it is newer than [lastShownId]. */
fun WhatsNew.updateToShow(lastShownId: Int): WhatsNewItem? =
    updates.maxByOrNull { it.id }?.takeIf { it.id > lastShownId }
