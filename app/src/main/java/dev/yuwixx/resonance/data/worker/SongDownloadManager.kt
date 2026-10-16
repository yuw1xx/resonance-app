package dev.yuwixx.resonance.data.worker

import android.content.Context
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SongDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: ResonancePreferences,
) {
    private val workManager = WorkManager.getInstance(context)

    private suspend fun buildConstraints(): Constraints {
        val wifiOnly = prefs.downloadWifiOnly.first()
        return Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
    }

    suspend fun enqueueSong(songId: Long) {
        val request = OneTimeWorkRequestBuilder<SongDownloadWorker>()
            .setConstraints(buildConstraints())
            .setInputData(workDataOf(SongDownloadWorker.KEY_SONG_ID to songId))
            .build()
        workManager.enqueueUniqueWork(workNameForSong(songId), ExistingWorkPolicy.KEEP, request)
    }

    // One job for a whole album or playlist. The IDs go in a file because WorkManager input
    // data is limited to 10 KB, which a large playlist exceeds.
    suspend fun enqueueBatch(workName: String, songIds: List<Long>) {
        if (songIds.isEmpty()) return
        val listFile = java.io.File(context.cacheDir, "download_batches/$workName.txt").apply {
            parentFile?.mkdirs()
            writeText(songIds.joinToString(","))
        }
        val request = OneTimeWorkRequestBuilder<SongBatchDownloadWorker>()
            .setConstraints(buildConstraints())
            .setInputData(workDataOf(SongBatchDownloadWorker.KEY_LIST_FILE to listFile.path))
            .build()
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelSong(songId: Long) {
        workManager.cancelUniqueWork(workNameForSong(songId))
    }

    fun workNameForAlbum(albumId: Long) = "download_album_$albumId"
    fun workNameForPlaylist(playlistId: Long) = "download_playlist_$playlistId"

    private fun workNameForSong(songId: Long) = "download_song_$songId"
}
