package dev.yuwixx.resonance.data.repository

import kotlinx.coroutines.CancellationException
import dev.yuwixx.resonance.BuildConfig
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
private val DEFAULT_CLIENT_ID = BuildConfig.SPOTIFY_CLIENT_ID
private val DEFAULT_CLIENT_SECRET = BuildConfig.SPOTIFY_CLIENT_SECRET

/**
 * Album art lookup through Spotify's client-credentials flow (no user login). The token is kept
 * in memory until it expires.
 */
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

    /**
     * The album art URL for a track: success with null means Spotify has no match; failure means
     * the lookup itself didn't work (no network, no API key), which callers shouldn't cache.
     */
    suspend fun searchAlbumArt(title: String, artist: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val token = getAccessToken() ?: return@withContext Result.failure(IllegalStateException("No Spotify token"))
            val query = "track:$title artist:$artist"
            val response = api.searchTracks(bearerAuth = "Bearer $token", query = query)
            Result.success(
                response.tracks?.items?.firstOrNull()
                    ?.album?.images?.maxByOrNull { it.width ?: 0 }
                    ?.url
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Spotify album art search failed: ${e.message}")
            Result.failure(e)
        }
    }
}
