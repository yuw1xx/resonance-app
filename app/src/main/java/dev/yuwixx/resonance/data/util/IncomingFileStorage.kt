// Saves audio files received via Resonance Share (Nearby or LAN/P2P) into Music/Resonance.
// On API 29+ scoped storage blocks direct File writes into the public Music directory unless
// MANAGE_EXTERNAL_STORAGE ("All files access") is granted — which this app never requests — so
// writes must go through MediaStore instead.
package dev.yuwixx.resonance.data.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.InputStream

object IncomingFileStorage {

    fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
            .trim()
            .ifBlank { "Unknown" }
            .take(200)

    fun saveIncoming(context: Context, input: InputStream, title: String, ext: String, mimeType: String): File {
        val fileName = "${sanitizeFileName(title)}.${ext.ifBlank { "mp3" }}"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType.ifBlank { "audio/mpeg" })
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Resonance")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(collection, values)
                ?: throw IOException("Could not create MediaStore entry")

            try {
                resolver.openOutputStream(itemUri)?.use { out -> input.copyTo(out) }
                    ?: throw IOException("Could not open output stream")
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(itemUri, null, null)
                throw e
            }

            // MediaStore silently renames on a DISPLAY_NAME collision (e.g. "Title (1).mp3"),
            // so read back the name it actually assigned rather than assuming it matches fileName.
            val actualName = resolver.query(itemUri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: fileName

            File(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Resonance"),
                actualName
            )
        } else {
            val destDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "Resonance"
            ).also { it.mkdirs() }
            val destFile = File(destDir, fileName)
            destFile.outputStream().use { out -> input.copyTo(out) }
            MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
            destFile
        }
    }
}
