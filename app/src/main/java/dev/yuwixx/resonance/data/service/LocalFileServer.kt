package dev.yuwixx.resonance.data.service

import kotlinx.coroutines.*
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalFileServer @Inject constructor() {

    private var serverSocket: ServerSocket? = null
    private var serveJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class ServeHandle(val token: String, val port: Int)

    suspend fun serve(
        file      : File,
        mimeType  : String,
        onProgress: (Float) -> Unit,
        onDone    : () -> Unit,
        onRejected: () -> Unit,
        timeoutMs : Long = 5 * 60_000L,
    ): ServeHandle = withContext(Dispatchers.IO) {
        stop()

        val token  = UUID.randomUUID().toString().replace("-", "")
        val socket = ServerSocket(0).also {
            it.soTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            serverSocket = it
        }

        serveJob = scope.launch {
            try {
                val client = socket.accept()
                handleConnection(client, token, file, mimeType, onProgress, onDone, onRejected)
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
            } finally {
                stop()
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

    private fun handleConnection(
        client    : java.net.Socket,
        expected  : String,
        file      : File,
        mimeType  : String,
        onProgress: (Float) -> Unit,
        onDone    : () -> Unit,
        onRejected: () -> Unit,
    ) {
        client.use {
            it.soTimeout = 30_000
            val input  = it.getInputStream()
            val output = it.getOutputStream()

            val reader = input.bufferedReader()

            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1)?.trimStart('/') ?: return

            if (path == "reject?token=$expected") {
                var line: String?
                do { line = reader.readLine() } while (!line.isNullOrBlank())

                output.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                onRejected()
                return
            }

            if (path != expected) {
                output.write(
                    "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                output.flush()
                return
            }

            var line: String?
            do { line = reader.readLine() } while (!line.isNullOrBlank())

            val fileSize = file.length()
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: ${mimeType.ifBlank { "application/octet-stream" }}\r\n")
                append("Content-Length: $fileSize\r\n")
                append("Content-Disposition: attachment; filename=\"${file.name}\"\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            output.write(headers.toByteArray())

            file.inputStream().buffered(64 * 1024).use { fis ->
                val buf  = ByteArray(64 * 1024)
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
        }
    }
}