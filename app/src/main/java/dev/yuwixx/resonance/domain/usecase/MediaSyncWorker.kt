// WorkManager worker that triggers a MediaStore library scan in the background.
// Used for both one-time (on-demand) and periodic (auto-scan interval) syncs.
package dev.yuwixx.resonance.domain.usecase

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.yuwixx.resonance.data.repository.MusicRepository
import java.util.concurrent.TimeUnit

@HiltWorker
class MediaSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val musicRepository: MusicRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            musicRepository.syncWithMediaStore()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "media_sync"
        const val PERIODIC_WORK_NAME = "media_sync_periodic"

                fun oneTimeRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<MediaSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

                fun periodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<MediaSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
    }
}
