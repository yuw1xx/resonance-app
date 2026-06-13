package dev.yuwixx.resonance.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.yuwixx.resonance.data.repository.MixRepository

@HiltWorker
class MixGeneratorWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val mixRepository: MixRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            mixRepository.generateAndPersistMixes()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
