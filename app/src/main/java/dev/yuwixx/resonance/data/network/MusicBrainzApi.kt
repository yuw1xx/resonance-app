package dev.yuwixx.resonance.data.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface MusicBrainzApi {
    @GET("artist")
    suspend fun searchArtist(
        @Query("query") query: String,
        @Query("fmt") fmt: String = "json",
        @Query("limit") limit: Int = 5,
    ): MbArtistSearchResponse

    @GET("artist/{mbid}")
    suspend fun getArtist(
        @Path("mbid") mbid: String,
        @Query("inc") inc: String = "tags+url-rels",
        @Query("fmt") fmt: String = "json",
    ): MbArtist

    @GET("release-group")
    suspend fun searchReleaseGroup(
        @Query("query") query: String,
        @Query("fmt") fmt: String = "json",
        @Query("limit") limit: Int = 5,
    ): MbReleaseGroupSearchResponse
}

@JsonClass(generateAdapter = true)
data class MbArtistSearchResponse(
    val artists: List<MbArtist> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class MbArtist(
    val id: String = "",
    val name: String = "",
    val type: String? = null,
    val score: Int? = null,
    @Json(name = "life-span") val lifeSpan: MbLifeSpan? = null,
    val tags: List<MbTag> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class MbLifeSpan(
    val begin: String? = null,
    val end: String? = null,
    val ended: Boolean? = null,
)

@JsonClass(generateAdapter = true)
data class MbTag(
    val name: String = "",
    val count: Int = 0,
)

@JsonClass(generateAdapter = true)
data class MbReleaseGroupSearchResponse(
    @Json(name = "release-groups") val releaseGroups: List<MbReleaseGroup> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class MbReleaseGroup(
    val id: String = "",
    val title: String = "",
    @Json(name = "first-release-date") val firstReleaseDate: String? = null,
    @Json(name = "artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val score: Int? = null,
)

@JsonClass(generateAdapter = true)
data class MbArtistCredit(
    val name: String = "",
)
