package dev.yuwixx.resonance.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.yuwixx.resonance.data.repository.NavidromeDownloadRepository

@HiltWorker
class SongDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val downloadRepository: NavidromeDownloadRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val songId = inputData.getLong(KEY_SONG_ID, -1L)
        if (songId == -1L) return Result.failure()
        return try {
            downloadRepository.performDownload(songId)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_SONG_ID = "song_id"
    }
}
