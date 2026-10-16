// Bridges MediaStore.createWriteRequest's system consent dialog (needed to write scoped-storage
// files this app doesn't own — e.g. a song it didn't itself create) between AudioTagWriter, which
// has no Activity/launcher of its own, and the single ActivityResultLauncher MainActivity
// registers on startup. There's only one Activity in this app, so one bound launcher is enough;
// concurrent requests are serialized behind a Mutex rather than racing two deferreds against one
// launcher/callback.
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
            // Resolve any in-flight request before clearing the launcher — otherwise, if the
            // Activity is destroyed before the system dialog's result callback fires, the
            // coroutine awaiting `pending` in requestWriteAccess() stays suspended forever,
            // permanently holding the Mutex (this is a @Singleton) and hanging every future call.
            pending?.complete(false)
            pending = null
            this.launcher = null
        }
    }

    fun onResult(granted: Boolean) {
        pending?.complete(granted)
        pending = null
    }

    // Returns false immediately (no dialog shown) if no launcher is currently bound (e.g. the
    // Activity was destroyed mid-request) — callers already treat "not granted" as "write
    // failed", so this degrades the same way a user-denied prompt would.
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
