// Hilt dependency injection modules: database DAOs, Retrofit network clients,
// Coil image loader, and the Navidrome API provider.
package dev.yuwixx.resonance.di

import android.content.Context
import android.net.Uri
import androidx.room.Room
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.yuwixx.resonance.BuildConfig
import dev.yuwixx.resonance.data.database.MIGRATION_1_3
import dev.yuwixx.resonance.data.database.MIGRATION_2_3
import dev.yuwixx.resonance.data.database.MIGRATION_3_4
import dev.yuwixx.resonance.data.database.MIGRATION_4_5
import dev.yuwixx.resonance.data.database.MIGRATION_5_6
import dev.yuwixx.resonance.data.database.MIGRATION_6_7
import dev.yuwixx.resonance.data.database.MIGRATION_7_8
import dev.yuwixx.resonance.data.database.MIGRATION_8_9
import dev.yuwixx.resonance.data.database.MIGRATION_9_10
import dev.yuwixx.resonance.data.database.ResonanceDatabase
import dev.yuwixx.resonance.data.database.dao.*
import dev.yuwixx.resonance.data.network.ApiRateLimiter
import dev.yuwixx.resonance.data.network.DeezerApi
import dev.yuwixx.resonance.data.network.DiscogsApi
import dev.yuwixx.resonance.data.network.GitHubApi
import dev.yuwixx.resonance.data.network.LastFmApi
import dev.yuwixx.resonance.data.network.LrclibApi
import dev.yuwixx.resonance.data.network.MusicBrainzApi
import dev.yuwixx.resonance.data.network.MusicBrainzInterceptor
import dev.yuwixx.resonance.data.network.NavidromeApiProvider
import dev.yuwixx.resonance.data.network.SpotifyApi
import dev.yuwixx.resonance.data.network.SpotifyAuthApi
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Singleton @Provides
    fun provideDatabase(@ApplicationContext ctx: Context): ResonanceDatabase =
        Room.databaseBuilder(ctx, ResonanceDatabase::class.java, "resonance.db")
            .addMigrations(MIGRATION_1_3, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
            .build()

    @Provides fun provideSongDao(db: ResonanceDatabase): SongDao = db.songDao()
    @Provides fun providePlaylistDao(db: ResonanceDatabase): PlaylistDao = db.playlistDao()
    @Provides fun provideHistoryDao(db: ResonanceDatabase): HistoryDao = db.historyDao()
    @Provides fun provideLyricsDao(db: ResonanceDatabase): LyricsDao = db.lyricsDao()
    @Provides fun provideArtworkDao(db: ResonanceDatabase): ArtworkDao = db.artworkDao()
    @Provides fun providePendingScrobbleDao(db: ResonanceDatabase): PendingScrobbleDao = db.pendingScrobbleDao()
    @Provides fun provideQueueDao(db: ResonanceDatabase): QueueDao = db.queueDao()
    @Provides fun provideLikedSongsDao(db: ResonanceDatabase): LikedSongsDao = db.likedSongsDao()
    @Provides fun provideNavidromeSongDao(db: ResonanceDatabase): NavidromeSongDao = db.navidromeSongDao()
    @Provides fun provideNavidromeAlbumDao(db: ResonanceDatabase): NavidromeAlbumDao = db.navidromeAlbumDao()
    @Provides fun provideMixNavidromeSongDao(db: ResonanceDatabase): MixNavidromeSongDao = db.mixNavidromeSongDao()
    @Provides fun provideSongDownloadDao(db: ResonanceDatabase): SongDownloadDao = db.songDownloadDao()
    @Provides fun providePendingStarActionDao(db: ResonanceDatabase): PendingStarActionDao = db.pendingStarActionDao()
    @Provides fun provideArtistInfoDao(db: ResonanceDatabase): ArtistInfoDao = db.artistInfoDao()
    @Provides fun provideSmartPlaylistDao(db: ResonanceDatabase): SmartPlaylistDao = db.smartPlaylistDao()
    @Provides fun providePlaylistSourceDao(db: ResonanceDatabase): PlaylistSourceDao = db.playlistSourceDao()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Singleton @Provides
    // Codegen adapters only (@JsonClass(generateAdapter = true) on every model): no
    // kotlin-reflect at runtime, faster first parse, and R8-safe.
    fun provideMoshi(): Moshi = Moshi.Builder().build()

    @Singleton @Provides
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    @Singleton @Provides @Named("github")
    fun provideGitHubRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder().baseUrl("https://api.github.com/")
            .client(okHttp).addConverterFactory(MoshiConverterFactory.create(moshi)).build()

    // A dedicated, shorter-timeout client — lyrics lookups happen on every song change and
    // should fail fast rather than block the player for up to 30s (15s connect + 15s read) on
    // the shared client if LRCLIB is slow or unreachable.
    @Singleton @Provides @Named("lrclib")
    fun provideLrclibRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit {
        // Derived from the shared client: same connection pool and threads, own timeouts.
        val client = okHttp.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            // Cloudflare in front of lrclib.net returns a 520 for requests with OkHttp's default
            // User-Agent (confirmed: identical request succeeds with any other UA) — give it a
            // descriptive one, same as the MusicBrainzInterceptor does for musicbrainz.org.
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Resonance/${BuildConfig.VERSION_NAME} (+https://github.com/yuw1xx/resonance-app)")
                    .build()
                chain.proceed(request)
            }
            .build()
        return Retrofit.Builder().baseUrl("https://lrclib.net/")
            .client(client).addConverterFactory(MoshiConverterFactory.create(moshi)).build()
    }

    @Singleton @Provides @Named("deezer")
    fun provideDeezerRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder().baseUrl("https://api.deezer.com/")
            .client(okHttp).addConverterFactory(MoshiConverterFactory.create(moshi)).build()

    @Singleton @Provides @Named("lastfm")
    fun provideLastFmRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder().baseUrl("https://ws.audioscrobbler.com/")
            .client(okHttp).addConverterFactory(MoshiConverterFactory.create(moshi)).build()

    @Singleton @Provides @Named("spotify-accounts")
    fun provideSpotifyAccountsRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder().baseUrl("https://accounts.spotify.com/")
            .client(okHttp).addConverterFactory(MoshiConverterFactory.create(moshi)).build()

    @Singleton @Provides @Named("spotify-api")
    fun provideSpotifyApiRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder().baseUrl("https://api.spotify.com/")
            .client(okHttp).addConverterFactory(MoshiConverterFactory.create(moshi)).build()

    // MusicBrainz gets its own dedicated OkHttpClient (not the shared singleton above) so the
    // User-Agent it requires is scoped only to MusicBrainz traffic.
    @Singleton @Provides @Named("musicbrainz")
    fun provideMusicBrainzRetrofit(okHttp: OkHttpClient, moshi: Moshi, mbInterceptor: MusicBrainzInterceptor): Retrofit {
        val client = okHttp.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(mbInterceptor)
            .build()
        return Retrofit.Builder().baseUrl("https://musicbrainz.org/ws/2/")
            .client(client).addConverterFactory(MoshiConverterFactory.create(moshi)).build()
    }

    @Singleton @Provides @Named("discogs")
    fun provideDiscogsRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder().baseUrl("https://api.discogs.com/")
            .client(okHttp).addConverterFactory(MoshiConverterFactory.create(moshi)).build()

    @Singleton @Provides
    fun provideGitHubApi(@Named("github") retrofit: Retrofit): GitHubApi =
        retrofit.create(GitHubApi::class.java)

    @Singleton @Provides
    fun provideLrclibApi(@Named("lrclib") retrofit: Retrofit): LrclibApi =
        retrofit.create(LrclibApi::class.java)

    @Singleton @Provides
    fun provideDeezerApi(@Named("deezer") retrofit: Retrofit): DeezerApi =
        retrofit.create(DeezerApi::class.java)

    @Singleton @Provides
    fun provideLastFmApi(@Named("lastfm") retrofit: Retrofit): LastFmApi =
        retrofit.create(LastFmApi::class.java)

    @Singleton @Provides
    fun provideSpotifyAuthApi(@Named("spotify-accounts") retrofit: Retrofit): SpotifyAuthApi =
        retrofit.create(SpotifyAuthApi::class.java)

    @Singleton @Provides
    fun provideSpotifyApi(@Named("spotify-api") retrofit: Retrofit): SpotifyApi =
        retrofit.create(SpotifyApi::class.java)

    @Singleton @Provides
    fun provideMusicBrainzApi(@Named("musicbrainz") retrofit: Retrofit): MusicBrainzApi =
        retrofit.create(MusicBrainzApi::class.java)

    @Singleton @Provides
    fun provideDiscogsApi(@Named("discogs") retrofit: Retrofit): DiscogsApi =
        retrofit.create(DiscogsApi::class.java)

    // MusicBrainz's usage policy caps unauthenticated traffic at ~1 request/second; Discogs'
    // unauthenticated tier is a looser 60/min, but reusing the same conservative interval keeps
    // this simple and avoids needing to special-case authenticated vs. unauthenticated Discogs
    // tokens.
    @Singleton @Provides @Named("musicbrainz-limiter")
    fun provideMusicBrainzRateLimiter(): ApiRateLimiter = ApiRateLimiter(minIntervalMs = 1100L)

    @Singleton @Provides @Named("discogs-limiter")
    fun provideDiscogsRateLimiter(): ApiRateLimiter = ApiRateLimiter(minIntervalMs = 1050L)
}

@Module
@InstallIn(SingletonComponent::class)
object ImageModule {
    @Singleton @Provides
    fun provideImageLoader(@ApplicationContext ctx: Context): ImageLoader =
        ImageLoader.Builder(ctx)
            .memoryCache { MemoryCache.Builder(ctx).maxSizePercent(0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(ctx.cacheDir.resolve("coil_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .crossfade(true)
            .components { add(StableSubsonicCacheKeys) }
            .build()
}

/**
 * Subsonic/Navidrome URLs carry per-request auth (`u`, `t`=token, `s`=salt, `p`) that changes
 * between sessions, so the same cover would be a cache miss every time. Key memory and disk
 * cache entries on the URL with those parameters removed.
 */
private object StableSubsonicCacheKeys : coil.intercept.Interceptor {
    private val authParams = setOf("u", "t", "s", "p")

    override suspend fun intercept(chain: coil.intercept.Interceptor.Chain): coil.request.ImageResult {
        val request = chain.request
        val url = request.data.toString()
        if (!url.startsWith("http") || "/rest/" !in url) return chain.proceed(request)
        val key = stableKey(url) ?: return chain.proceed(request)
        val keyed = request.newBuilder()
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .build()
        return chain.proceed(keyed)
    }

    fun stableKey(url: String): String? {
        val uri = Uri.parse(url)
        val names = uri.queryParameterNames.takeIf { it.isNotEmpty() } ?: return null
        val builder = uri.buildUpon().clearQuery()
        names.filter { it !in authParams }.sorted().forEach { name ->
            uri.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
        }
        return builder.build().toString()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NavidromeModule {
    @Singleton @Provides
    fun provideNavidromeApiProvider(moshi: Moshi, okHttp: OkHttpClient): NavidromeApiProvider =
        NavidromeApiProvider(moshi, okHttp)
}
