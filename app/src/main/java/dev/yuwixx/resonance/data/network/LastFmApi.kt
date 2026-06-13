package dev.yuwixx.resonance.data.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.*

interface LastFmApi {

        @POST("2.0/")
    @FormUrlEncoded
    suspend fun getMobileSession(
        @Field("method") method: String = "auth.getMobileSession",
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("api_key") apiKey: String,
        @Field("api_sig") apiSig: String,
        @Field("format") format: String = "json",
    ): LastFmSessionResponse

        @POST("2.0/")
    @FormUrlEncoded
    suspend fun scrobble(
        @Field("method") method: String = "track.scrobble",
        @Field("artist[0]") artist: String,
        @Field("track[0]") track: String,
        @Field("timestamp[0]") timestamp: Long,
        @Field("album[0]") album: String? = null,
        @Field("duration[0]") duration: Int? = null,
        @Field("trackNumber[0]") trackNumber: Int? = null,
        @Field("api_key") apiKey: String,
        @Field("sk") sessionKey: String,
        @Field("api_sig") apiSig: String,
        @Field("format") format: String = "json",
    ): LastFmScrobbleResponse

        @POST("2.0/")
    @FormUrlEncoded
    suspend fun updateNowPlaying(
        @Field("method") method: String = "track.updateNowPlaying",
        @Field("artist") artist: String,
        @Field("track") track: String,
        @Field("album") album: String? = null,
        @Field("duration") duration: Int? = null,
        @Field("trackNumber") trackNumber: Int? = null,
        @Field("api_key") apiKey: String,
        @Field("sk") sessionKey: String,
        @Field("api_sig") apiSig: String,
        @Field("format") format: String = "json",
    ): LastFmNowPlayingResponse

        @POST("2.0/")
    @FormUrlEncoded
    suspend fun loveTrack(
        @Field("method") method: String = "track.love",
        @Field("artist") artist: String,
        @Field("track") track: String,
        @Field("api_key") apiKey: String,
        @Field("sk") sessionKey: String,
        @Field("api_sig") apiSig: String,
        @Field("format") format: String = "json",
    ): LastFmBaseResponse

        @POST("2.0/")
    @FormUrlEncoded
    suspend fun unloveTrack(
        @Field("method") method: String = "track.unlove",
        @Field("artist") artist: String,
        @Field("track") track: String,
        @Field("api_key") apiKey: String,
        @Field("sk") sessionKey: String,
        @Field("api_sig") apiSig: String,
        @Field("format") format: String = "json",
    ): LastFmBaseResponse

    @GET("2.0/")
    suspend fun getUserInfo(
        @Query("method") method: String = "user.getInfo",
        @Query("user") username: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
    ): LastFmUserInfoResponse

    @GET("2.0/")
    suspend fun getTopTracks(
        @Query("method") method: String = "user.getTopTracks",
        @Query("user") username: String,
        @Query("period") period: String = "overall",
        @Query("limit") limit: Int = 20,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
    ): LastFmTopTracksResponse
}

@JsonClass(generateAdapter = true)
data class LastFmBaseResponse(
    val error: Int? = null,
    val message: String? = null,
)

@JsonClass(generateAdapter = true)
data class LastFmSessionResponse(
    val session: LastFmSession? = null,
    val error: Int? = null,
    val message: String? = null,
)

@JsonClass(generateAdapter = true)
data class LastFmSession(
    val name: String,
    val key: String,
    val subscriber: Int,
)

@JsonClass(generateAdapter = true)
data class LastFmScrobbleResponse(
    val scrobbles: LastFmScrobbles? = null,
    val error: Int? = null,
    val message: String? = null,
)

@JsonClass(generateAdapter = true)
data class LastFmScrobbles(
    @Json(name = "@attr") val attr: LastFmScrobblesAttr? = null,
)

@JsonClass(generateAdapter = true)
data class LastFmScrobblesAttr(
    val accepted: Int = 0,
    val ignored: Int = 0,
)

@JsonClass(generateAdapter = true)
data class LastFmNowPlayingResponse(
    val nowplaying: LastFmNowPlaying? = null,
    val error: Int? = null,
    val message: String? = null,
)

@JsonClass(generateAdapter = true)
data class LastFmNowPlaying(
    val track: LastFmCorrected? = null,
    val artist: LastFmCorrected? = null,
    val album: LastFmCorrected? = null,
)

@JsonClass(generateAdapter = true)
data class LastFmCorrected(
    @Json(name = "#text") val text: String = "",
    val corrected: String = "0",
)

@JsonClass(generateAdapter = true)
data class LastFmUserInfoResponse(
    val user: LastFmUser? = null,
    val error: Int? = null,
    val message: String? = null,
)

@JsonClass(generateAdapter = true)
data class LastFmUser(
    val name: String,
    val playcount: String = "0",
    val registered: LastFmRegistered? = null,
    val image: List<LastFmImage> = emptyList(),
    val country: String = "",
    val url: String = "",
)

@JsonClass(generateAdapter = true)
data class LastFmRegistered(
    @Json(name = "#text") val text: String = "",
    val unixtime: String = "0",
)

@JsonClass(generateAdapter = true)
data class LastFmImage(
    @Json(name = "#text") val url: String = "",
    val size: String = "",
)

@JsonClass(generateAdapter = true)
data class LastFmTopTracksResponse(
    @Json(name = "toptracks") val topTracks: LastFmTopTracks? = null,
    val error: Int? = null,
    val message: String? = null,
)

@JsonClass(generateAdapter = true)
data class LastFmTopTracks(
    val track: List<LastFmTopTrack> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class LastFmTopTrack(
    val name: String,
    val playcount: String = "0",
    val artist: LastFmTopTrackArtist,
)

@JsonClass(generateAdapter = true)
data class LastFmTopTrackArtist(
    val name: String,
)