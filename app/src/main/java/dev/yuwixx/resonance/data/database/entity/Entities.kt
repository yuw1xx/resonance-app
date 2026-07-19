// Room @Entity classes mapping to database tables: songs, playlists, history,
// artist artwork cache, lyrics, playback queue, liked songs, and Navidrome remote tracks/albums.
package dev.yuwixx.resonance.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val albumId: Long,
    val genre: String,
    val duration: Long,
    val size: Long,
    val bitrate: Int,
    val sampleRate: Int,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
    val dateAdded: Long,
    val dateModified: Long,
    val path: String,
    val folder: String,
    val mimeType: String,
    val replayGainTrack: Float?,
    val replayGainAlbum: Float?,
    val listenCount: Int = 0,
    val lastListened: Long = 0L,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isReadOnly: Boolean = false,
    val artworkUri: String?,
    val dateCreated: Long = System.currentTimeMillis(),
    val dateModified: Long = System.currentTimeMillis(),
    val isMix: Boolean = false,
    val mixSource: String? = null,
    val mixType: String? = null,
    val lastMixGenerated: Long = 0L,
    // Set once this playlist has been pushed to (or pulled from) Navidrome — only playlists
    // created in Resonance sync both ways; a playlist that already exists only on the server
    // stays visible through the existing read-only browse view instead of being auto-imported.
    val navidromePlaylistId: String? = null,
)

@Entity(tableName = "mix_navidrome_songs", primaryKeys = ["playlistId", "navidromeSongId"])
data class MixNavidromeSongCrossRef(
    val playlistId: Long,
    val navidromeSongId: String,
    val position: Int,
)

@Entity(tableName = "playlist_songs", primaryKeys = ["playlistId", "songId"])
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val songId: Long,
    val position: Int,
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val listenedAt: Long,
    val durationListened: Long,
    val percentageListened: Float,
)

@Entity(tableName = "artist_artwork")
data class ArtistArtworkEntity(
    @PrimaryKey val artistName: String,
    val artworkUrl: String?,
    val fetchedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "album_artwork")
data class AlbumArtworkEntity(
    @PrimaryKey val albumId: Long,
    val artworkUrl: String?,
    val fetchedAt: Long = System.currentTimeMillis(),
)

// Durable backup for the in-memory pending-scrobble queues in LastFmRepository/MalojaRepository
// — a killed process shouldn't silently lose scrobbles queued while offline.
@Entity(tableName = "pending_scrobbles")
data class PendingScrobbleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val service: String,       // "LASTFM" or "MALOJA"
    val artists: String,       // comma-joined (Maloja supports multiple artists per scrobble)
    val title: String,
    val album: String?,
    val durationSec: Int?,
    val trackNumber: Int?,
    val timestamp: Long,
)

// Durable retry queue for star/unstar actions not yet pushed to Navidrome (offline, or the
// push failed) — mirrors PendingScrobbleEntity's pattern. Its mere presence for a songId means
// "not yet synced"; a pull from the server skips any song with a pending row here, so a local
// action always wins over a stale remote read instead of needing timestamp-based merging.
@Entity(tableName = "pending_star_actions")
data class PendingStarActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val navidromeId: String,
    val action: String, // STAR | UNSTAR
    val timestamp: Long,
)

object StarAction {
    const val STAR = "STAR"
    const val UNSTAR = "UNSTAR"
}

// Offline-download tracking for Navidrome songs. Kept as its own table (not extra columns on
// NavidromeSongEntity) because NavidromeRepository.syncLibrary() wipes and rebuilds
// navidrome_songs wholesale on every sync — download state needs an independent lifecycle.
@Entity(tableName = "song_downloads")
data class SongDownloadEntity(
    @PrimaryKey val songId: Long,
    val navidromeId: String,
    val localFilePath: String,
    val state: String,       // QUEUED | DOWNLOADING | DOWNLOADED | FAILED
    val fileSizeBytes: Long = 0L,
    val requestedAt: Long,
    val downloadedAt: Long = 0L,
    val errorMessage: String? = null,
)

object DownloadState {
    const val QUEUED = "QUEUED"
    const val DOWNLOADING = "DOWNLOADING"
    const val DOWNLOADED = "DOWNLOADED"
    const val FAILED = "FAILED"
}

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val songId: Long,
    val synced: String?,
    val plain: String?,
    val source: String,
    val fetchedAt: Long = System.currentTimeMillis(),
)

// id is always 0 — we keep exactly one row and overwrite it on every queue change.
@Entity(tableName = "queues")
data class QueueEntity(
    @PrimaryKey val id: Long = 0L,
    val songIds: String,
    val currentIndex: Int,
    val shuffleEnabled: Boolean,
    val repeatMode: String,
    val originalOrder: String,
    val savedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "liked_songs")
data class LikedSongEntity(
    @PrimaryKey val songId: Long,
    val likedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "navidrome_songs")
data class NavidromeSongEntity(
    @PrimaryKey val navidromeId: String,
    val numericId: Long,
    val streamUrl: String,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val albumId: String,
    val coverArtUrl: String?,
    val genre: String,
    val durationMs: Long,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
    val path: String,
    val mimeType: String,
    val bitrate: Int,
    val size: Long,
    val playCount: Long,
    val cachedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "navidrome_albums")
data class NavidromeAlbumEntity(
    @PrimaryKey val navidromeId: String,
    val numericId: Long,
    val name: String,
    val artist: String,
    val artistId: String?,
    val coverArtUrl: String?,
    val songCount: Int,
    val durationSec: Int,
    val year: Int,
    val cachedAt: Long = System.currentTimeMillis(),
)
