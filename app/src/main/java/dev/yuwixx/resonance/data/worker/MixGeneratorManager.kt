package dev.yuwixx.resonance.data.worker

import android.content.Context
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MixGeneratorManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    fun scheduleWeekly() {
        val request = PeriodicWorkRequestBuilder<MixGeneratorWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun triggerNow() {
        val request = OneTimeWorkRequestBuilder<MixGeneratorWorker>().build()
        workManager.enqueue(request)
    }

    companion object {
        const val WORK_NAME = "resonance_mix_generator"
    }
}
