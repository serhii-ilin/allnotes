package org.qosp.notes.components.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.qosp.notes.data.repo.NoteRepository
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
        when (result) {
            Success -> Result.success()
            else -> Result.failure()
        }
    }
}
