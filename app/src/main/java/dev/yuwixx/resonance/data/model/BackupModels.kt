package dev.yuwixx.resonance.data.model

import com.squareup.moshi.JsonClass
import dev.yuwixx.resonance.data.playlist.SmartRules

@JsonClass(generateAdapter = true)
data class BackupSongEntry(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
)

@JsonClass(generateAdapter = true)
data class BackupPlaylistEntry(
    val name: String,
    val songs: List<BackupSongEntry>,
    /** Set for smart playlists: their rules travel instead of a song list. */
    val smartRules: SmartRules? = null,
)

// Generic key/type/value so every preference is backed up without listing them here. Both
// directions skip ResonancePreferences.BACKUP_EXCLUDED_KEYS.
@JsonClass(generateAdapter = true)
data class BackupPreferenceEntry(
    val key: String,
    val type: String,
    val value: String? = null,
    val values: List<String>? = null,
)

// Keyed by path, or title, artist and album, because song IDs differ between devices.
@JsonClass(generateAdapter = true)
data class BackupHistoryEntry(
    val song: BackupSongEntry,
    val listenedAt: Long,
    val durationListened: Long,
    val percentageListened: Float,
)

@JsonClass(generateAdapter = true)
data class BackupFile(
    val version: Int = 3,
    val exportedAt: Long,
    val likedSongs: List<BackupSongEntry> = emptyList(),
    val playlists: List<BackupPlaylistEntry> = emptyList(),
    val settings: List<BackupPreferenceEntry> = emptyList(),
    val history: List<BackupHistoryEntry> = emptyList(),
)
