package org.qosp.notes.data.sync.fs.vault.history

import kotlinx.serialization.Serializable

/**
 * `.allnotes/devices/<deviceId>/device.json`. Written only by its owner, so readers can trust it to say what
 * that device records and whether it tolerates a partial apply of a newer format.
 */
@Serializable
data class DeviceManifest(
    val format: Int = 1,
    val deviceId: String,
    val name: String,
    val appVersion: String,
    /** Ops this device records; anything outside is from a newer version. */
    val writes: List<String> = emptyList(),
    val partialApplyOk: Boolean = false,
    /** UTC day (`2026-11-10`) of the last flush; rewritten at most once a day. */
    val lastSeenDay: String? = null,
)
