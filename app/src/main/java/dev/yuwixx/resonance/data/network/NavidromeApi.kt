package dev.yuwixx.resonance.data.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

interface NavidromeApi {

    @GET("rest/ping.view")
    suspend fun ping(): SubsonicResponse

    /** Cheap change detection: Navidrome reports when the library was last scanned. */
    @GET("rest/getScanStatus.view")
    suspend fun getScanStatus(): SubsonicResponse

    @GET("rest/getAlbumList2.view")
    suspend fun getAlbumList(
        @Query("type") type: String = "alphabeticalByName",
        @Query("size") size: Int = 500,
        @Query("offset") offset: Int = 0,
    ): SubsonicResponse

    @GET("rest/getAlbum.view")
    suspend fun getAlbum(@Query("id") id: String): SubsonicResponse

    @GET("rest/getArtists.view")
    suspend fun getArtists(): SubsonicResponse

    @GET("rest/getArtist.view")
    suspend fun getArtist(@Query("id") id: String): SubsonicResponse

    @GET("rest/getPlaylists.view")
    suspend fun getPlaylists(): SubsonicResponse

    @GET("rest/getPlaylist.view")
    suspend fun getPlaylist(@Query("id") id: String): SubsonicResponse

    @GET("rest/search3.view")
    suspend fun search(
        @Query("query") query: String,
        @Query("songCount") songCount: Int = 100,
        @Query("albumCount") albumCount: Int = 20,
        @Query("artistCount") artistCount: Int = 20,
        @Query("songOffset") songOffset: Int = 0,
    ): SubsonicResponse

    @GET("rest/scrobble.view")
    suspend fun scrobble(
        @Query("id") id: String,
        @Query("time") timeMs: Long? = null,
        @Query("submission") submission: Boolean = true,
    ): SubsonicResponse

    @GET("rest/getRandomSongs.view")
    suspend fun getRandomSongs(
        @Query("size") size: Int = 500,
        @Query("musicFolderId") folderId: String? = null,
    ): SubsonicResponse

    @GET("rest/star.view")
    suspend fun star(@Query("id") id: String): SubsonicResponse

    @GET("rest/unstar.view")
    suspend fun unstar(@Query("id") id: String): SubsonicResponse

    @GET("rest/getStarred2.view")
    suspend fun getStarred2(): SubsonicResponse

    /** Creates a playlist, or with [playlistId] replaces an existing playlist's tracks. */
    @GET("rest/createPlaylist.view")
    suspend fun createPlaylist(
        @Query("name") name: String? = null,
        @Query("songId") songIds: List<String> = emptyList(),
        @Query("playlistId") playlistId: String? = null,
    ): SubsonicResponse

    @GET("rest/updatePlaylist.view")
    suspend fun updatePlaylist(
        @Query("playlistId") playlistId: String,
        @Query("name") name: String? = null,
        @Query("songIdToAdd") songIdsToAdd: List<String> = emptyList(),
        @Query("songIdToRemove") songIdsToRemove: List<String> = emptyList(),
    ): SubsonicResponse

    @GET("rest/deletePlaylist.view")
    suspend fun deletePlaylist(@Query("id") id: String): SubsonicResponse

    /** OpenSubsonic: structured (possibly synced) lyrics for a song. */
    @GET("rest/getLyricsBySongId.view")
    suspend fun getLyricsBySongId(@Query("id") id: String): SubsonicResponse

    /** Classic Subsonic: plain lyrics by artist + title. */
    @GET("rest/getLyrics.view")
    suspend fun getLyrics(@Query("artist") artist: String, @Query("title") title: String): SubsonicResponse
}

@JsonClass(generateAdapter = true)
data class SubsonicResponse(
    @Json(name = "subsonic-response") val response: SubsonicResponseBody,
)

/** Thrown for a Subsonic error, which the API reports as HTTP 200 with status "failed". */
class SubsonicException(val code: Int?, message: String) : Exception(message)

/**
 * The response body, or [SubsonicException] if the server reported an error. Callers must use
 * this rather than [SubsonicResponse.response]: a failed response has no data, and reading it as
 * "empty" would look like an empty library.
 */
val SubsonicResponse.ok: SubsonicResponseBody
    get() {
        if (response.status != "ok") {
            throw SubsonicException(response.error?.code, response.error?.message ?: "Server error (${response.status})")
        }
        return response
    }

@JsonClass(generateAdapter = true)
data class SubsonicResponseBody(
    val status: String,
    val version: String,
    val error: SubsonicError? = null,
    val songs: SubsonicSongs? = null,
    val randomSongs: SubsonicSongs? = null,
    val albumList2: SubsonicAlbumList? = null,
    val album: SubsonicAlbum? = null,
    val artists: SubsonicArtistsIndex? = null,
    val artist: SubsonicArtist? = null,
    val playlists: SubsonicPlaylists? = null,
    val playlist: SubsonicPlaylistWithSongs? = null,
    val searchResult3: SubsonicSearchResult? = null,
    val starred2: SubsonicStarred2? = null,
    val scanStatus: SubsonicScanStatus? = null,
    val lyricsList: SubsonicLyricsList? = null,
    val lyrics: SubsonicPlainLyrics? = null,
)

@JsonClass(generateAdapter = true)
data class SubsonicLyricsList(val structuredLyrics: List<SubsonicStructuredLyrics> = emptyList())

@JsonClass(generateAdapter = true)
data class SubsonicStructuredLyrics(
    val lang: String? = null,
    val synced: Boolean = false,
    val offset: Long? = null,
    val line: List<SubsonicLyricLine> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class SubsonicLyricLine(val start: Long? = null, val value: String = "")

@JsonClass(generateAdapter = true)
data class SubsonicPlainLyrics(val value: String? = null)

@JsonClass(generateAdapter = true)
data class SubsonicScanStatus(
    val scanning: Boolean = false,
    val count: Long? = null,
    val folderCount: Long? = null,
    /** Navidrome extension (ISO-8601); absent on other Subsonic servers. */
    val lastScan: String? = null,
)

@JsonClass(generateAdapter = true)
data class SubsonicStarred2(
    val song: List<SubsonicSong> = emptyList(),
    val album: List<SubsonicAlbum> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class SubsonicError(val code: Int, val message: String)

@JsonClass(generateAdapter = true)
data class SubsonicSongs(val song: List<SubsonicSong> = emptyList())

@JsonClass(generateAdapter = true)
data class SubsonicSong(
    val id: String,
    val title: String,
    val artist: String? = null,
    val artistId: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val coverArt: String? = null,
    val duration: Int? = null,
    val bitRate: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val size: Long? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    val path: String? = null,
    val playCount: Long? = null,
    /** ISO-8601 time the file was added to the server library. */
    val created: String? = null,
)

@JsonClass(generateAdapter = true)
data class SubsonicAlbumList(val album: List<SubsonicAlbum> = emptyList())

@JsonClass(generateAdapter = true)
data class SubsonicAlbum(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val year: Int? = null,
    val song: List<SubsonicSong> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class SubsonicArtistsIndex(val index: List<SubsonicIndex> = emptyList())

@JsonClass(generateAdapter = true)
data class SubsonicIndex(
    val name: String,
    val artist: List<SubsonicArtistSummary> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class SubsonicArtistSummary(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val coverArt: String? = null,
)

@JsonClass(generateAdapter = true)
data class SubsonicArtist(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val albumCount: Int? = null,
    val album: List<SubsonicAlbum> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class SubsonicPlaylists(val playlist: List<SubsonicPlaylistSummary> = emptyList())

@JsonClass(generateAdapter = true)
data class SubsonicPlaylistSummary(
    val id: String,
    val name: String,
    val songCount: Int? = null,
    val duration: Int? = null,
    val coverArt: String? = null,
)

@JsonClass(generateAdapter = true)
data class SubsonicPlaylistWithSongs(
    val id: String,
    val name: String,
    val entry: List<SubsonicSong> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class SubsonicSearchResult(
    val song: List<SubsonicSong> = emptyList(),
    val album: List<SubsonicAlbum> = emptyList(),
    val artist: List<SubsonicArtistSummary> = emptyList(),
)
