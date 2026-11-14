package org.qosp.notes.components.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.data.sync.core.BaseResult
import org.qosp.notes.data.sync.core.Success
import org.qosp.notes.ui.reminders.ReminderManager

class SyncWorker(
    private val noteRepository: NoteRepository,
    private val reminderManager: ReminderManager,
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {

        val result = noteRepository.syncNotes()
        // A sync can import or drop reminders; their alarms are only set from here.
        reminderManager.rescheduleAll()
        workResultFor(result)
    }

    companion object {
        /** Anything but success is retried with WorkManager's backoff: scan incomplete, backend unavailable, ... */
        fun workResultFor(result: BaseResult): Result = when (result) {
            Success -> Result.success()
            else -> Result.retry()
        }
    }
}
