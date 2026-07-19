// Spotify Client Credentials flow: app-level auth (no user login) used only to look up
// album art for tracks missing embedded artwork. Token is cached in memory until it expires.
package dev.yuwixx.resonance.data.repository

import android.util.Base64
import android.util.Log
import dev.yuwixx.resonance.data.network.SpotifyApi
import dev.yuwixx.resonance.data.network.SpotifyAuthApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SpotifyRepo"
private const val DEFAULT_CLIENT_ID     = "257fd355d619466b80cc30e02e3836b2"
private const val DEFAULT_CLIENT_SECRET = "6113274799b340388eaf12946dd0b4df"

@Singleton
class SpotifyRepository @Inject constructor(
    private val authApi: SpotifyAuthApi,
    private val api: SpotifyApi,
) {
    private val tokenMutex = Mutex()
    private var cachedToken: String? = null
    private var tokenExpiresAtMs: Long = 0L

    private suspend fun getAccessToken(): String? = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        cachedToken?.let { token -> if (now < tokenExpiresAtMs) return@withLock token }

        try {
            val basicAuth = Base64.encodeToString(
                "$DEFAULT_CLIENT_ID:$DEFAULT_CLIENT_SECRET".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            val response = authApi.getToken(basicAuth = "Basic $basicAuth")
            cachedToken = response.accessToken
            // Refresh a minute early so a request never races an about-to-expire token.
            tokenExpiresAtMs = now + (response.expiresInSeconds - 60).coerceAtLeast(0) * 1000L
            response.accessToken
        } catch (e: Exception) {
            Log.w(TAG, "Failed to obtain Spotify access token: ${e.message}")
            null
        }
    }

    suspend fun searchAlbumArt(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        try {
            val token = getAccessToken() ?: return@withContext null
            val query = "track:$title artist:$artist"
            val response = api.searchTracks(bearerAuth = "Bearer $token", query = query)
            response.tracks?.items?.firstOrNull()
                ?.album?.images?.maxByOrNull { it.width ?: 0 }
                ?.url
        } catch (e: Exception) {
            Log.w(TAG, "Spotify album art search failed: ${e.message}")
            null
        }
    }
}
