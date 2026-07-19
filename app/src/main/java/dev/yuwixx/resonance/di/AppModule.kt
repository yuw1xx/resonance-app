// Hilt dependency injection modules: database DAOs, Retrofit network clients,
// Coil image loader, and the Navidrome API provider.
package dev.yuwixx.resonance.di

import android.content.Context
import androidx.room.Room
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.BuildConfig
import dagger.hilt.components.SingletonComponent
import dev.yuwixx.resonance.data.database.MIGRATION_1_3
import dev.yuwixx.resonance.data.database.MIGRATION_2_3
import dev.yuwixx.resonance.data.database.MIGRATION_3_4
import dev.yuwixx.resonance.data.database.MIGRATION_4_5
import dev.yuwixx.resonance.data.database.MIGRATION_5_6
import dev.yuwixx.resonance.data.database.MIGRATION_6_7
import dev.yuwixx.resonance.data.database.MIGRATION_7_8
import dev.yuwixx.resonance.data.database.ResonanceDatabase
import dev.yuwixx.resonance.data.database.dao.*
import dev.yuwixx.resonance.data.network.DeezerApi
import dev.yuwixx.resonance.data.network.GitHubApi
import dev.yuwixx.resonance.data.network.LastFmApi
import dev.yuwixx.resonance.data.network.LrclibApi
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
            .addMigrations(MIGRATION_1_3, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
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
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Singleton @Provides
    fun provideMoshi(): Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

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

    @Singleton @Provides @Named("lrclib")
    fun provideLrclibRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder().baseUrl("https://lrclib.net/")
            .client(okHttp).addConverterFactory(MoshiConverterFactory.create(moshi)).build()

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
            .build()
}

@Module
@InstallIn(SingletonComponent::class)
object NavidromeModule {
    @Singleton @Provides
    fun provideNavidromeApiProvider(moshi: Moshi): NavidromeApiProvider =
        NavidromeApiProvider(moshi)
}
