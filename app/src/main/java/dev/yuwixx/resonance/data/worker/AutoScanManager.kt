package dev.yuwixx.resonance.data.worker

import android.content.Context
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoScanManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: ResonancePreferences
) {
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize() {
        scope.launch {
            prefs.autoScanIntervalHours.collect { hours ->
                if (hours > 0) {
                    scheduleWork(hours)
                } else {
                    cancelWork()
                }
            }
        }
    }

    private fun scheduleWork(hours: Int) {
        val constraints = Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<AutoScanWorker>(
            hours.toLong(), TimeUnit.HOURS,
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun cancelWork() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    companion object {
        private const val WORK_NAME = "resonance_auto_scan"
    }
}
