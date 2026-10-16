package dev.yuwixx.resonance.cast

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.model.Song
import fi.iki.elonen.NanoHTTPD
import java.io.FileInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.channels.Channels
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serves local songs and their artwork to a Cast receiver on the LAN while casting.
 *
 * Every URL carries a random token that changes each time the server starts, so other devices
 * on the network can't fetch files by guessing song IDs.
 */
@Singleton
class LocalAudioServer @Inject constructor(
    @ApplicationContext private val context: Context,
) : NanoHTTPD(PORT) {

    companion object {
        const val PORT = 9753
        private const val TAG = "LocalAudioServer"
    }

    private val songMap = mutableMapOf<Long, Song>()
    @Volatile private var token: String = newToken()

    fun registerSongs(songs: List<Song>) {
        synchronized(songMap) {
            songMap.clear()
            songMap.putAll(songs.associateBy { it.id })
        }
    }

    fun getUrl(song: Song): String = "http://${localIp()}:$PORT/$token/audio/${song.id}"

    fun getArtworkUrl(song: Song): String? {
        if (song.artworkUri == null) return null
        return "http://${localIp()}:$PORT/$token/artwork/${song.id}"
    }

    fun safeStart() {
        try {
            if (!isAlive) {
                token = newToken()
                start(SOCKET_READ_TIMEOUT, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local server", e)
        }
    }

    fun safeStop() {
        try {
            stop()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop local server", e)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.removePrefix("/$token/")
        if (path == session.uri) return notFound()

        val kind = path.substringBefore('/')
        val song = path.substringAfter('/').toLongOrNull()
            ?.let { id -> synchronized(songMap) { songMap[id] } }
            ?: return notFound()

        return when (kind) {
            "audio" -> serveAudio(song, session.headers["range"])
            "artwork" -> serveArtwork(song.artworkUri)
            else -> notFound()
        }
    }

    private fun serveAudio(song: Song, rangeHeader: String?): Response {
        val mimeType = song.mimeType.ifBlank { "audio/mpeg" }
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(song.uri, "r")
                ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Cannot open file")
            val fileSize = pfd.statSize
            val range = rangeHeader?.let { parseRange(it, fileSize) }

            if (rangeHeader != null && range == null) {
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                    .apply { addHeader("Content-Range", "bytes */$fileSize") }
            }

            val channel = FileInputStream(pfd.fileDescriptor).channel
            val start = range?.first ?: 0L
            channel.position(start)
            val input = closingWith(pfd, Channels.newInputStream(channel))
            pfd = null // closed together with the stream from here on

            if (range == null) {
                newFixedLengthResponse(Response.Status.OK, mimeType, input, fileSize)
            } else {
                newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, input, range.last - start + 1)
                    .apply { addHeader("Content-Range", "bytes $start-${range.last}/$fileSize") }
            }.apply { addHeader("Accept-Ranges", "bytes") }
        } catch (e: Exception) {
            pfd?.close()
            Log.e(TAG, "Error serving song ${song.id}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error")
        }
    }

    /** Parses one "bytes=a-b", "bytes=a-" or "bytes=-n" range; null if it can't be served. */
    private fun parseRange(header: String, size: Long): LongRange? {
        val spec = header.removePrefix("bytes=").takeIf { it != header && ',' !in it } ?: return null
        val from = spec.substringBefore('-')
        val to = spec.substringAfter('-', "")
        val range = when {
            from.isEmpty() -> to.toLongOrNull()?.takeIf { it > 0 }?.let { n -> (size - n).coerceAtLeast(0)..<size }
            else -> {
                val start = from.toLongOrNull() ?: return null
                val end = if (to.isEmpty()) size - 1 else to.toLongOrNull()?.coerceAtMost(size - 1) ?: return null
                start..end
            }
        }
        return range?.takeIf { size > 0 && it.first in 0..<size && it.last >= it.first }
    }

    private fun serveArtwork(artUri: Uri?): Response {
        artUri ?: return notFound()
        return try {
            val bytes = context.contentResolver.openInputStream(artUri)?.use { it.readBytes() }
                ?: return notFound()
            newFixedLengthResponse(Response.Status.OK, "image/jpeg", bytes.inputStream(), bytes.size.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Error serving artwork", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error")
        }
    }

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")

    // Closing the returned stream also closes the file descriptor.
    private fun closingWith(pfd: ParcelFileDescriptor, inner: InputStream): InputStream =
        object : InputStream() {
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
            override fun close() {
                try { inner.close() } finally { pfd.close() }
            }
        }

    private fun newToken(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // The Wi-Fi address, so the Cast device on the same LAN can reach us.
    private fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress ?: "127.0.0.1"
    } catch (e: Exception) {
        "127.0.0.1"
    }
}
