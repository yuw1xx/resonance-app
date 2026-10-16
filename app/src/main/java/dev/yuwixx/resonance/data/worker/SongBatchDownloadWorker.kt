package dev.yuwixx.resonance.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.yuwixx.resonance.data.repository.NavidromeDownloadRepository
import dev.yuwixx.resonance.data.repository.NotDownloadableException
import dev.yuwixx.resonance.data.util.runCatchingCancellable
import java.io.File
import kotlinx.coroutines.CancellationException

/** Downloads an album or playlist in one job, reporting overall progress. */
@HiltWorker
class SongBatchDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val downloadRepository: NavidromeDownloadRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val listFile = inputData.getString(KEY_LIST_FILE)?.let(::File) ?: return Result.failure()
        val songIds = runCatchingCancellable { listFile.readText() }.getOrNull()
            ?.split(",")
            ?.mapNotNull { it.toLongOrNull() }
            ?: return Result.failure()

        var retryable = false
        songIds.forEachIndexed { index, songId ->
            try {
                // Songs finished in an earlier attempt are skipped inside performDownload.
                downloadRepository.performDownload(songId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotDownloadableException) {
                // Permanent; nothing to retry.
            } catch (e: Exception) {
                retryable = true
            }
            setProgress(workDataOf(KEY_PROGRESS_PCT to (index + 1) * 100 / songIds.size))
        }
        val retry = retryable && runAttemptCount < MAX_ATTEMPTS
        if (!retry) listFile.delete()
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_LIST_FILE = "song_list_file"
        const val KEY_PROGRESS_PCT = "progress_pct"
        private const val MAX_ATTEMPTS = 3
    }
}
