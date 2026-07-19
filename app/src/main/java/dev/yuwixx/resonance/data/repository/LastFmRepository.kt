// Last.fm integration: mobile-session authentication, now-playing updates, scrobbling
// with offline queue, and track love/unlove. Uses its own DataStore separate from main prefs.
package dev.yuwixx.resonance.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.database.dao.PendingScrobbleDao
import dev.yuwixx.resonance.data.database.entity.PendingScrobbleEntity
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.network.LastFmApi
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LastFmRepo"
private const val DEFAULT_API_KEY    = "56e83db5fd112b64e486cba54141c783"
private const val DEFAULT_API_SECRET = "cad85a4626b55111661a4d6e0be85f5d"

private val Context.lastFmStore: DataStore<Preferences> by preferencesDataStore(name = "lastfm_prefs")

private object LastFmKeys {
    val SESSION_KEY  = stringPreferencesKey("session_key")
    val USERNAME     = stringPreferencesKey("username")
    val ENABLED      = booleanPreferencesKey("enabled")
    val NOW_PLAYING  = booleanPreferencesKey("now_playing")
    val ONLY_WIFI    = booleanPreferencesKey("only_wifi")
    val API_KEY      = stringPreferencesKey("api_key")
    val API_SECRET   = stringPreferencesKey("api_secret")
}

data class PendingScrobble(
    val artist: String,
    val track: String,
    val album: String,
    val timestamp: Long,
    val duration: Int,
    val trackNumber: Int,
)

sealed class LastFmAuthState {
    data object Idle : LastFmAuthState()
    data object Loading : LastFmAuthState()
    data class Authenticated(val username: String, val playCount: String) : LastFmAuthState()
    data class Error(val message: String) : LastFmAuthState()
}

@Singleton
class LastFmRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: LastFmApi,
    private val mainPrefs: ResonancePreferences,
    private val pendingScrobbleDao: PendingScrobbleDao,
    private val secureCredentialStore: dev.yuwixx.resonance.data.security.SecureCredentialStore,
) {
    private val store = context.lastFmStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pendingScrobbles = java.util.concurrent.CopyOnWriteArrayList<PendingScrobble>()

    val username: Flow<String>    = store.data.map { it[LastFmKeys.USERNAME]    ?: "" }
    val isEnabled: Flow<Boolean>  = store.data.map { it[LastFmKeys.ENABLED]     ?: false }
    val nowPlaying: Flow<Boolean> = store.data.map { it[LastFmKeys.NOW_PLAYING] ?: true }
    val onlyWifi: Flow<Boolean>   = store.data.map { it[LastFmKeys.ONLY_WIFI]   ?: false }

    // Migrated out of plain DataStore into Keystore-backed encrypted storage (see
    // SecureCredentialStore). Was a Flow<String> before, but every use in this file only ever
    // read it with .firstOrNull() — never .collect() — so a plain suspend getter is a smaller,
    // equally-correct change than bridging a Flow over EncryptedSharedPreferences.
    @Volatile private var sessionKeyMigrated = false

    private suspend fun getSessionKey(): String {
        if (!sessionKeyMigrated) {
            sessionKeyMigrated = true
            val legacyPlaintext = store.data.map { it[LastFmKeys.SESSION_KEY] }.firstOrNull()
            if (!legacyPlaintext.isNullOrEmpty()) {
                secureCredentialStore.setLastFmSessionKey(legacyPlaintext)
                store.edit { it.remove(LastFmKeys.SESSION_KEY) }
            }
        }
        return secureCredentialStore.getLastFmSessionKey() ?: ""
    }

    private val _authState = MutableStateFlow<LastFmAuthState>(LastFmAuthState.Idle)
    val authState: StateFlow<LastFmAuthState> = _authState.asStateFlow()

    init {
        scope.launch {
            val sk  = getSessionKey()
            val usr = username.firstOrNull()   ?: ""
            if (sk.isNotBlank() && usr.isNotBlank()) {
                fetchUserInfo(usr)
            }
        }
        // Restore any scrobbles that were queued but not yet flushed before the process died,
        // then try to flush them right away (flushPendingScrobbles() no-ops gracefully if
        // there's no network or the session isn't ready).
        scope.launch {
            val restored = pendingScrobbleDao.getAllForService("LASTFM").map {
                PendingScrobble(
                    artist = it.artists,
                    track = it.title,
                    album = it.album ?: "",
                    timestamp = it.timestamp,
                    duration = it.durationSec ?: 0,
                    trackNumber = it.trackNumber ?: 0,
                )
            }
            if (restored.isNotEmpty()) {
                pendingScrobbles.addAll(restored)
                flushPendingScrobbles()
                persistPendingToDb()
            }
        }
    }

    // Mirrors the current in-memory pendingScrobbles list into the DB so a killed process
    // doesn't lose whatever's still unflushed. Delete-all-then-reinsert instead of per-row
    // tracking — simpler, and flushes are already infrequent/batched.
    private suspend fun persistPendingToDb() {
        pendingScrobbleDao.deleteAllForService("LASTFM")
        pendingScrobbles.forEach { p ->
            pendingScrobbleDao.insert(
                PendingScrobbleEntity(
                    service = "LASTFM",
                    artists = p.artist,
                    title = p.track,
                    album = p.album,
                    durationSec = p.duration,
                    trackNumber = p.trackNumber,
                    timestamp = p.timestamp,
                )
            )
        }
    }

    suspend fun setEnabled(v: Boolean)    { store.edit { it[LastFmKeys.ENABLED]     = v } }
    suspend fun setNowPlaying(v: Boolean) { store.edit { it[LastFmKeys.NOW_PLAYING] = v } }
    suspend fun setOnlyWifi(v: Boolean)   { store.edit { it[LastFmKeys.ONLY_WIFI]   = v } }

    suspend fun setCustomCredentials(apiKey: String, apiSecret: String) {
        store.edit {
            it[LastFmKeys.API_KEY]    = apiKey
            it[LastFmKeys.API_SECRET] = apiSecret
        }
    }

        suspend fun authenticate(username: String, password: String) {
        _authState.value = LastFmAuthState.Loading
        try {
            val (apiKey, apiSecret) = getCredentials()

            val sig = sign(
                mapOf(
                    "method"   to "auth.getMobileSession",
                    "username" to username,
                    "password" to password,
                    "api_key"  to apiKey,
                ),
                apiSecret,
            )

            val response = api.getMobileSession(
                username = username,
                password = password,
                apiKey   = apiKey,
                apiSig   = sig,
            )

            if (response.error != null) {
                _authState.value = LastFmAuthState.Error(
                    response.message ?: "Login failed (error ${response.error})"
                )
                return
            }

            val sk = response.session?.key ?: run {
                _authState.value = LastFmAuthState.Error("No session key in response")
                return
            }

            sessionKeyMigrated = true
            secureCredentialStore.setLastFmSessionKey(sk)
            store.edit {
                it[LastFmKeys.USERNAME]    = username
                it[LastFmKeys.ENABLED]     = true
            }

            fetchUserInfo(username)

        } catch (e: Exception) {
            Log.e(TAG, "authenticate failed", e)
            _authState.value = LastFmAuthState.Error(e.message ?: "Network error — check your connection")
        }
    }

    suspend fun logout() {
        secureCredentialStore.clearLastFmSessionKey()
        store.edit { prefs ->
            prefs.remove(LastFmKeys.USERNAME)
            prefs[LastFmKeys.ENABLED] = false
        }
        _authState.value = LastFmAuthState.Idle
    }

    private suspend fun fetchUserInfo(usr: String) {
        try {
            val (apiKey, _) = getCredentials()
            val resp = api.getUserInfo(username = usr, apiKey = apiKey)
            val user = resp.user
            if (user != null) {
                _authState.value = LastFmAuthState.Authenticated(
                    username  = user.name,
                    playCount = user.playcount,
                )
            } else {
                _authState.value = LastFmAuthState.Authenticated(usr, "–")
            }
        } catch (e: Exception) {
            _authState.value = LastFmAuthState.Authenticated(usr, "–")
        }
    }

    fun updateNowPlaying(song: Song) {
        scope.launch {
            if (!isScrobbleReady()) return@launch
            val nowPlayingOn = nowPlaying.firstOrNull() ?: true
            if (!nowPlayingOn) return@launch
            if (!isNetworkAllowedForScrobble()) return@launch
            try {
                val (apiKey, sk, sig) = buildSignedParams(
                    "track.updateNowPlaying",
                    mapOf(
                        "artist" to song.displayArtist,
                        "track"  to song.title,
                        "album"  to song.album,
                    )
                ) ?: return@launch

                api.updateNowPlaying(
                    artist      = song.displayArtist,
                    track       = song.title,
                    album       = song.album.takeIf { it.isNotBlank() },
                    duration    = (song.duration / 1000).toInt(),
                    trackNumber = song.trackNumber.takeIf { it > 0 },
                    apiKey      = apiKey,
                    sessionKey  = sk,
                    apiSig      = sig,
                )
            } catch (e: Exception) {
                Log.w(TAG, "updateNowPlaying failed: ${e.message}")
            }
        }
    }

    fun scrobble(song: Song, startedAt: Long) {
        scope.launch {
            if (!isScrobbleReady()) return@launch
            pendingScrobbles.add(
                PendingScrobble(
                    artist      = song.displayArtist,
                    track       = song.title,
                    album       = song.album,
                    timestamp   = startedAt / 1000,
                    duration    = (song.duration / 1000).toInt(),
                    trackNumber = song.trackNumber,
                )
            )
            flushPendingScrobbles()
            persistPendingToDb()
        }
    }

    private suspend fun flushPendingScrobbles() {
        if (pendingScrobbles.isEmpty() || !isNetworkAllowedForScrobble()) return
        val toFlush = pendingScrobbles.toList()
        pendingScrobbles.clear()

        for (p in toFlush) {
            try {
                val (apiKey, sk, sig) = buildSignedParams(
                    "track.scrobble",
                    mapOf(
                        "artist[0]"    to p.artist,
                        "track[0]"     to p.track,
                        "timestamp[0]" to p.timestamp.toString(),
                        "album[0]"     to p.album,
                    )
                ) ?: continue

                val response = api.scrobble(
                    artist      = p.artist,
                    track       = p.track,
                    timestamp   = p.timestamp,
                    album       = p.album.takeIf { it.isNotBlank() },
                    duration    = p.duration.takeIf { it > 0 },
                    trackNumber = p.trackNumber.takeIf { it > 0 },
                    apiKey      = apiKey,
                    sessionKey  = sk,
                    apiSig      = sig,
                )

                if (response.error != null) {
                    Log.w(TAG, "Scrobble error ${response.error}: ${response.message}")
                    // 11 = service offline, 16 = temporarily unavailable — both are transient, re-queue.
                    // All other API errors (bad session, invalid params, etc.) are permanent; drop them.
                    if (response.error == 11 || response.error == 16) pendingScrobbles.add(p)
                } else {
                    Log.d(TAG, "Scrobbled: ${p.artist} – ${p.track}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Scrobble network error, re-queuing: ${e.message}")
                val offlineQueueEnabled = mainPrefs.lastFmScrobbleOffline.firstOrNull() ?: true
                if (offlineQueueEnabled) pendingScrobbles.add(p)
            }
        }
    }

    suspend fun loveTrack(song: Song): Result<Unit> = runCatching {
        val (apiKey, sk, sig) = buildSignedParams(
            "track.love",
            mapOf("artist" to song.displayArtist, "track" to song.title)
        ) ?: error("Not authenticated")
        val resp = api.loveTrack(
            artist = song.displayArtist, track = song.title,
            apiKey = apiKey, sessionKey = sk, apiSig = sig,
        )
        if (resp.error != null) error("Love failed: ${resp.message}")
    }

    suspend fun unloveTrack(song: Song): Result<Unit> = runCatching {
        val (apiKey, sk, sig) = buildSignedParams(
            "track.unlove",
            mapOf("artist" to song.displayArtist, "track" to song.title)
        ) ?: error("Not authenticated")
        val resp = api.unloveTrack(
            artist = song.displayArtist, track = song.title,
            apiKey = apiKey, sessionKey = sk, apiSig = sig,
        )
        if (resp.error != null) error("Unlove failed: ${resp.message}")
    }

    private suspend fun isScrobbleReady(): Boolean {
        val enabled = isEnabled.firstOrNull() ?: false
        val sk = getSessionKey()
        return enabled && sk.isNotBlank()
    }

        private suspend fun getCredentials(): Pair<String, String> {
        val prefs = store.data.firstOrNull()
        val apiKey    = prefs?.get(LastFmKeys.API_KEY)?.takeIf    { it.isNotBlank() } ?: DEFAULT_API_KEY
        val apiSecret = prefs?.get(LastFmKeys.API_SECRET)?.takeIf { it.isNotBlank() } ?: DEFAULT_API_SECRET
        return apiKey to apiSecret
    }

        private suspend fun buildSignedParams(
        method: String,
        extraParams: Map<String, String> = emptyMap(),
    ): Triple<String, String, String>? {
        val (apiKey, apiSecret) = getCredentials()
        val sk = getSessionKey().takeIf { it.isNotBlank() } ?: return null

        val allParams = buildMap {
            put("method",  method)
            put("api_key", apiKey)
            put("sk",      sk)
            putAll(extraParams)
        }
        return Triple(apiKey, sk, sign(allParams, apiSecret))
    }

        // Last.fm API signature: sorted key+value pairs concatenated, then MD5-hashed.
        private fun sign(params: Map<String, String>, secret: String): String {
        val payload = params.entries
            .sortedBy { it.key }
            .joinToString("") { (k, v) -> k + v } + secret
        return MessageDigest.getInstance("MD5")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    val pendingScrobbleCount: Int get() = pendingScrobbles.size

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    private suspend fun isNetworkAllowedForScrobble(): Boolean {
        val restrictToWifi = onlyWifi.firstOrNull() ?: false
        return if (restrictToWifi) isOnWifi() else true
    }
}