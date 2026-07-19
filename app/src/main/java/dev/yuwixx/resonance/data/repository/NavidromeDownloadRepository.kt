// Offline download/caching for Navidrome songs: enqueues background downloads (via
// SongDownloadManager/WorkManager), tracks per-song state in the song_downloads table, and
// resolves a downloaded song's local file path for playback substitution.
package dev.yuwixx.resonance.data.repository

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.database.dao.NavidromeAlbumDao
import dev.yuwixx.resonance.data.database.dao.NavidromeSongDao
import dev.yuwixx.resonance.data.database.dao.PlaylistDao
import dev.yuwixx.resonance.data.database.dao.SongDownloadDao
import dev.yuwixx.resonance.data.database.entity.DownloadState
import dev.yuwixx.resonance.data.database.entity.SongDownloadEntity
import dev.yuwixx.resonance.data.worker.SongDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavidromeDownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songDownloadDao: SongDownloadDao,
    private val navidromeSongDao: NavidromeSongDao,
    private val navidromeAlbumDao: NavidromeAlbumDao,
    private val playlistDao: PlaylistDao,
    private val navidromeRepository: NavidromeRepository,
    private val downloadManager: SongDownloadManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val downloadStates: Flow<Map<Long, SongDownloadEntity>> =
        songDownloadDao.getAll().map { list -> list.associateBy { it.songId } }

    // Hot in-memory snapshot of songId -> local file path, kept live from the DB. Playback
    // substitution (PlayerViewModel.toMediaItem(), MusicService.restoreQueue()) happens at
    // MediaItem-build call sites that aren't suspend today — a synchronous snapshot read avoids
    // threading suspend through every one of those call sites just for this one lookup.
    private val downloadedPaths = MutableStateFlow<Map<Long, String>>(emptyMap())

    init {
        scope.launch {
            songDownloadDao.getAll().collect { list ->
                downloadedPaths.value = list
                    .filter { it.state == DownloadState.DOWNLOADED }
                    .associate { it.songId to it.localFilePath }
            }
        }
    }

    fun localPathForSync(songId: Long): String? = downloadedPaths.value[songId]

    suspend fun isDownloaded(songId: Long): Boolean =
        songDownloadDao.getBySongId(songId)?.state == DownloadState.DOWNLOADED

    suspend fun localPathFor(songId: Long): String? {
        val entity = songDownloadDao.getBySongId(songId) ?: return null
        if (entity.state != DownloadState.DOWNLOADED) return null
        return entity.localFilePath.takeIf { File(it).exists() }
    }

    suspend fun downloadSongs(songIds: List<Long>) {
        songIds.forEach { downloadManager.enqueueSong(it) }
    }

    suspend fun downloadAlbum(albumId: Long) {
        val songIds = navidromeSongDao.getAllSongsList()
            .filter { (it.albumId.toLongOrNull() ?: stableLongId(it.albumId)) == albumId }
            .map { it.numericId }
        downloadManager.enqueueBatch(downloadManager.workNameForAlbum(albumId), songIds)
    }

    suspend fun downloadPlaylist(playlistId: Long) {
        val songIds = playlistDao.getPlaylistSongRefs(playlistId).map { it.songId }
        downloadManager.enqueueBatch(downloadManager.workNameForPlaylist(playlistId), songIds)
    }

    suspend fun removeDownload(songId: Long) = withContext(Dispatchers.IO) {
        downloadManager.cancelSong(songId)
        val entity = songDownloadDao.getBySongId(songId)
        if (entity != null) {
            runCatching { File(entity.localFilePath).delete() }
            songDownloadDao.delete(songId)
        }
    }

    suspend fun removeAll() = withContext(Dispatchers.IO) {
        downloadsDir().deleteRecursively()
        songDownloadDao.deleteAll()
    }

    suspend fun totalBytesUsed(): Long = songDownloadDao.sumDownloadedBytes()

    private fun downloadsDir(): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "downloads").apply { mkdirs() }

    // Called by SongDownloadWorker/SongBatchDownloadWorker — actually performs one song's
    // download and persists the result. Not private: the workers live in a different package.
    suspend fun performDownload(songId: Long) = withContext(Dispatchers.IO) {
        val entity = navidromeSongDao.getSongByNumericId(songId)
            ?: throw IllegalStateException("Song $songId not found in Navidrome cache")

        songDownloadDao.upsert(
            SongDownloadEntity(
                songId = songId,
                navidromeId = entity.navidromeId,
                localFilePath = "",
                state = DownloadState.DOWNLOADING,
                requestedAt = System.currentTimeMillis(),
            )
        )

        try {
            val downloadUrl = navidromeRepository.buildDownloadUrl(entity.navidromeId)
                ?: throw IllegalStateException("Not connected to a Navidrome server")

            val ext = entity.path.substringAfterLast('.', "mp3").ifBlank { "mp3" }
            val destFile = File(downloadsDir(), "$songId.$ext")

            val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout    = 300_000
            }
            try {
                connection.inputStream.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }

            songDownloadDao.upsert(
                SongDownloadEntity(
                    songId = songId,
                    navidromeId = entity.navidromeId,
                    localFilePath = destFile.absolutePath,
                    state = DownloadState.DOWNLOADED,
                    fileSizeBytes = destFile.length(),
                    requestedAt = System.currentTimeMillis(),
                    downloadedAt = System.currentTimeMillis(),
                )
            )
        } catch (e: Exception) {
            songDownloadDao.updateState(songId, DownloadState.FAILED, e.message)
            throw e
        }
    }
}
