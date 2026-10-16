// Disk cache for streamed audio (Navidrome). Stream URLs are stable per song, so a replay or a
// seek back into an already-played part is served from disk instead of downloading again.
// Local files bypass it (CacheDataSource only caches network sources it is asked to read).
package dev.yuwixx.resonance.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaCacheModule {
    private const val MAX_BYTES = 512L * 1024 * 1024

    // SimpleCache allows only one instance per directory per process.
    @OptIn(UnstableApi::class)
    @Provides
    @Singleton
    fun provideStreamCache(@ApplicationContext context: Context): SimpleCache =
        SimpleCache(
            File(context.cacheDir, "stream_cache"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context),
        )
}
