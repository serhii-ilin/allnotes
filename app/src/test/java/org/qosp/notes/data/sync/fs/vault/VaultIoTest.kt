package org.qosp.notes.data.sync.fs.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultIoTest {

    @Test
    fun blocker6_vaultIo_runsOffCallingThread() = runBlocking {
        val caller = Thread.currentThread().name
        val ioThread = VaultIo.run { Thread.currentThread().name }
        assertFalse(
            "Vault IO should not run on the same thread as the caller ($caller)",
            ioThread == caller,
        )
        assertTrue(
            ioThread.contains("DefaultDispatcher", ignoreCase = true) ||
                ioThread.contains("worker", ignoreCase = true),
        )
    }

}
