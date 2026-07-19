// Room database declaration listing all entities and exposing DAO accessors.
package dev.yuwixx.resonance.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.yuwixx.resonance.data.database.dao.*
import dev.yuwixx.resonance.data.database.entity.*

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongCrossRef::class,
        MixNavidromeSongCrossRef::class,
        HistoryEntity::class,
        ArtistArtworkEntity::class,
        AlbumArtworkEntity::class,
        LyricsEntity::class,
        QueueEntity::class,
        LikedSongEntity::class,
        NavidromeSongEntity::class,
        NavidromeAlbumEntity::class,
        PendingScrobbleEntity::class,
        SongDownloadEntity::class,
        PendingStarActionEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class ResonanceDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun mixNavidromeSongDao(): MixNavidromeSongDao
    abstract fun historyDao(): HistoryDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun artworkDao(): ArtworkDao
    abstract fun queueDao(): QueueDao
    abstract fun likedSongsDao(): LikedSongsDao
    abstract fun navidromeSongDao(): NavidromeSongDao
    abstract fun navidromeAlbumDao(): NavidromeAlbumDao
    abstract fun pendingScrobbleDao(): PendingScrobbleDao
    abstract fun songDownloadDao(): SongDownloadDao
    abstract fun pendingStarActionDao(): PendingStarActionDao
}
