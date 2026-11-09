package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** All vault tree I/O and sync must run on this dispatcher (never the UI thread). */
object VaultIo {
    var vaultDispatcher: CoroutineDispatcher = Dispatchers.IO

    suspend fun <T> run(block: suspend () -> T): T = withContext(vaultDispatcher) { block() }
}
