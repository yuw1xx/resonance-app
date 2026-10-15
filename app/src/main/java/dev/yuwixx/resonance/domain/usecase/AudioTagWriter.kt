// Shared physical tag-write path (jaudiotagger direct file I/O) used by MusicRepository
// (single-song Tag Editor screen + batch tag editing) and TagEditor (metadata enrichment) — a
// single implementation instead of two independently-maintained ones.
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

@Singleton
class AudioTagWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val writeBroker: MediaStoreWriteBroker,
) {
    // Writes only the given fields, leaving everything else in the file's tag untouched (batch
    // tag editing relies on this to only touch fields the user actually filled in).
    //
    // If the file isn't directly writable — common under scoped storage for songs this app
    // didn't create itself, e.g. anything synced in by another app — requests one-time
    // MediaStore write access via the system consent dialog and retries once if granted, instead
    // of silently doing nothing. That fallback needs API 30+ (MediaStore.createWriteRequest) and
    // a URI to request access to; on older versions, or when uri is null, a non-writable file
    // just fails as it always did.
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
