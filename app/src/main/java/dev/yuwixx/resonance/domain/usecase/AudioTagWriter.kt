package dev.yuwixx.resonance.domain.usecase

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.service.MediaStoreWriteBroker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The one place tags are written to audio files. */
@Singleton
class AudioTagWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val writeBroker: MediaStoreWriteBroker,
) {
    // Writes only the given fields and leaves the rest of the tag alone.
    //
    // Scoped storage often blocks writes to files other apps created. Then, on API 30+, it asks
    // for write access through the system dialog and retries once.
    suspend fun writeTags(path: String, uri: Uri?, fields: Map<FieldKey, String>): Boolean =
        withContext(Dispatchers.IO) {
            if (tryWrite(path, fields)) return@withContext true

            if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val granted = writeBroker.requestWriteAccess(context, listOf(uri))
                if (granted && tryWrite(path, fields)) return@withContext true
            }
            false
        }

    private fun tryWrite(path: String, fields: Map<FieldKey, String>): Boolean {
        return try {
            val file = File(path)
            if (!file.exists() || !file.canWrite()) return false
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tagOrCreateAndSetDefault
            fields.forEach { (key, value) -> tag.setField(key, value) }
            AudioFileIO.write(audioFile)
            true
        } catch (e: Exception) {
            Log.e("AudioTagWriter", "Failed to write tags for $path", e)
            false
        }
    }
}
