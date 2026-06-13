package dev.yuwixx.resonance.data.service

import android.content.Context
import android.net.Uri
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.database.dao.LikedSongsDao
import dev.yuwixx.resonance.data.database.dao.PlaylistDao
import dev.yuwixx.resonance.data.database.dao.SongDao
import dev.yuwixx.resonance.data.database.entity.LikedSongEntity
import dev.yuwixx.resonance.data.database.entity.PlaylistEntity
import dev.yuwixx.resonance.data.database.entity.PlaylistSongCrossRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@JsonClass(generateAdapter = true)
data class PlaylistBackup(
    val name: String,
    val songPaths: List<String>,
)

@JsonClass(generateAdapter = true)
data class BackupFile(
    val version: Int = 1,
    val exportedAt: Long,
    val likedSongPaths: List<String>,
    val playlists: List<PlaylistBackup>,
)

sealed class RestoreResult {
    data class Success(val likedRestored: Int, val playlistsRestored: Int) : RestoreResult()
    data class Error(val message: String) : RestoreResult()
}

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moshi: Moshi,
    private val likedSongsDao: LikedSongsDao,
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
) {
    private val adapter = moshi.adapter(BackupFile::class.java)

    suspend fun createBackup(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val likedPaths = likedSongsDao.getLikedSongPaths()
            val playlists = playlistDao.getAllPlaylistsList()
            val playlistBackups = playlists
                .filter { !it.isReadOnly }
                .map { playlist ->
                    PlaylistBackup(
                        name = playlist.name,
                        songPaths = playlistDao.getSongPathsForPlaylist(playlist.id),
                    )
                }

            val backup = BackupFile(
                exportedAt = System.currentTimeMillis(),
                likedSongPaths = likedPaths,
                playlists = playlistBackups,
            )

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(adapter.toJson(backup).toByteArray())
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun restoreBackup(uri: Uri): RestoreResult = withContext(Dispatchers.IO) {
        try {
            val json = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: return@withContext RestoreResult.Error("Could not read file")

            val backup = adapter.fromJson(json)
                ?: return@withContext RestoreResult.Error("Invalid backup format")

            if (backup.version != 1) {
                return@withContext RestoreResult.Error("Unsupported backup version ${backup.version}")
            }

            var likedRestored = 0
            for (path in backup.likedSongPaths) {
                val song = songDao.getSongByPath(path) ?: continue
                likedSongsDao.likeSong(LikedSongEntity(songId = song.id))
                likedRestored++
            }

            var playlistsRestored = 0
            for (pb in backup.playlists) {
                val playlistId = playlistDao.insertPlaylist(
                    PlaylistEntity(name = pb.name, artworkUri = null)
                )
                var position = 0
                for (path in pb.songPaths) {
                    val song = songDao.getSongByPath(path) ?: continue
                    playlistDao.addSongToPlaylist(
                        PlaylistSongCrossRef(playlistId = playlistId, songId = song.id, position = position++)
                    )
                }
                if (position > 0) playlistsRestored++
            }

            RestoreResult.Success(likedRestored, playlistsRestored)
        } catch (e: Exception) {
            RestoreResult.Error(e.message ?: "Unknown error")
        }
    }
}
