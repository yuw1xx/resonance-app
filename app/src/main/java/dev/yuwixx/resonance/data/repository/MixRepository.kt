package dev.yuwixx.resonance.data.repository

import dev.yuwixx.resonance.data.database.dao.HistoryDao
import dev.yuwixx.resonance.data.database.dao.MixNavidromeSongDao
import dev.yuwixx.resonance.data.database.dao.NavidromeSongDao
import dev.yuwixx.resonance.data.database.dao.PlaylistDao
import dev.yuwixx.resonance.data.database.dao.SongDao
import dev.yuwixx.resonance.data.database.entity.MixNavidromeSongCrossRef
import dev.yuwixx.resonance.data.database.entity.NavidromeSongEntity
import dev.yuwixx.resonance.data.database.entity.PlaylistEntity
import dev.yuwixx.resonance.data.database.entity.PlaylistSongCrossRef
import dev.yuwixx.resonance.data.model.MixType
import dev.yuwixx.resonance.data.model.MusicSource
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MixRepository @Inject constructor(
    private val songDao: SongDao,
    private val navidromeSongDao: NavidromeSongDao,
    private val historyDao: HistoryDao,
    private val playlistDao: PlaylistDao,
    private val mixNavidromeSongDao: MixNavidromeSongDao,
    private val prefs: ResonancePreferences,
) {
    suspend fun generateAndPersistMixes() = withContext(Dispatchers.Default) {
        when (prefs.musicSource.first()) {
            MusicSource.LOCAL     -> generateLocalMixes()
            MusicSource.NAVIDROME -> generateNavidromeMixes()
        }
    }

    private suspend fun generateLocalMixes() {
        val now = System.currentTimeMillis()
        val weekAgo = now - 7L * 24 * 60 * 60 * 1000

        val topIds = historyDao.getTopSongIds(weekAgo, now, 50)
        val allSongs = songDao.getAllSongsList()
        val songMap = allSongs.associateBy { it.id }
        val weekSongs = topIds.mapNotNull { songMap[it.songId]?.toDomain() }

        val topArtist = weekSongs.flatMap { it.artists }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        if (topArtist != null) {
            val songs = allSongs
                .filter { it.artist.contains(topArtist, ignoreCase = true) || it.albumArtist.contains(topArtist, ignoreCase = true) }
                .map { it.toDomain() }
                .sortedByDescending { it.listenCount }
                .take(30)
            if (songs.size >= 3) saveLocalMix(MixType.TOP_ARTIST, topArtist, songs, now)
        }

        val topGenre = weekSongs.filter { it.genre.isNotEmpty() }
            .groupingBy { it.genre }.eachCount().maxByOrNull { it.value }?.key
        if (topGenre != null) {
            val songs = allSongs
                .filter { it.genre == topGenre }
                .map { it.toDomain() }
                .sortedByDescending { it.listenCount }
                .take(30)
            if (songs.size >= 3) saveLocalMix(MixType.TOP_GENRE, topGenre, songs, now)
        }

        val topDecade = weekSongs.filter { it.year > 0 }
            .groupingBy { it.year / 10 * 10 }.eachCount().maxByOrNull { it.value }?.key
        if (topDecade != null) {
            val songs = allSongs
                .filter { it.year in topDecade until topDecade + 10 }
                .map { it.toDomain() }
                .sortedByDescending { it.listenCount }
                .take(30)
            if (songs.size >= 3) saveLocalMix(MixType.ERA, "${topDecade}s Mix", songs, now)
        }

        val favorites = songDao.getMostPlayed(30).map { it.toDomain() }
        if (favorites.isNotEmpty()) saveLocalMix(MixType.FAVORITES, "Favorites Mix", favorites, now)

        if (weekSongs.size >= 3) saveLocalMix(MixType.RECENTLY_LOVED, "Recently Loved", weekSongs.take(30), now)
    }

    private suspend fun generateNavidromeMixes() {
        val now = System.currentTimeMillis()
        val weekAgo = now - 7L * 24 * 60 * 60 * 1000

        val topIds = historyDao.getTopSongIds(weekAgo, now, 50)
        val allSongs = navidromeSongDao.getAllSongsList()
        val songMap = allSongs.associateBy { it.numericId }
        val weekSongs = topIds.mapNotNull { songMap[it.songId]?.toDomain() }

        val topArtist = weekSongs.flatMap { it.artists }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        if (topArtist != null) {
            val songs = allSongs
                .filter { it.artist.contains(topArtist, ignoreCase = true) || it.albumArtist.contains(topArtist, ignoreCase = true) }
                .sortedByDescending { it.playCount }
                .take(30)
            if (songs.size >= 3) saveNavidromeMix(MixType.TOP_ARTIST, topArtist, songs, now)
        }

        val topGenre = weekSongs.filter { it.genre.isNotEmpty() }
            .groupingBy { it.genre }.eachCount().maxByOrNull { it.value }?.key
        if (topGenre != null) {
            val songs = allSongs
                .filter { it.genre == topGenre }
                .sortedByDescending { it.playCount }
                .take(30)
            if (songs.size >= 3) saveNavidromeMix(MixType.TOP_GENRE, topGenre, songs, now)
        }

        val topDecade = weekSongs.filter { it.year > 0 }
            .groupingBy { it.year / 10 * 10 }.eachCount().maxByOrNull { it.value }?.key
        if (topDecade != null) {
            val songs = allSongs
                .filter { it.year in topDecade until topDecade + 10 }
                .sortedByDescending { it.playCount }
                .take(30)
            if (songs.size >= 3) saveNavidromeMix(MixType.ERA, "${topDecade}s Mix", songs, now)
        }

        val favorites = allSongs.sortedByDescending { it.playCount }.take(30)
        if (favorites.isNotEmpty()) saveNavidromeMix(MixType.FAVORITES, "Favorites Mix", favorites, now)

        if (weekSongs.size >= 3) {
            val weekNavSongs = topIds.mapNotNull { songMap[it.songId] }.take(30)
            saveNavidromeMix(MixType.RECENTLY_LOVED, "Recently Loved", weekNavSongs, now)
        }
    }

    private suspend fun saveLocalMix(type: MixType, name: String, songs: List<Song>, now: Long) {
        val playlistId = upsertMixPlaylist(type, name, "LOCAL", now)
        playlistDao.clearPlaylist(playlistId)
        songs.forEachIndexed { i, song ->
            playlistDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId, song.id, i))
        }
    }

    private suspend fun saveNavidromeMix(type: MixType, name: String, songs: List<NavidromeSongEntity>, now: Long) {
        val playlistId = upsertMixPlaylist(type, name, "NAVIDROME", now)
        mixNavidromeSongDao.clearPlaylist(playlistId)
        songs.forEachIndexed { i, song ->
            mixNavidromeSongDao.addSong(MixNavidromeSongCrossRef(playlistId, song.navidromeId, i))
        }
    }

    private suspend fun upsertMixPlaylist(type: MixType, name: String, source: String, now: Long): Long {
        val existing = playlistDao.getMixByType(type.name)
        return if (existing != null) {
            playlistDao.updatePlaylist(
                existing.copy(name = name, dateModified = now, lastMixGenerated = now)
            )
            existing.id
        } else {
            playlistDao.insertPlaylist(
                PlaylistEntity(
                    name = name,
                    isReadOnly = true,
                    artworkUri = null,
                    isMix = true,
                    mixSource = source,
                    mixType = type.name,
                    lastMixGenerated = now,
                )
            )
        }
    }
}
