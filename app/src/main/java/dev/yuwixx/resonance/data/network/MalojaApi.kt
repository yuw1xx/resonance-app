package dev.yuwixx.resonance.data.network

import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

@JsonClass(generateAdapter = true)
data class MalojaScrobbleRequest(
    val artists: List<String>,
    val title: String,
    val album: String? = null,
    val length: Int? = null,
    val time: Long? = null,
    val key: String,
)

@JsonClass(generateAdapter = true)
data class MalojaResponse(
    val status: String = "",
    val desc: String? = null,
)

interface MalojaApi {
    @POST("apis/mlj_1/newscrobble")
    suspend fun scrobble(@Body request: MalojaScrobbleRequest): MalojaResponse

    @GET("apis/mlj_1/scrobbles")
    suspend fun ping(@Query("key") key: String, @Query("max") max: Int = 1): MalojaResponse
}
