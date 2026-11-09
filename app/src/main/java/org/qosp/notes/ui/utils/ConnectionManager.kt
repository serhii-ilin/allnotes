package org.qosp.notes.ui.utils

import android.content.Context
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SyncMode

/** Vault sync is local/SAF-only; network is not required for sync. */
class ConnectionManager(@Suppress("UNUSED_PARAMETER") private val context: Context) {

    fun isConnectionAvailable(syncMode: SyncMode?, cloudService: CloudService?): Boolean = true
}
