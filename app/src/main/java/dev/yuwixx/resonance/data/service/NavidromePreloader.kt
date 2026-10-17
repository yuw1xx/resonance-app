package dev.yuwixx.resonance.data.service

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads the start of upcoming Navidrome streams into the player's stream cache, so the next
 * track starts without waiting for the network.
 */
@OptIn(UnstableApi::class)
@Singleton
class NavidromePreloader @Inject constructor(cache: SimpleCache) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warmed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var job: Job? = null

    private val dataSourceFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())

    fun preload(urls: List<String>) {
        val toWarm = urls.filterNot { it in warmed }
        if (toWarm.isEmpty()) return
        job?.cancel()
        job = scope.launch {
            for (url in toWarm) {
                ensureActive()
                try {
                    val spec = DataSpec.Builder().setUri(Uri.parse(url)).setLength(PRELOAD_BYTES).build()
                    CacheWriter(dataSourceFactory.createDataSource(), spec, null, null).cache()
                    warmed += url
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Preload failed for ${url.substringBefore('?')}: ${e.message}")
                }
            }
        }
    }

    fun reset() {
        job?.cancel()
        warmed.clear()
    }

    private companion object {
        const val TAG = "NavidromePreloader"
        const val PRELOAD_BYTES = 512L * 1024
    }
}
