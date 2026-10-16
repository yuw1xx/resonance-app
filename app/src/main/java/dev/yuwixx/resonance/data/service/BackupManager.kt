package dev.yuwixx.resonance.data.service

import android.content.Context
import android.net.Uri
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.database.dao.HistoryDao
import dev.yuwixx.resonance.data.database.dao.LikedSongsDao
import dev.yuwixx.resonance.data.database.dao.PlaylistDao
import dev.yuwixx.resonance.data.database.dao.SmartPlaylistDao
import dev.yuwixx.resonance.data.database.dao.SongDao
import dev.yuwixx.resonance.data.database.entity.HistoryEntity
import dev.yuwixx.resonance.data.database.entity.LikedSongEntity
import dev.yuwixx.resonance.data.database.entity.PlaylistEntity
import dev.yuwixx.resonance.data.database.entity.PlaylistSongCrossRef
import dev.yuwixx.resonance.data.database.entity.SmartPlaylistEntity
import dev.yuwixx.resonance.data.database.entity.SongEntity
import dev.yuwixx.resonance.data.model.BackupFile
import dev.yuwixx.resonance.data.model.BackupHistoryEntry
import dev.yuwixx.resonance.data.model.BackupPlaylistEntry
import dev.yuwixx.resonance.data.model.BackupSongEntry
import dev.yuwixx.resonance.data.playlist.SmartRules
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.data.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

sealed class RestoreResult {
    data class Success(
        val likedRestored: Int,
        val likedSkipped: Int,
        val playlistsRestored: Int,
        val playlistSongsSkipped: Int,
        val settingsRestored: Int,
        val historyRestored: Int,
        val historySkipped: Int,
    ) : RestoreResult()
    data class Error(val message: String) : RestoreResult()
}

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moshi: Moshi,
    private val likedSongsDao: LikedSongsDao,
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val historyDao: HistoryDao,
    private val prefs: ResonancePreferences,
    private val smartPlaylistDao: SmartPlaylistDao,
) {
    private val adapter = moshi.adapter(BackupFile::class.java).indent("  ")
    private val rulesAdapter = moshi.adapter(SmartRules::class.java)

    suspend fun createBackup(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val likedSongs = likedSongsDao.getLikedSongIds().first()
                .mapNotNull { songDao.getSongById(it) }
                .map { it.toBackupEntry() }

            val smartRules = smartPlaylistDao.getAll().associate { it.playlistId to it.rulesJson }
            val playlists = playlistDao.getAllPlaylistsList()
                .filter { !it.isReadOnly } // excludes auto-generated Smart Mixes (isMix implies isReadOnly)
                .map { playlist ->
                    smartRules[playlist.id]?.let { json ->
                        val rules = runCatchingCancellable { rulesAdapter.fromJson(json) }.getOrNull()
                        if (rules != null) return@map BackupPlaylistEntry(name = playlist.name, songs = emptyList(), smartRules = rules)
                    }
                    val songs = playlistDao.getPlaylistSongRefs(playlist.id)
                        .mapNotNull { ref -> songDao.getSongById(ref.songId) }
                        .map { it.toBackupEntry() }
                    BackupPlaylistEntry(name = playlist.name, songs = songs)
                }

            val settings = prefs.exportBackupPreferences()

            val history = historyDao.getAllHistory().mapNotNull { h ->
                val song = songDao.getSongById(h.songId) ?: return@mapNotNull null
                BackupHistoryEntry(
                    song = song.toBackupEntry(),
                    listenedAt = h.listenedAt,
                    durationListened = h.durationListened,
                    percentageListened = h.percentageListened,
                )
            }

            val backup = BackupFile(
                exportedAt = System.currentTimeMillis(),
                likedSongs = likedSongs,
                playlists = playlists,
                settings = settings,
                history = history,
            )

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(adapter.toJson(backup).toByteArray())
            } ?: return@withContext false
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

            // v2 backups have no history field (it defaults to empty) and still restore.
            if (backup.version !in 2..3) {
                return@withContext RestoreResult.Error(
                    "Unsupported backup version ${backup.version} — please re-export from a current version of the app"
                )
            }

            // Match by path first, then by title, artist and album for files that moved.
            val contentIndex = buildContentIndex()

            var likedRestored = 0
            var likedSkipped = 0
            for (entry in backup.likedSongs) {
                val song = findSong(entry, contentIndex)
                if (song != null) {
                    likedSongsDao.likeSong(LikedSongEntity(songId = song.id))
                    likedRestored++
                } else {
                    likedSkipped++
                }
            }

            var playlistsRestored = 0
            var playlistSongsSkipped = 0
            for (pb in backup.playlists) {
                val existing = playlistDao.getPlaylistByName(pb.name)
                val playlistId = existing?.id
                    ?: playlistDao.insertPlaylist(PlaylistEntity(name = pb.name, artworkUri = null))
                // Reuse a playlist with the same name, so importing twice doesn't duplicate it.
                if (existing != null) playlistDao.clearPlaylist(playlistId)
                pb.smartRules?.let { rules ->
                    smartPlaylistDao.upsert(
                        SmartPlaylistEntity(playlistId, rulesAdapter.toJson(rules))
                    )
                    playlistsRestored++
                    continue
                }
                var position = 0
                for (entry in pb.songs) {
                    val song = findSong(entry, contentIndex)
                    if (song == null) {
                        playlistSongsSkipped++
                        continue
                    }
                    playlistDao.addSongToPlaylist(
                        PlaylistSongCrossRef(playlistId = playlistId, songId = song.id, position = position++)
                    )
                }
                if (position > 0) playlistsRestored++
            }

            val settingsRestored = prefs.importBackupPreferences(backup.settings)

            // Skip listens already present (same song and time), so importing twice doesn't
            // double play counts.
            val existingHistoryKeys = historyDao.getAllHistory().mapTo(HashSet()) { it.songId to it.listenedAt }
            var historyRestored = 0
            var historySkipped = 0
            for (entry in backup.history) {
                val song = findSong(entry.song, contentIndex)
                if (song == null) {
                    historySkipped++
                    continue
                }
                val key = song.id to entry.listenedAt
                if (!existingHistoryKeys.add(key)) continue // already restored, silent no-op
                historyDao.insertHistory(
                    HistoryEntity(
                        songId = song.id,
                        listenedAt = entry.listenedAt,
                        durationListened = entry.durationListened,
                        percentageListened = entry.percentageListened,
                    )
                )
                historyRestored++
            }

            RestoreResult.Success(
                likedRestored = likedRestored,
                likedSkipped = likedSkipped,
                playlistsRestored = playlistsRestored,
                playlistSongsSkipped = playlistSongsSkipped,
                settingsRestored = settingsRestored,
                historyRestored = historyRestored,
                historySkipped = historySkipped,
            )
        } catch (e: Exception) {
            RestoreResult.Error(e.message ?: "Unknown error")
        }
    }

    private suspend fun findSong(entry: BackupSongEntry, contentIndex: Map<String, SongEntity>): SongEntity? =
        songDao.getSongByPath(entry.path)
            ?: contentIndex[contentKey(entry.title, entry.artist, entry.album)]

    private suspend fun buildContentIndex(): Map<String, SongEntity> =
        songDao.getAllSongs().first().associateBy { contentKey(it.title, it.artist, it.album) }

    private fun contentKey(title: String, artist: String, album: String) =
        "${title.trim().lowercase()}|${artist.trim().lowercase()}|${album.trim().lowercase()}"

    private fun SongEntity.toBackupEntry() = BackupSongEntry(path = path, title = title, artist = artist, album = album)
}
