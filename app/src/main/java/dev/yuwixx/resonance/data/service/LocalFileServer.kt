package dev.yuwixx.resonance.data.service

import dev.yuwixx.resonance.data.util.runCatchingCancellable
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*

@Singleton
class LocalFileServer @Inject constructor() {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var serveJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class ServeHandle(val token: String, val port: Int)

    suspend fun serve(
        file: File,
        mimeType: String,
        onProgress: (Float) -> Unit,
        onDone: () -> Unit,
        onRejected: () -> Unit,
        timeoutMs: Long = 5 * 60_000L,
    ): ServeHandle = withContext(Dispatchers.IO) {
        stop()

        val token = UUID.randomUUID().toString().replace("-", "")
        val socket = ServerSocket(0).also {
            it.soTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            serverSocket = it
        }

        // Keeps accepting until the real transfer (or a reject) happens, so a stray request
        // with the wrong token (a favicon fetch, a port scan) doesn't end the share.
        serveJob = scope.launch {
            val deadline = System.currentTimeMillis() + timeoutMs
            try {
                while (isActive) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val client = socket.accept()
                    val outcome = try {
                        handleConnection(client, token, file, mimeType, onProgress, onDone, onRejected)
                    } catch (_: IOException) {
                        Outcome.Ignored // receiver dropped mid-request; let it retry
                    }
                    if (outcome != Outcome.Ignored) break
                }
            } catch (_: SocketTimeoutException) {
                // Nobody came in time; the share expires.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            } finally {
                // Only this share's socket: a newer serve() may already have its own.
                runCatchingCancellable { socket.close() }
                if (serverSocket === socket) serverSocket = null
            }
        }

        ServeHandle(token = token, port = socket.localPort)
    }

    fun stop() {
        serveJob?.cancel()
        serveJob = null
        serverSocket?.runCatching { close() }
        serverSocket = null
    }

    private enum class Outcome { Served, Rejected, Ignored }

    private fun handleConnection(
        client: Socket,
        expected: String,
        file: File,
        mimeType: String,
        onProgress: (Float) -> Unit,
        onDone: () -> Unit,
        onRejected: () -> Unit,
    ): Outcome {
        return client.use {
            it.soTimeout = 30_000
            val input = it.getInputStream()
            val output = it.getOutputStream()

            val reader = input.bufferedReader()

            val requestLine = reader.readLine() ?: return Outcome.Ignored
            val path = requestLine.split(" ").getOrNull(1)?.trimStart('/') ?: return Outcome.Ignored

            if (path == "reject?token=$expected") {
                var line: String?
                do { line = reader.readLine() } while (!line.isNullOrBlank())

                output.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                onRejected()
                return Outcome.Rejected
            }

            if (path != expected) {
                output.write(
                    "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                output.flush()
                return Outcome.Ignored
            }

            var line: String?
            do { line = reader.readLine() } while (!line.isNullOrBlank())

            val fileSize = file.length()
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: ${mimeType.ifBlank { "application/octet-stream" }}\r\n")
                append("Content-Length: $fileSize\r\n")
                append("Content-Disposition: attachment; filename=\"${file.name.replace(Regex("[\"\\r\\n]"), "_")}\"\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            output.write(headers.toByteArray())

            file.inputStream().buffered(64 * 1024).use { fis ->
                val buf = ByteArray(64 * 1024)
                var sent = 0L
                var n: Int
                while (fis.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    sent += n
                    onProgress((sent.toFloat() / fileSize).coerceIn(0f, 1f))
                }
            }
            output.flush()
            onDone()
            Outcome.Served
        }
    }
}