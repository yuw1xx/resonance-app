package dev.yuwixx.resonance.data.database.dao

import androidx.room.*
import dev.yuwixx.resonance.data.database.entity.*
import kotlinx.coroutines.flow.Flow

data class SongPlayCount(val songId: Long, val playCount: Int)
data class HourPlayCount(val hour: Int, val playCount: Int)
data class DailyTotal(val day: String, val totalMs: Long, val playCount: Int)
data class DayOfWeekPlayCount(val dayOfWeek: Int, val playCount: Int)

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE folder = :folder")
    fun getSongsByFolder(folder: String): Flow<List<SongEntity>>

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

    /** Just what a MediaStore sync needs to diff against, plus the per-song state it must keep. */
    @Query("SELECT id, dateModified, size, path, listenCount, lastListened FROM songs")
    suspend fun getSyncKeys(): List<SongSyncKey>

    @Query("UPDATE songs SET replayGainTrack = :track, replayGainAlbum = :album WHERE id = :id")
    suspend fun updateReplayGain(id: Long, track: Float?, album: Float?)

    @Query("SELECT id FROM songs")
    suspend fun getAllSongIds(): List<Long>

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteSongsByIds(ids: List<Long>)

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

    // IGNORE, not REPLACE: re-adding a song that's already in the playlist must leave it where
    // it is instead of moving it to the end (and leaving a hole at its old position).
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongRefsIgnoringDuplicates(refs: List<PlaylistSongCrossRef>): List<Long>

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    // Every playlist's refs in one query, so the playlists screen is built from three flows
    // (playlists, refs, songs) instead of one query per playlist per song.
    @Query("SELECT * FROM playlist_songs ORDER BY playlistId ASC, position ASC")
    fun observeAllSongRefs(): Flow<List<PlaylistSongCrossRef>>

    @Transaction
    suspend fun deletePlaylistAndSongs(playlist: PlaylistEntity) {
        clearPlaylist(playlist.id)
        deletePlaylist(playlist)
    }

    // Rewrites positions as 0..n-1 in their current order, closing gaps left by removals.
    @Transaction
    suspend fun compactPositions(playlistId: Long) {
        getPlaylistSongRefs(playlistId).forEachIndexed { index, ref ->
            if (ref.position != index) updateSongPosition(playlistId, ref.songId, index)
        }
    }

    @Transaction
    suspend fun replaceSongs(playlistId: Long, songIds: List<Long>) {
        clearPlaylist(playlistId)
        insertSongRefsIgnoringDuplicates(
            songIds.distinct().mapIndexed { i, id -> PlaylistSongCrossRef(playlistId, id, i) }
        )
    }

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: Long)

    @Query("UPDATE playlist_songs SET position = :position WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun updateSongPosition(playlistId: Long, songId: Long, position: Int)

    @Query("SELECT * FROM playlists ORDER BY isMix ASC, dateModified DESC")
    suspend fun getAllPlaylistsList(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE navidromePlaylistId = :navidromePlaylistId LIMIT 1")
    suspend fun getPlaylistByNavidromeId(navidromePlaylistId: String): PlaylistEntity?

    // Backup restore matches playlists by name. Mixes are never in a backup, so they're excluded.
    @Query("SELECT * FROM playlists WHERE name = :name AND isMix = 0 LIMIT 1")
    suspend fun getPlaylistByName(name: String): PlaylistEntity?
}

@Dao
interface SmartPlaylistDao {
    @Query("SELECT * FROM smart_playlists")
    fun observeAll(): Flow<List<SmartPlaylistEntity>>

    @Query("SELECT * FROM smart_playlists WHERE playlistId = :playlistId")
    suspend fun get(playlistId: Long): SmartPlaylistEntity?

    @Upsert
    suspend fun upsert(entity: SmartPlaylistEntity)

    @Query("DELETE FROM smart_playlists WHERE playlistId = :playlistId")
    suspend fun delete(playlistId: Long)

    @Query("SELECT * FROM smart_playlists")
    suspend fun getAll(): List<SmartPlaylistEntity>
}

@Dao
interface PlaylistSourceDao {
    @Query("SELECT * FROM playlist_sources")
    suspend fun getAll(): List<PlaylistSourceEntity>

    @Query("SELECT * FROM playlist_sources WHERE documentUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): PlaylistSourceEntity?

    @Query("SELECT * FROM playlist_sources WHERE playlistId = :playlistId")
    suspend fun getByPlaylist(playlistId: Long): PlaylistSourceEntity?

    @Upsert
    suspend fun upsert(entity: PlaylistSourceEntity)

    @Query("DELETE FROM playlist_sources WHERE playlistId = :playlistId")
    suspend fun delete(playlistId: Long)

    @Query("DELETE FROM playlist_sources")
    suspend fun deleteAll()
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

    @Query("SELECT * FROM history ORDER BY listenedAt DESC LIMIT 200")
    fun getRecentHistory(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history")
    suspend fun getAllHistory(): List<HistoryEntity>

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

    @Query("SELECT COUNT(*) FROM history WHERE songId = :songId")
    suspend fun getPlayCountForSong(songId: Long): Int

    @Query("SELECT COALESCE(SUM(durationListened), 0) FROM history WHERE songId = :songId")
    suspend fun getTotalListenTimeForSong(songId: Long): Long

    @Query("""
        SELECT date(listenedAt / 1000, 'unixepoch', 'localtime') AS day,
               COALESCE(SUM(durationListened), 0) AS totalMs,
               COUNT(*) AS playCount
        FROM history
        WHERE listenedAt BETWEEN :from AND :to
        GROUP BY day
        ORDER BY day ASC
    """)
    suspend fun getDailyTotals(from: Long, to: Long): List<DailyTotal>

    @Query("""
        SELECT CAST(strftime('%w', listenedAt / 1000, 'unixepoch', 'localtime') AS INTEGER) AS dayOfWeek,
               COUNT(*) AS playCount
        FROM history
        WHERE listenedAt BETWEEN :from AND :to
        GROUP BY dayOfWeek
        ORDER BY dayOfWeek ASC
    """)
    suspend fun getPlaysByDayOfWeek(from: Long, to: Long): List<DayOfWeekPlayCount>
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

    @Query("SELECT songId FROM liked_songs")
    suspend fun getAllLikedIds(): List<Long>
}

@Dao
interface ArtworkDao {
    @Query("SELECT * FROM artist_artwork WHERE artistName = :artistName")
    suspend fun getArtistArtwork(artistName: String): ArtistArtworkEntity?

    @Upsert
    suspend fun upsertArtistArtwork(artwork: ArtistArtworkEntity)

    @Query("SELECT * FROM album_artwork WHERE albumId = :albumId")
    suspend fun getAlbumArtwork(albumId: Long): AlbumArtworkEntity?

    @Upsert
    suspend fun upsertAlbumArtwork(artwork: AlbumArtworkEntity)
}

@Dao
interface ArtistInfoDao {
    @Query("SELECT * FROM artist_info WHERE artistName = :artistName")
    suspend fun getArtistInfo(artistName: String): ArtistInfoEntity?

    @Upsert
    suspend fun upsert(info: ArtistInfoEntity)
}

@Dao
interface PendingScrobbleDao {
    @Insert
    suspend fun insert(entity: PendingScrobbleEntity): Long

    @Query("SELECT * FROM pending_scrobbles WHERE service = :service")
    suspend fun getAllForService(service: String): List<PendingScrobbleEntity>

    @Query("DELETE FROM pending_scrobbles WHERE service = :service")
    suspend fun deleteAllForService(service: String)
}

@Dao
interface PendingStarActionDao {
    @Insert
    suspend fun insert(entity: PendingStarActionEntity): Long

    @Query("SELECT * FROM pending_star_actions")
    suspend fun getAll(): List<PendingStarActionEntity>

    @Query("SELECT DISTINCT songId FROM pending_star_actions")
    suspend fun getPendingSongIds(): List<Long>

    /** Removes the actions for a song up to and including [upTo], leaving newer ones queued. */
    @Query("DELETE FROM pending_star_actions WHERE songId = :songId AND timestamp <= :upTo")
    suspend fun deleteForSongUpTo(songId: Long, upTo: Long)

    @Query("DELETE FROM pending_star_actions")
    suspend fun deleteAll()
}

@Dao
interface SongDownloadDao {
    @Query("SELECT * FROM song_downloads")
    fun getAll(): Flow<List<SongDownloadEntity>>

    @Query("SELECT * FROM song_downloads WHERE songId = :songId")
    suspend fun getBySongId(songId: Long): SongDownloadEntity?

    @Upsert
    suspend fun upsert(entity: SongDownloadEntity)

    @Query("UPDATE song_downloads SET state = :state, errorMessage = :error WHERE songId = :songId")
    suspend fun updateState(songId: Long, state: String, error: String? = null)

    @Query("DELETE FROM song_downloads WHERE songId = :songId")
    suspend fun delete(songId: Long)

    @Query("DELETE FROM song_downloads")
    suspend fun deleteAll()

}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queues WHERE id = 0")
    suspend fun getCurrentQueue(): QueueEntity?

    @Upsert
    suspend fun saveQueue(queue: QueueEntity)

    @Query("DELETE FROM queues")
    suspend fun clearQueue()
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

    @Query("SELECT * FROM navidrome_songs WHERE numericId IN (:ids)")
    suspend fun getSongsByNumericIds(ids: List<Long>): List<NavidromeSongEntity>

    @Query("SELECT COUNT(*) FROM navidrome_songs")
    suspend fun count(): Int

        @Upsert
    suspend fun upsertAll(songs: List<NavidromeSongEntity>)

    @Query("DELETE FROM navidrome_songs")
    suspend fun deleteAll()

    @Query("DELETE FROM navidrome_songs WHERE navidromeId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
interface NavidromeAlbumDao {
    @Query("SELECT * FROM navidrome_albums ORDER BY artist ASC, year ASC, name ASC")
    fun getAllAlbums(): Flow<List<NavidromeAlbumEntity>>

    @Query("SELECT COUNT(*) FROM navidrome_albums")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(albums: List<NavidromeAlbumEntity>)

    @Query("DELETE FROM navidrome_albums")
    suspend fun deleteAll()

    @Query("SELECT * FROM navidrome_albums")
    suspend fun getAllAlbumsList(): List<NavidromeAlbumEntity>

    @Query("DELETE FROM navidrome_albums WHERE navidromeId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

data class SongSyncKey(
    val id: Long,
    val dateModified: Long,
    val size: Long,
    val path: String,
    val listenCount: Int,
    val lastListened: Long,
)
