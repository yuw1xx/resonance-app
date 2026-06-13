// Room DAO interfaces for every table: songs, playlists, history, lyrics, liked songs,
// artist artwork cache, playback queue, and Navidrome remote tracks/albums.
package dev.yuwixx.resonance.data.database.dao

import androidx.room.*
import dev.yuwixx.resonance.data.database.entity.*
import kotlinx.coroutines.flow.Flow

data class SongPlayCount(val songId: Long, val playCount: Int)
data class HourPlayCount(val hour: Int, val playCount: Int)

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE folder = :folder")
    fun getSongsByFolder(folder: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE album = :album ORDER BY trackNumber ASC")
    fun getSongsByAlbum(album: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE artist LIKE '%' || :artist || '%'")
    fun getSongsByArtist(artist: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE genre = :genre")
    fun getSongsByGenre(genre: String): Flow<List<SongEntity>>

    @Query("""
        SELECT * FROM songs 
        WHERE title LIKE '%' || :query || '%'
           OR artist LIKE '%' || :query || '%'
           OR album LIKE '%' || :query || '%'
           OR genre LIKE '%' || :query || '%'
        ORDER BY listenCount DESC
    """)
    fun searchSongs(query: String): Flow<List<SongEntity>>

    @Upsert
    suspend fun upsertSongs(songs: List<SongEntity>)

    @Query("SELECT id FROM songs")
    suspend fun getAllSongIds(): List<Long>

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteSongsByIds(ids: List<Long>)

    @Query("DELETE FROM songs")
    suspend fun deleteAllSongs()

    @Query("UPDATE songs SET listenCount = listenCount + 1, lastListened = :time WHERE id = :id")
    suspend fun incrementListenCount(id: Long, time: Long)

    @Query("SELECT * FROM songs ORDER BY listenCount DESC LIMIT :limit")
    suspend fun getMostPlayed(limit: Int = 50): List<SongEntity>

    @Query("SELECT id AS songId, listenCount AS playCount FROM songs WHERE id IN (:ids)")
    suspend fun getListenCountsForIds(ids: List<Long>): List<SongPlayCount>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getSongsByIds(ids: List<Long>): List<SongEntity>

    @Query("SELECT DISTINCT folder FROM songs ORDER BY folder ASC")
    fun getAllFolders(): Flow<List<String>>

    @Query("SELECT DISTINCT genre FROM songs WHERE genre != '' ORDER BY genre ASC")
    fun getAllGenres(): Flow<List<String>>

    @Query("SELECT * FROM songs WHERE path = :path LIMIT 1")
    suspend fun getSongByPath(path: String): SongEntity?

    @Query("SELECT * FROM songs ORDER BY title ASC")
    suspend fun getAllSongsList(): List<SongEntity>
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY isMix ASC, dateModified DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylistById(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlists WHERE isMix = 1 ORDER BY dateModified DESC")
    suspend fun getMixPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE mixType = :type AND isMix = 1 LIMIT 1")
    suspend fun getMixByType(type: String): PlaylistEntity?

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getPlaylistSongRefs(playlistId: Long): List<PlaylistSongCrossRef>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongToPlaylist(ref: PlaylistSongCrossRef)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: Long)

    @Query("UPDATE playlist_songs SET position = :position WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun updateSongPosition(playlistId: Long, songId: Long, position: Int)

    @Query("SELECT * FROM playlists ORDER BY isMix ASC, dateModified DESC")
    suspend fun getAllPlaylistsList(): List<PlaylistEntity>

    @Query("""
        SELECT s.path FROM songs s
        INNER JOIN playlist_songs ps ON s.id = ps.songId
        WHERE ps.playlistId = :playlistId
        ORDER BY ps.position ASC
    """)
    suspend fun getSongPathsForPlaylist(playlistId: Long): List<String>
}

@Dao
interface MixNavidromeSongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSong(ref: MixNavidromeSongCrossRef)

    @Query("DELETE FROM mix_navidrome_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: Long)

    @Query("SELECT * FROM mix_navidrome_songs WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getSongRefs(playlistId: Long): List<MixNavidromeSongCrossRef>
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insertHistory(history: HistoryEntity)

    @Query("""
        SELECT * FROM history 
        WHERE listenedAt BETWEEN :from AND :to
        ORDER BY listenedAt DESC
    """)
    fun getHistoryInRange(from: Long, to: Long): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history ORDER BY listenedAt DESC LIMIT 200")
    fun getRecentHistory(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history")
    suspend fun getAllHistory(): List<HistoryEntity>

    @Query("DELETE FROM history WHERE listenedAt < :before")
    suspend fun pruneHistory(before: Long)

    @Query("DELETE FROM history")
    suspend fun clearAllHistory()

    @Query("SELECT COUNT(DISTINCT songId) FROM history WHERE listenedAt BETWEEN :from AND :to")
    suspend fun getUniqueTrackCount(from: Long, to: Long): Int

    @Query("SELECT DISTINCT date(listenedAt / 1000, 'unixepoch', 'localtime') FROM history ORDER BY 1 DESC")
    suspend fun getDistinctListenDates(): List<String>

    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY listenedAt DESC LIMIT :limit)")
    suspend fun trimHistory(limit: Int)

    @Query("SELECT COALESCE(SUM(durationListened), 0) FROM history WHERE listenedAt BETWEEN :from AND :to")
    suspend fun getTotalListenTime(from: Long, to: Long): Long

    @Query("SELECT COUNT(*) FROM history WHERE listenedAt BETWEEN :from AND :to")
    suspend fun getPlayCount(from: Long, to: Long): Int

    @Query("""
        SELECT songId, COUNT(*) AS playCount FROM history
        WHERE listenedAt BETWEEN :from AND :to
        GROUP BY songId
        ORDER BY playCount DESC
        LIMIT :limit
    """)
    suspend fun getTopSongIds(from: Long, to: Long, limit: Int = 5): List<SongPlayCount>

    @Query("""
        SELECT CAST(strftime('%H', listenedAt / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,
               COUNT(*) AS playCount
        FROM history
        WHERE listenedAt BETWEEN :from AND :to
        GROUP BY hour
        ORDER BY hour ASC
    """)
    suspend fun getPlaysByHour(from: Long, to: Long): List<HourPlayCount>
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE songId = :songId")
    suspend fun getLyrics(songId: Long): LyricsEntity?

    @Upsert
    suspend fun upsertLyrics(lyrics: LyricsEntity)
}

@Dao
interface LikedSongsDao {
    @Query("SELECT songId FROM liked_songs ORDER BY likedAt DESC")
    fun getLikedSongIds(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun likeSong(entity: LikedSongEntity)

    @Query("DELETE FROM liked_songs WHERE songId = :songId")
    suspend fun unlikeSong(songId: Long)

    @Query("SELECT COUNT(*) FROM liked_songs WHERE songId = :songId")
    suspend fun isLiked(songId: Long): Int

    @Query("""
        SELECT s.path FROM songs s
        INNER JOIN liked_songs ls ON s.id = ls.songId
        ORDER BY ls.likedAt DESC
    """)
    suspend fun getLikedSongPaths(): List<String>
}

@Dao
interface ArtworkDao {
    @Query("SELECT * FROM artist_artwork WHERE artistName = :artistName")
    suspend fun getArtistArtwork(artistName: String): ArtistArtworkEntity?

    @Upsert
    suspend fun upsertArtistArtwork(artwork: ArtistArtworkEntity)
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queues WHERE id = 0")
    suspend fun getCurrentQueue(): QueueEntity?

    @Upsert
    suspend fun saveQueue(queue: QueueEntity)
}
@Dao
interface NavidromeSongDao {
    @Query("SELECT * FROM navidrome_songs ORDER BY artist ASC, album ASC, discNumber ASC, trackNumber ASC")
    fun getAllSongs(): Flow<List<NavidromeSongEntity>>

    @Query("SELECT * FROM navidrome_songs ORDER BY artist ASC, album ASC, discNumber ASC, trackNumber ASC")
    suspend fun getAllSongsList(): List<NavidromeSongEntity>

    @Query("SELECT * FROM navidrome_songs WHERE navidromeId = :id")
    suspend fun getSongById(id: String): NavidromeSongEntity?

    @Query("SELECT * FROM navidrome_songs WHERE numericId = :id")
    suspend fun getSongByNumericId(id: Long): NavidromeSongEntity?

    @Query("SELECT COUNT(*) FROM navidrome_songs")
    suspend fun count(): Int

        @Upsert
    suspend fun upsertAll(songs: List<NavidromeSongEntity>)

    @Query("DELETE FROM navidrome_songs")
    suspend fun deleteAll()
}

@Dao
interface NavidromeAlbumDao {
    @Query("SELECT * FROM navidrome_albums ORDER BY artist ASC, year ASC, name ASC")
    fun getAllAlbums(): Flow<List<NavidromeAlbumEntity>>

    @Query("SELECT * FROM navidrome_albums WHERE navidromeId = :id")
    suspend fun getAlbumById(id: String): NavidromeAlbumEntity?

    @Query("SELECT COUNT(*) FROM navidrome_albums")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(albums: List<NavidromeAlbumEntity>)

    @Query("DELETE FROM navidrome_albums")
    suspend fun deleteAll()
}
