package dev.yuwixx.resonance.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.database.ResonanceDatabase
import dev.yuwixx.resonance.data.database.dao.NavidromeAlbumDao
import dev.yuwixx.resonance.data.database.dao.NavidromeSongDao
import dev.yuwixx.resonance.data.database.entity.NavidromeAlbumEntity
import dev.yuwixx.resonance.data.database.entity.NavidromeSongEntity
import dev.yuwixx.resonance.data.model.*
import dev.yuwixx.resonance.data.network.*
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.data.util.md5Hex
import dev.yuwixx.resonance.data.util.sha256Hex
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class NavidromeConnectionState {
    data object Idle : NavidromeConnectionState()
    data object Connecting : NavidromeConnectionState()
    data object Connected : NavidromeConnectionState()
    data class Error(val message: String) : NavidromeConnectionState()
}

sealed class NavidromeSyncState {
    data object Idle : NavidromeSyncState()
    data class Syncing(val albumsDone: Int, val songsDone: Int, val estimatedTotal: Int = 0) : NavidromeSyncState()
    data class Done(val songCount: Int, val albumCount: Int) : NavidromeSyncState()
    data class Error(val message: String) : NavidromeSyncState()
}

@Singleton
class NavidromeRepository @Inject constructor(
    private val navidromeApiProvider: NavidromeApiProvider,
    private val prefs: ResonancePreferences,
    private val navidromeSongDao: NavidromeSongDao,
    private val navidromeAlbumDao: NavidromeAlbumDao,
    private val database: ResonanceDatabase,
    private val moshi: Moshi,
    @ApplicationContext private val context: Context,
) {
    private val syncMutex = Mutex()
    private val artistsFile get() = File(context.filesDir, "navidrome_artists.json")
    private val artistsAdapter by lazy {
        moshi.adapter<List<SubsonicArtistSummary>>(
            Types.newParameterizedType(List::class.java, SubsonicArtistSummary::class.java)
        )
    }

    private val _connectionState = MutableStateFlow<NavidromeConnectionState>(NavidromeConnectionState.Idle)
    val connectionState: StateFlow<NavidromeConnectionState> = _connectionState.asStateFlow()

    private val _syncState = MutableStateFlow<NavidromeSyncState>(NavidromeSyncState.Idle)
    val syncState: StateFlow<NavidromeSyncState> = _syncState.asStateFlow()

    fun resetSyncState() { _syncState.value = NavidromeSyncState.Idle }

    val cachedSongs: Flow<List<Song>> = navidromeSongDao.getAllSongs()
        .map { entities -> entities.map { it.toDomain() } }
        .flowOn(Dispatchers.Default)

        val cachedAlbums: Flow<List<Album>> = combine(
        navidromeSongDao.getAllSongs(),
        navidromeAlbumDao.getAllAlbums(),
    ) { songEntities, albumEntities ->
        val songsByAlbumId = songEntities.groupBy { it.albumId }
        albumEntities.map { album ->
            val songs = (songsByAlbumId[album.navidromeId] ?: emptyList())
                .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }))
                .map { it.toDomain() }
            album.toDomain(songs)
        }
    }.flowOn(Dispatchers.Default)

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        navidromeSongDao.deleteAll()
        navidromeAlbumDao.deleteAll()
        artistsFile.delete()
        prefs.setNavidromeSyncStamp(null)
    }

    suspend fun testConnection(
        serverUrl: String,
        username: String,
        password: String,
    ): NavidromeConnectionState = withContext(Dispatchers.IO) {
        _connectionState.value = NavidromeConnectionState.Connecting
        try {
            val api = navidromeApiProvider.buildApi(serverUrl, username, password)
            val body = api.ping().response
            if (body.status == "ok") {
                NavidromeConnectionState.Connected.also { _connectionState.value = it }
            } else {
                val msg = body.error?.message ?: "Server error: ${body.status}"
                NavidromeConnectionState.Error(msg).also { _connectionState.value = it }
            }
        } catch (e: Exception) {
            NavidromeConnectionState.Error(e.message ?: "Connection failed")
                .also { _connectionState.value = it }
        }
    }

    fun resetConnectionState() { _connectionState.value = NavidromeConnectionState.Idle }

    /**
     * Brings the local Navidrome cache up to date.
     *
     * Cheap by default: one getScanStatus call, and nothing else when the server's library
     * hasn't been rescanned since the last sync. When it has (or [force]), songs are paged in
     * with search3 (~1 request per 500 songs instead of one per album), albums with
     * getAlbumList2, and only rows that actually changed are written — unchanged rows aren't
     * touched, so the UI doesn't reload. One sync at a time.
     */
    suspend fun syncLibrary(force: Boolean = false): Boolean = syncMutex.withLock {
        withContext(Dispatchers.IO) { syncLocked(force) }
    }

    private suspend fun syncLocked(force: Boolean): Boolean {
        val api = navidromeApiProvider.currentApi() ?: return false
        val creds = navidromeApiProvider.currentCredentials() ?: return false

        try {
            val status = runCatching { api.getScanStatus().ok.scanStatus }.getOrNull()
            val stamp = status?.let { "${creds.serverUrl}|${creds.username}|${it.lastScan}|${it.count}" }
            val hasCache = navidromeSongDao.count() > 0
            if (!force && hasCache) {
                if (stamp != null && stamp == prefs.navidromeSyncStamp.first()) return true
                // Mid-scan the library is in flux; the next launch will pick up the result.
                if (status?.scanning == true) return true
            }

            val estimatedTotal = status?.count?.toInt() ?: navidromeSongDao.count()
            _syncState.value = NavidromeSyncState.Syncing(0, 0, estimatedTotal)

            // Albums: list pages only (no per-album detail requests).
            val albums = mutableListOf<SubsonicAlbum>()
            var offset = 0
            while (true) {
                val page = api.getAlbumList(size = 500, offset = offset).ok.albumList2?.album.orEmpty()
                albums += page
                _syncState.value = NavidromeSyncState.Syncing(albums.size, 0, estimatedTotal)
                if (page.size < 500) break
                offset += 500
            }

            // Songs: search3 with an empty query pages through the whole library.
            val songs = mutableListOf<SubsonicSong>()
            offset = 0
            while (true) {
                val page = api.search(query = "", songCount = 500, albumCount = 0, artistCount = 0, songOffset = offset)
                    .response.searchResult3?.song.orEmpty()
                songs += page
                _syncState.value = NavidromeSyncState.Syncing(albums.size, songs.size, estimatedTotal)
                if (page.size < 500) break
                offset += 500
            }
            // Servers that don't support the empty-query listing: fall back to album details.
            if (songs.isEmpty() && albums.isNotEmpty()) {
                albums.forEachIndexed { index, summary ->
                    val detail = runCatching { api.getAlbum(summary.id).ok.album }.getOrNull()
                    songs += detail?.song.orEmpty()
                    if (index % 20 == 0) _syncState.value = NavidromeSyncState.Syncing(index, songs.size, estimatedTotal)
                }
            }

            val existingSongs = navidromeSongDao.getAllSongsList().associateBy { it.navidromeId }
            val newSongs = songs.distinctBy { it.id }.map { song ->
                val entity = song.toEntity(creds)
                // Keep the original "added" time when the server's date is missing or unreadable.
                val old = existingSongs[entity.navidromeId]
                if (parseIsoMillis(song.created) == null && old != null) entity.copy(cachedAt = old.cachedAt) else entity
            }
            val changedSongs = newSongs.filter { existingSongs[it.navidromeId] != it }
            val removedSongs = existingSongs.keys - newSongs.mapTo(HashSet()) { it.navidromeId }

            val songCountByAlbum = newSongs.groupingBy { it.albumId }.eachCount()
            val existingAlbums = navidromeAlbumDao.getAllAlbumsList().associateBy { it.navidromeId }
            val newAlbums = albums.distinctBy { it.id }.map { album ->
                val entity = album.toEntity(creds)
                val old = existingAlbums[entity.navidromeId]
                entity.copy(
                    songCount = album.songCount ?: songCountByAlbum[album.id] ?: 0,
                    cachedAt = old?.cachedAt ?: entity.cachedAt,
                )
            }
            val changedAlbums = newAlbums.filter { existingAlbums[it.navidromeId] != it }
            val removedAlbums = existingAlbums.keys - newAlbums.mapTo(HashSet()) { it.navidromeId }

            if (changedSongs.isNotEmpty() || removedSongs.isNotEmpty() ||
                changedAlbums.isNotEmpty() || removedAlbums.isNotEmpty()
            ) {
                database.withTransaction {
                    removedSongs.chunked(900).forEach { navidromeSongDao.deleteByIds(it) }
                    removedAlbums.chunked(900).forEach { navidromeAlbumDao.deleteByIds(it) }
                    changedSongs.chunked(500).forEach { navidromeSongDao.upsertAll(it) }
                    changedAlbums.chunked(500).forEach { navidromeAlbumDao.upsertAll(it) }
                }
            }

            refreshArtistCache()
            prefs.setNavidromeSyncStamp(stamp)
            _syncState.value = NavidromeSyncState.Done(newSongs.size, newAlbums.size)
            Log.i("NavidromeRepository",
                "Sync complete — ${newSongs.size} songs (${changedSongs.size} changed, ${removedSongs.size} removed), " +
                    "${newAlbums.size} albums")
            return true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _syncState.value = NavidromeSyncState.Error(e.message ?: "Sync failed")
            Log.e("NavidromeRepository", "syncLibrary failed: ${e.message}", e)
            return false
        }
    }

    /** Artists from the on-disk cache: instant, and available offline. */
    suspend fun cachedArtists(): List<Artist> = withContext(Dispatchers.IO) {
        val creds = navidromeApiProvider.currentCredentials() ?: return@withContext emptyList()
        val file = artistsFile
        if (!file.exists()) return@withContext emptyList()
        runCatching { artistsAdapter.fromJson(file.readText()) }.getOrNull().orEmpty().map { it.toDomain(creds) }
    }

    private suspend fun refreshArtistCache() {
        val api = navidromeApiProvider.currentApi() ?: return
        val artists = runCatching { api.getArtists().ok.artists?.index?.flatMap { it.artist } }.getOrNull() ?: return
        runCatching { artistsFile.writeText(artistsAdapter.toJson(artists)) }
    }

    suspend fun getAllPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        val api = navidromeApiProvider.currentApi() ?: return@withContext emptyList()
        val creds = navidromeApiProvider.currentCredentials() ?: return@withContext emptyList()
        try {
            api.getPlaylists().ok.playlists?.playlist?.map { summary ->
                val detail = runCatching { api.getPlaylist(summary.id).ok.playlist }.getOrNull()
                Playlist(
                    id = summary.id.toLongOrNull() ?: stableLongId(summary.id),
                    name = summary.name,
                    songs = detail?.entry?.map { it.toSongDomain(creds) } ?: emptyList(),
                    isReadOnly = false,
                    artworkUri = summary.coverArt?.let { Uri.parse(coverArtUrl(creds, it)) },
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e("NavidromeRepository", "getAllPlaylists failed: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun notifyNowPlaying(songId: String) {
        val api = navidromeApiProvider.currentApi() ?: return
        try {
            api.scrobble(id = songId, submission = false).ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("NavidromeRepository", "Now playing failed: ${e.message}")
        }
    }

    /** Updates the song's play count and "recently played" on the server. */
    suspend fun submitScrobble(songId: String, playedAtMs: Long) {
        val api = navidromeApiProvider.currentApi() ?: return
        try {
            api.scrobble(id = songId, timeMs = playedAtMs, submission = true).ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("NavidromeRepository", "Scrobble failed: ${e.message}")
        }
    }

    // Untranscoded original (rest/download), not rest/stream — the right choice for a
    // permanent offline copy, since stream.view may be transcoded per server config.
    suspend fun buildDownloadUrl(songId: String): String? {
        val creds = navidromeApiProvider.currentCredentials() ?: return null
        return buildDownloadUrl(creds.serverUrl, creds.username, creds.password, songId)
    }

}

// Token auth for URLs that get stored or cached (stream, download, cover art). The salt is
// stable per server + account, so the same song/cover always has the same URL: syncs don't
// rewrite every row with fresh salts and image/audio caches keep hitting across launches.
// (API calls still get a fresh salt per request from NavidromeAuthInterceptor.)
private fun authQuery(serverUrl: String, username: String, password: String): String {
    val salt = sha256Hex("resonance|${serverUrl.trimEnd('/')}|$username").take(12)
    val token = md5Hex("$password$salt")
    return "u=${Uri.encode(username)}&t=$token&s=$salt&v=1.16.1&c=Resonance&f=json"
}

private fun buildStreamUrl(serverUrl: String, username: String, password: String, songId: String): String =
    "${serverUrl.trimEnd('/')}/rest/stream?id=$songId&${authQuery(serverUrl, username, password)}"

private fun buildDownloadUrl(serverUrl: String, username: String, password: String, songId: String): String =
    "${serverUrl.trimEnd('/')}/rest/download?id=$songId&${authQuery(serverUrl, username, password)}"

private fun coverArtUrl(creds: NavidromeApiProvider.Credentials, id: String): String =
    "${creds.serverUrl.trimEnd('/')}/rest/getCoverArt?id=$id&${authQuery(creds.serverUrl, creds.username, creds.password)}"

// Subsonic/Navidrome IDs are opaque strings (usually UUIDs), but the shared Song/Album domain
// models use a Long id to stay compatible with local MediaStore songs, so non-numeric ids need
// mapping to a Long. String.hashCode() is only 32 bits — collisions become likely well within
// the size of a real library (~1% chance already at ~10k items, per the birthday bound), which
// would silently merge two different songs' identity (queue persistence, playback, DB lookups
// all key off this id). FNV-1a's 64-bit output makes collisions practically impossible instead.
// Not private: NavidromeDownloadRepository needs the same string->Long mapping to match a
// domain-model album/song id back to its raw Subsonic string id.
internal fun stableLongId(s: String): Long {
    var hash = -3750763034362895579L // FNV-1a 64-bit offset basis
    for (b in s.toByteArray(Charsets.UTF_8)) {
        hash = hash xor (b.toLong() and 0xff)
        hash *= 1099511628211L // FNV-1a 64-bit prime
    }
    return hash
}

// Accepts both "2025-03-26T22:26:35Z" and offset forms like "2025-03-26T22:26:35+00:00".
private fun parseIsoMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
}

private fun SubsonicSong.toEntity(creds: NavidromeApiProvider.Credentials): NavidromeSongEntity {
    val streamUrl = buildStreamUrl(creds.serverUrl, creds.username, creds.password, id)
    val cover = coverArt?.let { coverArtUrl(creds, it) }
    return NavidromeSongEntity(
        navidromeId = id,
        numericId = id.toLongOrNull() ?: stableLongId(id),
        streamUrl = streamUrl,
        title = title,
        artist = artist ?: "Unknown Artist",
        albumArtist = albumArtist ?: artist ?: "Unknown Artist",
        album = album ?: "Unknown Album",
        albumId = albumId ?: "",
        coverArtUrl = cover,
        genre = genre ?: "",
        durationMs = (duration ?: 0) * 1000L,
        trackNumber = track ?: 0,
        discNumber = discNumber ?: 1,
        year = year ?: 0,
        path = path ?: streamUrl,
        mimeType = contentType ?: "audio/mpeg",
        bitrate = bitRate ?: 0,
        size = size ?: 0L,
        playCount = playCount ?: 0L,
        // cachedAt doubles as "date added" in the UI — use the server's, so "Recently added"
        // reflects the library rather than the last sync.
        cachedAt = parseIsoMillis(created) ?: System.currentTimeMillis(),
    )
}

private fun SubsonicAlbum.toEntity(creds: NavidromeApiProvider.Credentials): NavidromeAlbumEntity {
    val cover = coverArt?.let { coverArtUrl(creds, it) }
    return NavidromeAlbumEntity(
        navidromeId = id,
        numericId = id.toLongOrNull() ?: stableLongId(id),
        name = name,
        artist = artist ?: "Unknown Artist",
        artistId = artistId,
        coverArtUrl = cover,
        songCount = songCount ?: song.size,
        durationSec = duration ?: 0,
        year = year ?: 0,
    )
}

internal fun NavidromeSongEntity.toDomain(): Song = Song(
    id = numericId,
    uri = Uri.parse(streamUrl),
    title = title,
    artist = artist,
    artists = listOf(artist),
    albumArtist = albumArtist,
    album = album,
    albumId = albumId.toLongOrNull() ?: stableLongId(albumId),
    genre = genre,
    duration = durationMs,
    size = size,
    bitrate = bitrate,
    sampleRate = 0,
    trackNumber = trackNumber,
    discNumber = discNumber,
    year = year,
    dateAdded = cachedAt,
    dateModified = cachedAt,
    path = path,
    folder = path.substringBeforeLast('/'),
    mimeType = mimeType,
    replayGainTrack = null,
    replayGainAlbum = null,
    artworkUri = coverArtUrl?.let { Uri.parse(it) },
    listenCount = playCount.toInt(),
    lastListened = 0L,
    navidromeId = navidromeId,
)

internal fun NavidromeAlbumEntity.toDomain(songs: List<Song> = emptyList()): Album = Album(
    id = numericId,
    title = name,
    artist = artist,
    year = year,
    songCount = songCount,
    artworkUri = coverArtUrl?.let { Uri.parse(it) },
    songs = songs,
)

internal fun SubsonicSong.toSongDomain(creds: NavidromeApiProvider.Credentials): Song {
    val streamUrl = buildStreamUrl(creds.serverUrl, creds.username, creds.password, id)
    val cover = coverArt?.let { coverArtUrl(creds, it) }
    return Song(
        id = id.toLongOrNull() ?: stableLongId(id),
        uri = Uri.parse(streamUrl),
        title = title,
        artist = artist ?: "Unknown Artist",
        artists = listOf(artist ?: "Unknown Artist"),
        albumArtist = albumArtist ?: artist ?: "Unknown Artist",
        album = album ?: "Unknown Album",
        albumId = albumId?.toLongOrNull() ?: albumId?.let { stableLongId(it) } ?: 0L,
        genre = genre ?: "",
        duration = (duration ?: 0) * 1000L,
        size = size ?: 0L,
        bitrate = bitRate ?: 0,
        sampleRate = 0,
        trackNumber = track ?: 0,
        discNumber = discNumber ?: 1,
        year = year ?: 0,
        dateAdded = 0L,
        dateModified = 0L,
        path = path ?: streamUrl,
        folder = path?.substringBeforeLast('/') ?: "",
        mimeType = contentType ?: "audio/mpeg",
        replayGainTrack = null,
        replayGainAlbum = null,
        artworkUri = cover?.let { Uri.parse(it) },
        listenCount = playCount?.toInt() ?: 0,
        lastListened = 0L,
        navidromeId = id,
    )
}

internal fun SubsonicArtistSummary.toDomain(creds: NavidromeApiProvider.Credentials): Artist {
    val cover = coverArt?.let { coverArtUrl(creds, it) }
    return Artist(
        name = name,
        songCount = 0,
        albumCount = albumCount ?: 0,
        artworkUrl = cover,
    )
}
