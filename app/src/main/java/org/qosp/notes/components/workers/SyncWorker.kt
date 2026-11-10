package org.qosp.notes.components.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.data.sync.core.Success

class SyncWorker(
    private val noteRepository: NoteRepository,
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {

        when (noteRepository.syncNotes()) {
            Success -> Result.success()
            else -> Result.failure()
        }
    }
}
