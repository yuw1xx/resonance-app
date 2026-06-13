package dev.yuwixx.resonance.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.yuwixx.resonance.data.repository.MusicRepository

@HiltWorker
class AutoScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val musicRepository: MusicRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            musicRepository.syncWithMediaStore()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
