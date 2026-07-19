package dev.yuwixx.resonance.data.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface GitHubApi {
    @GET("repos/yuw1xx/resonance-app/releases/latest")
    suspend fun getLatestRelease(): GitHubRelease
}

@JsonClass(generateAdapter = true)
data class GitHubRelease(
    @Json(name = "tag_name") val tagName: String,
    val name: String,
    val body: String?,
    val assets: List<GitHubAsset>
)

@JsonClass(generateAdapter = true)
data class GitHubAsset(
    val name: String,
    @Json(name = "browser_download_url") val browserDownloadUrl: String
)

interface LrclibApi {
    @GET("api/get")
    suspend fun getLyrics(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String,
        @Query("album_name") albumName: String?,
        @Query("duration") durationSeconds: Int?,
    ): LrclibResponse?

    @GET("api/search")
    suspend fun searchLyrics(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String,
    ): List<LrclibResponse>
}

@JsonClass(generateAdapter = true)
data class LrclibResponse(
    val id: Long,
    @Json(name = "trackName") val trackName: String,
    @Json(name = "artistName") val artistName: String,
    @Json(name = "albumName") val albumName: String?,
    val duration: Float?,
    @Json(name = "syncedLyrics") val syncedLyrics: String?,
    @Json(name = "plainLyrics") val plainLyrics: String?,
    val instrumental: Boolean,
)

interface DeezerApi {
    @GET("search/artist")
    suspend fun searchArtist(
        @Query("q") query: String,
        @Query("limit") limit: Int = 1,
    ): DeezerArtistSearchResponse
}

@JsonClass(generateAdapter = true)
data class DeezerArtistSearchResponse(
    val data: List<DeezerArtist>,
)

@JsonClass(generateAdapter = true)
data class DeezerArtist(
    val id: Long,
    val name: String,
    @Json(name = "picture_medium") val pictureMedium: String?,
    @Json(name = "picture_xl") val pictureXl: String?,
    val nb_fan: Long?,
)

interface SpotifyAuthApi {
    @FormUrlEncoded
    @POST("api/token")
    suspend fun getToken(
        @Header("Authorization") basicAuth: String,
        @Field("grant_type") grantType: String = "client_credentials",
    ): SpotifyTokenResponse
}

@JsonClass(generateAdapter = true)
data class SpotifyTokenResponse(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "expires_in") val expiresInSeconds: Int,
)

interface SpotifyApi {
    @GET("v1/search")
    suspend fun searchTracks(
        @Header("Authorization") bearerAuth: String,
        @Query("q") query: String,
        @Query("type") type: String = "track",
        @Query("limit") limit: Int = 1,
    ): SpotifySearchResponse
}

@JsonClass(generateAdapter = true)
data class SpotifySearchResponse(
    val tracks: SpotifyTrackPage?,
)

@JsonClass(generateAdapter = true)
data class SpotifyTrackPage(
    val items: List<SpotifyTrack>,
)

@JsonClass(generateAdapter = true)
data class SpotifyTrack(
    val name: String,
    val album: SpotifyAlbum,
)

@JsonClass(generateAdapter = true)
data class SpotifyAlbum(
    val images: List<SpotifyImage>,
)

@JsonClass(generateAdapter = true)
data class SpotifyImage(
    val url: String,
    val width: Int?,
    val height: Int?,
)