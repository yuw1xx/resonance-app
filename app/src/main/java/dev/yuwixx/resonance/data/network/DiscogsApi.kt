package dev.yuwixx.resonance.data.network

import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

interface DiscogsApi {
    @GET("database/search")
    suspend fun searchArtist(
        @Query("q") q: String,
        @Header("Authorization") auth: String,
        @Query("type") type: String = "artist",
    ): DiscogsSearchResponse

    @GET("artists/{id}")
    suspend fun getArtist(
        @Path("id") id: Long,
        @Header("Authorization") auth: String,
    ): DiscogsArtist
}

@JsonClass(generateAdapter = true)
data class DiscogsSearchResponse(
    val results: List<DiscogsSearchResult> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DiscogsSearchResult(
    val id: Long = 0,
    val title: String = "",
)

@JsonClass(generateAdapter = true)
data class DiscogsArtist(
    val id: Long = 0,
    val name: String = "",
    val profile: String = "",
    val members: List<DiscogsMember>? = null,
)

@JsonClass(generateAdapter = true)
data class DiscogsMember(
    val id: Long = 0,
    val name: String = "",
    val active: Boolean = true,
)
