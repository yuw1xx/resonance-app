// JSON shape for the Settings > Data > Export/Import Backup feature (see BackupManager).
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

// A generic DataStore preference snapshot — key/type/value rather than one field per setting —
// so every current and future preference round-trips through backup without needing to be
// hand-enumerated here. Both export and import are filtered against
// ResonancePreferences.BACKUP_EXCLUDED_KEYS (credentials, tokens, server URLs, device identity)
// regardless of what a backup file claims, so this being generic doesn't widen what's actually
// able to leave or come back into the app.
@JsonClass(generateAdapter = true)
data class BackupPreferenceEntry(
    val key: String,
    val type: String,
    val value: String? = null,
    val values: List<String>? = null,
)

// Listening history is keyed by song identity (path, falling back to title+artist+album), not
// the local songId — same reasoning as BackupSongEntry: the DB row id isn't stable across a
// rescan or a different device, but the song's identifying fields are.
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
