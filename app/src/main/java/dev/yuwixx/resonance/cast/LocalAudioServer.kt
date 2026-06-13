package dev.yuwixx.resonance.cast

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.model.Song
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.channels.Channels
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalAudioServer @Inject constructor(
    @ApplicationContext private val context: Context,
) : NanoHTTPD(PORT) {

    companion object {
        const val PORT = 9753
        private const val TAG = "LocalAudioServer"
    }

    private val songMap = mutableMapOf<Long, Song>()
    @Volatile private var currentArtworkUri: android.net.Uri? = null

    fun registerSongs(songs: List<Song>) {
        synchronized(songMap) {
            songMap.clear()
            songMap.putAll(songs.associateBy { it.id })
        }
    }

    fun getUrl(song: Song): String = "http://${localIp()}:$PORT/audio/${song.id}"

    fun trackCurrentSong(song: Song) {
        currentArtworkUri = song.artworkUri
    }

    fun getArtworkUrl(song: Song): String? {
        if (song.artworkUri == null) return null
        return "http://${localIp()}:$PORT/artwork/${song.id}"
    }

    fun safeStart() {
        try {
            if (!isAlive) start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local server", e)
        }
    }

    fun safeStop() {
        try { stop() } catch (_: Exception) {}
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/artwork/current") return serveArtworkUri(currentArtworkUri)
        if (session.uri.startsWith("/artwork/")) return serveArtwork(session)

        val songId = session.uri.removePrefix("/audio/").toLongOrNull()
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")

        val song = synchronized(songMap) { songMap[songId] }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Song not registered")

        val mimeType = song.mimeType.ifBlank { "audio/mpeg" }

        var pfd: android.os.ParcelFileDescriptor? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(song.uri, "r")
                ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Cannot open file")
            val fileSize = pfd.statSize

            val rangeHeader = session.headers["range"]
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val rangePart = rangeHeader.removePrefix("bytes=")
                val parts = rangePart.split("-")
                val start = parts[0].toLongOrNull() ?: 0L
                val end = parts.getOrNull(1)?.toLongOrNull() ?: (fileSize - 1)
                val length = end - start + 1

                val channel = java.io.FileInputStream(pfd.fileDescriptor).channel
                channel.position(start)
                val inputStream = pfdWrapped(pfd, Channels.newInputStream(channel))
                pfd = null // ownership transferred to the wrapped stream

                val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, inputStream, length)
                response.addHeader("Content-Range", "bytes $start-$end/$fileSize")
                response.addHeader("Accept-Ranges", "bytes")
                response
            } else {
                val channel = java.io.FileInputStream(pfd.fileDescriptor).channel
                val inputStream = pfdWrapped(pfd, Channels.newInputStream(channel))
                pfd = null // ownership transferred to the wrapped stream

                val response = newFixedLengthResponse(Response.Status.OK, mimeType, inputStream, fileSize)
                response.addHeader("Accept-Ranges", "bytes")
                response
            }
        } catch (e: Exception) {
            pfd?.close()
            Log.e(TAG, "Error serving song $songId", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun serveArtwork(session: IHTTPSession): Response {
        val songId = session.uri.removePrefix("/artwork/").toLongOrNull()
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        val song = synchronized(songMap) { songMap[songId] }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Song not registered")
        return serveArtworkUri(song.artworkUri)
    }

    private fun serveArtworkUri(artUri: android.net.Uri?): Response {
        artUri ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No artwork")
        return try {
            val bytes = context.contentResolver.openInputStream(artUri)?.use { it.readBytes() }
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Cannot open artwork")
            newFixedLengthResponse(Response.Status.OK, "image/jpeg", bytes.inputStream(), bytes.size.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Error serving artwork", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    // Wraps an InputStream so that closing it also closes the ParcelFileDescriptor.
    private fun pfdWrapped(pfd: android.os.ParcelFileDescriptor, inner: java.io.InputStream): java.io.InputStream =
        object : java.io.InputStream() {
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
            override fun close() {
                try { inner.close() } finally { pfd.close() }
            }
        }

    private fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            // Prefer the Wi-Fi interface so Cast (same LAN) gets the right address.
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress ?: "127.0.0.1"
    } catch (_: Exception) {
        "127.0.0.1"
    }
}
