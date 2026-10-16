// Application entry point: wires Hilt's WorkManager factory, kicks off the auto-scan schedule,
// and pre-configures the Navidrome API client from saved credentials.
package dev.yuwixx.resonance

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.yuwixx.resonance.data.network.NavidromeApiProvider
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.data.worker.AutoScanManager
import dev.yuwixx.resonance.data.worker.MixGeneratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ResonanceApp : Application(), Configuration.Provider, coil.ImageLoaderFactory {
    // AsyncImage/rememberAsyncImagePainter use Coil's singleton loader; hand it the configured
    // one (cache sizes, stable Navidrome cache keys) instead of a default per-process loader.
    @Inject lateinit var imageLoader: coil.ImageLoader
    override fun newImageLoader(): coil.ImageLoader = imageLoader

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var autoScanManager: AutoScanManager
    @Inject lateinit var mixGeneratorManager: MixGeneratorManager
    @Inject lateinit var navidromeApiProvider: NavidromeApiProvider
    @Inject lateinit var prefs: ResonancePreferences

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        autoScanManager.initialize()
        mixGeneratorManager.scheduleWeekly()
        applicationScope.launch {
            // Only set up the client here; syncing (stars and playlists included) happens when
            // the UI starts, not on every process start (widget, Android Auto, media buttons).
            navidromeApiProvider.initFromPrefs(prefs)
        }
    }
}