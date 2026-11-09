package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** All vault tree I/O and sync must run on this dispatcher (never the UI thread). */
object VaultIo {
    suspend fun <T> run(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
