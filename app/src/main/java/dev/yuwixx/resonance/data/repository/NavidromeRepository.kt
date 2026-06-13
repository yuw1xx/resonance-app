package dev.yuwixx.resonance.data.repository

import android.net.Uri
import androidx.room.withTransaction
import dev.yuwixx.resonance.data.database.ResonanceDatabase
import dev.yuwixx.resonance.data.database.dao.NavidromeAlbumDao
import dev.yuwixx.resonance.data.database.dao.NavidromeSongDao
import dev.yuwixx.resonance.data.database.entity.NavidromeAlbumEntity
import dev.yuwixx.resonance.data.database.entity.NavidromeSongEntity
import dev.yuwixx.resonance.data.model.*
import dev.yuwixx.resonance.data.network.*
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

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
) {
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

        suspend fun hasCachedData(): Boolean =
        navidromeSongDao.count() > 0

        suspend fun clearCache() = withContext(Dispatchers.IO) {
        navidromeSongDao.deleteAll()
        navidromeAlbumDao.deleteAll()
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

        suspend fun syncLibrary(): Boolean = withContext(Dispatchers.IO) {
        val api   = navidromeApiProvider.currentApi()   ?: return@withContext false
        val creds = navidromeApiProvider.currentCredentials() ?: return@withContext false

        try {
            val songEntities  = mutableListOf<NavidromeSongEntity>()
            val albumEntities = mutableListOf<NavidromeAlbumEntity>()

            val previousSongCount = navidromeSongDao.count()
            _syncState.value = NavidromeSyncState.Syncing(0, 0, previousSongCount)

            var estimatedByAlbums = 0
            var offset = 0
            while (true) {
                val batch = api.getAlbumList(size = 500, offset = offset)
                    .response.albumList2?.album ?: break

                for (summary in batch) {
                    val album = runCatching { api.getAlbum(summary.id).response.album }
                        .getOrNull() ?: summary

                    val albumEntity = album.toEntity(creds)
                    albumEntities += albumEntity
                    estimatedByAlbums += albumEntity.songCount

                    for (song in album.song) {
                        songEntities += song.toEntity(creds)
                    }

                    val estimatedTotal = maxOf(estimatedByAlbums, previousSongCount)
                    _syncState.value = NavidromeSyncState.Syncing(albumEntities.size, songEntities.size, estimatedTotal)
                }

                if (batch.size < 500) break
                offset += 500
            }

            database.withTransaction {
                navidromeSongDao.deleteAll()
                navidromeAlbumDao.deleteAll()
                navidromeSongDao.upsertAll(songEntities)
                navidromeAlbumDao.upsertAll(albumEntities)
            }

            _syncState.value = NavidromeSyncState.Done(songEntities.size, albumEntities.size)
            android.util.Log.i("NavidromeRepository",
                "Sync complete — ${songEntities.size} songs, ${albumEntities.size} albums cached")
            true
        } catch (e: Exception) {
            _syncState.value = NavidromeSyncState.Error(e.message ?: "Sync failed")
            android.util.Log.e("NavidromeRepository", "syncLibrary failed: ${e.message}", e)
            false
        }
    }

    suspend fun getAllArtists(): List<Artist> = withContext(Dispatchers.IO) {
        val api   = navidromeApiProvider.currentApi()   ?: return@withContext emptyList()
        val creds = navidromeApiProvider.currentCredentials() ?: return@withContext emptyList()
        try {
            api.getArtists().response.artists?.index
                ?.flatMap { it.artist }
                ?.map { it.toDomain(creds) }
                ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("NavidromeRepository", "getAllArtists failed: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getArtistWithSongs(artistId: String): Artist? = withContext(Dispatchers.IO) {
        val api   = navidromeApiProvider.currentApi()   ?: return@withContext null
        val creds = navidromeApiProvider.currentCredentials() ?: return@withContext null
        try {
            val artist = api.getArtist(artistId).response.artist ?: return@withContext null
            val albums = artist.album.map { summary ->
                val detail = runCatching { api.getAlbum(summary.id).response.album }.getOrNull() ?: summary
                detail.toAlbumDomain(creds)
            }
            Artist(
                name      = artist.name,
                songCount = albums.sumOf { it.songCount },
                albumCount = albums.size,
                artworkUrl = artist.coverArt?.let { coverArtUrl(creds, it) },
                songs  = albums.flatMap { it.songs },
                albums = albums,
            )
        } catch (e: Exception) {
            android.util.Log.e("NavidromeRepository", "getArtistWithSongs failed: ${e.message}", e)
            null
        }
    }

    suspend fun getAllPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        val api   = navidromeApiProvider.currentApi()   ?: return@withContext emptyList()
        val creds = navidromeApiProvider.currentCredentials() ?: return@withContext emptyList()
        try {
            api.getPlaylists().response.playlists?.playlist?.map { summary ->
                val detail = runCatching { api.getPlaylist(summary.id).response.playlist }.getOrNull()
                Playlist(
                    id       = summary.id.hashCode().toLong(),
                    name     = summary.name,
                    songs    = detail?.entry?.map { it.toSongDomain(creds) } ?: emptyList(),
                    isReadOnly = false,
                    artworkUri = summary.coverArt?.let { Uri.parse(coverArtUrl(creds, it)) },
                )
            } ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("NavidromeRepository", "getAllPlaylists failed: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun notifyNowPlaying(songId: String) {
        val api = navidromeApiProvider.currentApi() ?: return
        runCatching { api.scrobble(id = songId, submission = false) }
    }

    suspend fun submitScrobble(songId: String, playedAtMs: Long) {
        val api = navidromeApiProvider.currentApi() ?: return
        runCatching { api.scrobble(id = songId, timeMs = playedAtMs, submission = true) }
    }

}

private fun buildStreamUrl(
    serverUrl: String,
    username: String,
    password: String,
    songId: String,
): String {
    val salt  = UUID.randomUUID().toString().replace("-", "").take(12)
    val token = MessageDigest.getInstance("MD5")
        .digest("$password$salt".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "${serverUrl.trimEnd('/')}/rest/stream" +
            "?id=$songId&u=$username&t=$token&s=$salt&v=1.16.1&c=Resonance&f=json"
}

private fun coverArtUrl(creds: NavidromeApiProvider.Credentials, id: String): String {
    val salt  = UUID.randomUUID().toString().replace("-", "").take(12)
    val token = MessageDigest.getInstance("MD5")
        .digest("${creds.password}$salt".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "${creds.serverUrl.trimEnd('/')}/rest/getCoverArt" +
            "?id=$id&u=${creds.username}&t=$token&s=$salt&v=1.16.1&c=Resonance&f=json"
}

private fun SubsonicSong.toEntity(creds: NavidromeApiProvider.Credentials): NavidromeSongEntity {
    val streamUrl = buildStreamUrl(creds.serverUrl, creds.username, creds.password, id)
    val cover     = coverArt?.let { coverArtUrl(creds, it) }
    return NavidromeSongEntity(
        navidromeId  = id,
        numericId    = id.toLongOrNull() ?: id.hashCode().toLong(),
        streamUrl    = streamUrl,
        title        = title,
        artist       = artist       ?: "Unknown Artist",
        albumArtist  = albumArtist  ?: artist ?: "Unknown Artist",
        album        = album        ?: "Unknown Album",
        albumId      = albumId      ?: "",
        coverArtUrl  = cover,
        genre        = genre        ?: "",
        durationMs   = (duration    ?: 0) * 1000L,
        trackNumber  = track        ?: 0,
        discNumber   = discNumber   ?: 1,
        year         = year         ?: 0,
        path         = path         ?: streamUrl,
        mimeType     = contentType  ?: "audio/mpeg",
        bitrate      = bitRate      ?: 0,
        size         = size         ?: 0L,
        playCount    = playCount    ?: 0L,
    )
}

private fun SubsonicAlbum.toEntity(creds: NavidromeApiProvider.Credentials): NavidromeAlbumEntity {
    val cover = coverArt?.let { coverArtUrl(creds, it) }
    return NavidromeAlbumEntity(
        navidromeId = id,
        numericId   = id.toLongOrNull() ?: id.hashCode().toLong(),
        name        = name,
        artist      = artist     ?: "Unknown Artist",
        artistId    = artistId,
        coverArtUrl = cover,
        songCount   = songCount  ?: song.size,
        durationSec = duration   ?: 0,
        year        = year       ?: 0,
    )
}

internal fun NavidromeSongEntity.toDomain(): Song = Song(
    id            = numericId,
    uri           = Uri.parse(streamUrl),
    title         = title,
    artist        = artist,
    artists       = listOf(artist),
    albumArtist   = albumArtist,
    album         = album,
    albumId       = albumId.toLongOrNull() ?: albumId.hashCode().toLong(),
    genre         = genre,
    duration      = durationMs,
    size          = size,
    bitrate       = bitrate,
    sampleRate    = 0,
    trackNumber   = trackNumber,
    discNumber    = discNumber,
    year          = year,
    dateAdded     = cachedAt,
    dateModified  = cachedAt,
    path          = path,
    folder        = path.substringBeforeLast('/'),
    mimeType      = mimeType,
    replayGainTrack = null,
    replayGainAlbum = null,
    artworkUri    = coverArtUrl?.let { Uri.parse(it) },
    listenCount   = playCount.toInt(),
    lastListened  = 0L,
)

internal fun NavidromeAlbumEntity.toDomain(songs: List<Song> = emptyList()): Album = Album(
    id         = numericId,
    title      = name,
    artist     = artist,
    year       = year,
    songCount  = songCount,
    artworkUri = coverArtUrl?.let { Uri.parse(it) },
    songs      = songs,
)

internal fun SubsonicSong.toSongDomain(creds: NavidromeApiProvider.Credentials): Song {
    val streamUrl = buildStreamUrl(creds.serverUrl, creds.username, creds.password, id)
    val cover     = coverArt?.let { coverArtUrl(creds, it) }
    return Song(
        id            = id.toLongOrNull() ?: id.hashCode().toLong(),
        uri           = Uri.parse(streamUrl),
        title         = title,
        artist        = artist       ?: "Unknown Artist",
        artists       = listOf(artist ?: "Unknown Artist"),
        albumArtist   = albumArtist  ?: artist ?: "Unknown Artist",
        album         = album        ?: "Unknown Album",
        albumId       = albumId?.toLongOrNull() ?: (albumId?.hashCode()?.toLong() ?: 0L),
        genre         = genre        ?: "",
        duration      = (duration    ?: 0) * 1000L,
        size          = size         ?: 0L,
        bitrate       = bitRate      ?: 0,
        sampleRate    = 0,
        trackNumber   = track        ?: 0,
        discNumber    = discNumber   ?: 1,
        year          = year         ?: 0,
        dateAdded     = 0L,
        dateModified  = 0L,
        path          = path         ?: streamUrl,
        folder        = path?.substringBeforeLast('/') ?: "",
        mimeType      = contentType  ?: "audio/mpeg",
        replayGainTrack = null,
        replayGainAlbum = null,
        artworkUri    = cover?.let { Uri.parse(it) },
        listenCount   = playCount?.toInt() ?: 0,
        lastListened  = 0L,
    )
}

internal fun SubsonicAlbum.toAlbumDomain(creds: NavidromeApiProvider.Credentials): Album {
    val cover = coverArt?.let { coverArtUrl(creds, it) }
    return Album(
        id        = id.toLongOrNull() ?: id.hashCode().toLong(),
        title     = name,
        artist    = artist   ?: "Unknown Artist",
        year      = year     ?: 0,
        songCount = songCount ?: song.size,
        artworkUri = cover?.let { Uri.parse(it) },
        songs     = song.map { it.toSongDomain(creds) },
    )
}

internal fun SubsonicArtistSummary.toDomain(creds: NavidromeApiProvider.Credentials): Artist {
    val cover = coverArt?.let { coverArtUrl(creds, it) }
    return Artist(
        name       = name,
        songCount  = 0,
        albumCount = albumCount ?: 0,
        artworkUrl = cover,
    )
}
