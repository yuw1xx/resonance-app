package dev.yuwixx.resonance.data.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

interface GitHubApi {
    @GET("repos/yuw1xx/resonance/releases/latest")
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