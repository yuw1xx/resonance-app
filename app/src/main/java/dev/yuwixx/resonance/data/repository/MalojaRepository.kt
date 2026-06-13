package dev.yuwixx.resonance.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.BuildConfig
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.network.MalojaApi
import dev.yuwixx.resonance.data.network.MalojaScrobbleRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.malojaStore: DataStore<Preferences> by preferencesDataStore(name = "maloja_prefs")

private object MalojaKeys {
    val ENABLED    = booleanPreferencesKey("enabled")
    val SERVER_URL = stringPreferencesKey("server_url")
    val API_KEY    = stringPreferencesKey("api_key")
}

private data class PendingMalojaScrobble(
    val artists: List<String>,
    val title: String,
    val album: String?,
    val length: Int?,
    val time: Long,
)

@Singleton
class MalojaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moshi: Moshi,
) {
    private val store = context.malojaStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = java.util.concurrent.CopyOnWriteArrayList<PendingMalojaScrobble>()
    private val flushMutex = Mutex()

    val isEnabled: Flow<Boolean> = store.data.map { it[MalojaKeys.ENABLED] ?: false }
    val serverUrl: Flow<String>  = store.data.map { it[MalojaKeys.SERVER_URL] ?: "" }
    val apiKey: Flow<String>     = store.data.map { it[MalojaKeys.API_KEY] ?: "" }

    @Volatile private var currentApi: MalojaApi? = null
    @Volatile private var currentApiUrl: String = ""

    init {
        scope.launch {
            serverUrl.collect { url ->
                currentApi = if (url.isBlank()) null else buildApi(url)
                currentApiUrl = url
            }
        }
    }

    suspend fun setEnabled(v: Boolean)  { store.edit { it[MalojaKeys.ENABLED]    = v } }
    suspend fun setServerUrl(v: String) { store.edit { it[MalojaKeys.SERVER_URL] = v } }
    suspend fun setApiKey(v: String)    { store.edit { it[MalojaKeys.API_KEY]    = v } }

    suspend fun configure(url: String, key: String) {
        store.edit {
            it[MalojaKeys.SERVER_URL] = url.trim()
            it[MalojaKeys.API_KEY]    = key.trim()
            it[MalojaKeys.ENABLED]    = true
        }
    }

    suspend fun clear() {
        store.edit {
            it.remove(MalojaKeys.SERVER_URL)
            it.remove(MalojaKeys.API_KEY)
            it[MalojaKeys.ENABLED] = false
        }
        currentApi = null
        currentApiUrl = ""
    }

    val pendingCount: Int get() = pending.size

    fun scrobble(song: Song, startedAt: Long) {
        scope.launch {
            if (!isReady()) return@launch
            pending.add(
                PendingMalojaScrobble(
                    artists = song.artists.ifEmpty { listOf(song.artist) },
                    title   = song.title,
                    album   = song.album.takeIf { it.isNotBlank() },
                    length  = (song.duration / 1000).toInt().takeIf { it > 0 },
                    time    = startedAt / 1000,
                )
            )
            flush()
        }
    }

    suspend fun testConnection(url: String, key: String): Result<String> = runCatching {
        if (url.isBlank()) error("Server URL is empty")
        if (key.isBlank()) error("API key is empty")
        val api = buildApi(url)
        val resp = api.ping(key = key)
        when {
            resp.status == "success" || resp.status == "ok" -> resp.desc ?: "Connected"
            resp.status == "error" -> error(resp.desc ?: "Server returned an error")
            else -> error("Unexpected response: ${resp.status}")
        }
    }

    private suspend fun isReady(): Boolean {
        if (currentApi == null) return false
        val enabled = isEnabled.firstOrNull() ?: false
        val key = apiKey.firstOrNull() ?: ""
        return enabled && key.isNotBlank()
    }

    private suspend fun flush() {
        if (pending.isEmpty()) return
        flushMutex.withLock {
            if (pending.isEmpty()) return
            val api = currentApi ?: return
            val key = apiKey.firstOrNull() ?: return
            if (key.isBlank()) return

            val toFlush = pending.toList()
            pending.clear()

            for (p in toFlush) {
                try {
                    val resp = api.scrobble(
                        MalojaScrobbleRequest(
                            artists = p.artists,
                            title   = p.title,
                            album   = p.album,
                            length  = p.length,
                            time    = p.time,
                            key     = key,
                        )
                    )
                    if (resp.status == "success" || resp.status == "ok") {
                        Log.d(TAG, "Scrobbled: ${p.artists.joinToString(", ")} — ${p.title}")
                    } else {
                        Log.w(TAG, "Scrobble rejected: ${resp.status} — ${resp.desc}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Scrobble failed, re-queuing: ${e.message}")
                    pending.add(p)
                }
            }
        }
    }

    private fun buildApi(baseUrl: String): MalojaApi {
        val normalized = baseUrl.trim().trimEnd('/') + "/"
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            })
            .build()
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(MalojaApi::class.java)
    }

    companion object {
        private const val TAG = "MalojaRepo"
    }
}
