package dev.yuwixx.resonance.data.service

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands MediaStore write-consent requests from AudioTagWriter to the launcher MainActivity
 * registers. There is only one launcher, so requests are serialized with a Mutex.
 */
@Singleton
class MediaStoreWriteBroker @Inject constructor() {
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private val mutex = Mutex()
    private var pending: CompletableDeferred<Boolean>? = null

    fun bind(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        this.launcher = launcher
    }

    fun unbind(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        if (this.launcher === launcher) {
            // Complete a pending request first, or its caller would wait forever holding the Mutex.
            pending?.complete(false)
            pending = null
            this.launcher = null
        }
    }

    fun onResult(granted: Boolean) {
        pending?.complete(granted)
        pending = null
    }

    // Returns false without a dialog when no launcher is bound, the same as a denied prompt.
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun requestWriteAccess(context: Context, uris: List<Uri>): Boolean = mutex.withLock {
        val activeLauncher = launcher ?: return@withLock false
        val pendingIntent = MediaStore.createWriteRequest(context.contentResolver, uris)
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        // Activity result launchers must be used on the main thread.
        withContext(Dispatchers.Main) {
            activeLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        }
        deferred.await()
    }
}
