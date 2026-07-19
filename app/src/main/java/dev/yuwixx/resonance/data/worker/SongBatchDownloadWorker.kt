package dev.yuwixx.resonance.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.yuwixx.resonance.data.repository.NavidromeDownloadRepository

// Downloads a whole album/playlist as one aggregate job (rather than one WorkManager job per
// song), reporting overall progress as it works through the list sequentially.
@HiltWorker
class SongBatchDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val downloadRepository: NavidromeDownloadRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val songIds = inputData.getString(KEY_SONG_IDS)
            ?.split(",")
            ?.mapNotNull { it.toLongOrNull() }
            ?: return Result.failure()
        if (songIds.isEmpty()) return Result.success()

        var anyFailed = false
        songIds.forEachIndexed { index, songId ->
            try {
                downloadRepository.performDownload(songId)
            } catch (e: Exception) {
                anyFailed = true
            }
            setProgress(workDataOf(KEY_PROGRESS_PCT to (index + 1) * 100 / songIds.size))
        }
        return if (anyFailed) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_SONG_IDS = "song_ids"
        const val KEY_PROGRESS_PCT = "progress_pct"
    }
}
