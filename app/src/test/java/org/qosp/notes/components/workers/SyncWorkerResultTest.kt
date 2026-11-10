package org.qosp.notes.components.workers

import androidx.work.ListenableWorker.Result
import org.junit.Assert.assertEquals
import org.junit.Test
import org.qosp.notes.data.sync.core.GenericError
import org.qosp.notes.data.sync.core.Success

class SyncWorkerResultTest {
    @Test
    fun success_isSuccess() {
        assertEquals(Result.success(), SyncWorker.workResultFor(Success))
    }

    @Test
    fun genericError_isRetriedSoAnIncompleteScanTriesAgain() {
        assertEquals(Result.retry(), SyncWorker.workResultFor(GenericError("Vault scan incomplete")))
    }
}
