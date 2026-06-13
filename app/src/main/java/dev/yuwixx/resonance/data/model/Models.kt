// Domain model data classes and enums used throughout the app: Song, Album, Artist, Playlist,
// playback state types (RepeatMode, SleepTimer, SortOrder), and smart queue types.
package dev.yuwixx.resonance.data.model

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
data class Song(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val artists: List<String>,
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
    val artworkUri: Uri?,
    val listenCount: Int = 0,
    val lastListened: Long = 0L,
) {
    val displayArtist: String get() = artists.joinToString(", ")
}

@Immutable
data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val songCount: Int,
    val artworkUri: Uri?,
    val songs: List<Song> = emptyList(),
    val totalDuration: Long = songs.sumOf { it.duration },
)

@Immutable
data class Artist(
    val name: String,
    val songCount: Int,
    val albumCount: Int,
    val artworkUrl: String? = null,
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
)

@Immutable
data class Playlist(
    val id: Long,
    val name: String,
    val songs: List<Song> = emptyList(),
    val isReadOnly: Boolean = false,
    val artworkUri: Uri? = null,
    val dateCreated: Long = System.currentTimeMillis(),
    val dateModified: Long = System.currentTimeMillis(),
    val isMix: Boolean = false,
    val mixType: MixType? = null,
    val mixSource: String? = null,
    val lastMixGenerated: Long = 0L,
) {
    val songCount: Int get() = songs.size
    val totalDuration: Long get() = songs.sumOf { it.duration }
}

@Immutable
data class PlaybackQueue(
    val songs: List<Song>,
    val currentIndex: Int,
    val shuffleEnabled: Boolean,
    val repeatMode: RepeatMode,
    val originalOrder: List<Int>,
)

enum class RepeatMode { NONE, ONE, ALL }

@Immutable
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val wordTimings: List<WordTiming> = emptyList(),
)

@Immutable
data class WordTiming(
    val startMs: Long,
    val endMs: Long,
    val word: String,
)

data class SmartQueueResult(
    val songs: List<Song>,
    val reason: SmartQueueReason,
)

enum class SmartQueueReason {
    RELATED_BY_HISTORY,
    SIMILAR_RELEASE_DATE,
    SAME_GENRE,
    SAME_ERA,
    RANDOM_DISCOVERY,
    MOST_PLAYED,
    LOST_MEMORIES,
}

enum class MixType { TOP_ARTIST, TOP_GENRE, ERA, FAVORITES, RECENTLY_LOVED }

sealed class SleepTimer {
    data object Off : SleepTimer()
    data class Tracks(val tracksLeft: Int) : SleepTimer()
    data class Time(val minutes: Int, val startedAt: Long) : SleepTimer()
}

data class WaveformData(
    val amplitudes: FloatArray,
    val resolution: Int = 200,
) {
    override fun equals(other: Any?): Boolean =
        other is WaveformData && amplitudes.contentEquals(other.amplitudes)

    override fun hashCode(): Int = amplitudes.contentHashCode()
}

enum class SortOrder {
    TITLE_ASC, TITLE_DESC,
    ARTIST_ASC, ARTIST_DESC,
    ALBUM_ASC, ALBUM_DESC,
    DATE_ADDED_ASC, DATE_ADDED_DESC,
    DURATION_ASC, DURATION_DESC,
    TRACK_NUMBER,
    LISTEN_COUNT_DESC,
    NATURAL,
}
