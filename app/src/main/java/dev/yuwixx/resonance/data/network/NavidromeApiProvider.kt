package dev.yuwixx.resonance.data.network

import com.squareup.moshi.Moshi
import dev.yuwixx.resonance.BuildConfig
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavidromeApiProvider @Inject constructor(
    private val moshi: Moshi,
) {
    @Volatile private var api: NavidromeApi? = null
    @Volatile private var currentServerUrl: String? = null
    @Volatile private var currentUsername: String? = null
    @Volatile private var currentPassword: String? = null

        fun currentApi(): NavidromeApi? = api

        data class Credentials(val serverUrl: String, val username: String, val password: String)
    fun currentCredentials(): Credentials? {
        val url  = currentServerUrl ?: return null
        val user = currentUsername  ?: return null
        val pass = currentPassword  ?: return null
        return Credentials(url, user, pass)
    }

        suspend fun initFromPrefs(prefs: ResonancePreferences) {
        val url  = prefs.navidromeServerUrl.first() ?: return
        val user = prefs.navidromeUsername.first()   ?: return
        val pass = prefs.getNavidromePassword()       ?: return
        rebuild(url, user, pass)
    }

        fun serverUrl(): String? = currentServerUrl

        fun rebuild(serverUrl: String, username: String, password: String): NavidromeApi {
        val normalizedUrl = serverUrl.trimEnd('/') + "/"
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(NavidromeAuthInterceptor(username, password))
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            })
            .build()

        val newApi = Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(NavidromeApi::class.java)

        api = newApi
        currentServerUrl = normalizedUrl
        currentUsername = username
        currentPassword = password
        return newApi
    }

        fun buildApi(serverUrl: String, username: String, password: String): NavidromeApi {
        val normalizedUrl = serverUrl.trimEnd('/') + "/"
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(NavidromeAuthInterceptor(username, password))
            .build()

        return Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(NavidromeApi::class.java)
    }
}
