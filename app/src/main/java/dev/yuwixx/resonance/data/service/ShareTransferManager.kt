package dev.yuwixx.resonance.data.service

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val MAX_CONCURRENT_UPLOADS = 4

/** Wraps a RequestBody to report write progress (0f..1f) as it streams to the network. */
private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun isOneShot(): Boolean = delegate.isOneShot()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var written = 0L
        val countingSink = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
            }
        }
        val bufferedSink = countingSink.buffer()
        delegate.writeTo(bufferedSink)
        // Without this, any bytes still sitting in bufferedSink's internal buffer never
        // reach the network — the server sees fewer bytes than Content-Length promised and
        // the connection dies mid-stream ("unexpected end of stream").
        bufferedSink.flush()
    }
}

const val MAX_REMOTE_TTL_HOURS = 72

@Singleton
class ShareTransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileServer: LocalFileServer,
    private val prefs: ResonancePreferences,
    private val okHttpClient: OkHttpClient,
) {

    sealed class TransferState {
        data object Idle      : TransferState()
        data object Preparing : TransferState()
        /** Uploading to the internet relay. [completedSongs]/[totalSongs] are 0/1 for a single song. */
        data class Uploading(val progress: Float, val completedSongs: Int = 0, val totalSongs: Int = 1) : TransferState()
        data class Ready(val qrContent: String, val mode: String) : TransferState()
        data class Serving(val progress: Float) : TransferState()
        data object Done      : TransferState()
        data object Rejected  : TransferState()
        data object NoWifi    : TransferState()
        data class Error(val message: String) : TransferState()
    }

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prepareJob: Job? = null

    // The injected OkHttpClient is shared app-wide and tuned for small API calls (15s
    // connect/read, OkHttp's 10s default write timeout) — nowhere near enough to upload a
    // multi-megabyte audio file over a typical home connection. Relay uploads/manifest
    // calls need their own generous timeouts.
    private val relayClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(2, TimeUnit.MINUTES)
            .build()
    }

    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null

    private fun ensureP2pInitialized(): Boolean {
        if (p2pManager != null && p2pChannel != null) return true

        return try {
            val appContext = context.applicationContext
            val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (manager == null) return false

            val channel = manager.initialize(appContext, Looper.getMainLooper()) {
                if (_state.value is TransferState.Ready || _state.value is TransferState.Serving) {
                    cleanupResources()
                    _state.value = TransferState.Error("Wi-Fi Direct disconnected")
                }
            }

            p2pManager = manager
            p2pChannel = channel
            true
        } catch (e: Exception) {
            _state.value = TransferState.Error("Failed to initialize Wi-Fi Direct: ${e.message}")
            false
        }
    }

    fun prepareTransfer(song: Song) {
        if (_state.value is TransferState.Preparing) return
        _state.value = TransferState.Preparing

        prepareJob?.cancel()
        prepareJob = scope.launch {
            val scheme = song.uri.scheme ?: ""
            if (scheme == "http" || scheme == "https") {
                _state.value = TransferState.Error("This song is streamed from a remote server and can't be shared as a file.")
                return@launch
            }
            val file = File(song.path)
            if (!file.exists()) {
                _state.value = TransferState.Error("Audio file not found at ${song.path}")
                return@launch
            }
            when {
                isOnWifi()       -> prepareLan(song, file)
                isWifiEnabled()  -> prepareP2p(song, file)
                else             -> _state.value = TransferState.NoWifi
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun cleanupResources() {
        prepareJob?.cancel()
        prepareJob = null
        fileServer.stop()
        val channel = p2pChannel
        if (channel != null) {
            p2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        }
    }

    fun cancel() {
        cleanupResources()
        _state.value = TransferState.Idle
    }

    fun dismissNoWifi() { _state.value = TransferState.Idle }

    fun prepareRemoteTransfer(song: Song, ttlHours: Int = 24) {
        if (_state.value is TransferState.Preparing) return
        _state.value = TransferState.Preparing

        prepareJob?.cancel()
        prepareJob = scope.launch {
            val scheme = song.uri.scheme ?: ""
            if (scheme == "http" || scheme == "https") {
                _state.value = TransferState.Error("This song is streamed from a remote server and can't be shared as a file.")
                return@launch
            }
            val file = File(song.path)
            if (!file.exists()) {
                _state.value = TransferState.Error("Audio file not found at ${song.path}")
                return@launch
            }
            prepareRemote(song, file, ttlHours.coerceIn(1, MAX_REMOTE_TTL_HOURS))
        }
    }

    private data class RelayConfig(val serverUrl: String, val uploadToken: String, val deviceId: String)

    private suspend fun resolveRelayConfig(): RelayConfig {
        val customUrl = prefs.remoteShareServerUrl.first().trimEnd('/')
        val customToken = prefs.remoteShareUploadToken.first()
        return RelayConfig(
            serverUrl = customUrl.ifBlank { RemoteShareDefaults.SERVER_URL },
            uploadToken = customToken.ifBlank { RemoteShareDefaults.UPLOAD_TOKEN },
            deviceId = prefs.getOrCreateDeviceId(),
        )
    }

    /** Uploads one file to the relay's /upload endpoint and returns its per-file download token. */
    private fun uploadOneFile(song: Song, file: File, ttlHours: Int, config: RelayConfig, onProgress: (Float) -> Unit = {}): String {
        val mediaType = song.mimeType.ifBlank { "application/octet-stream" }.toMediaTypeOrNull()
        val request = Request.Builder()
            .url("${config.serverUrl}/upload?ttlHours=$ttlHours")
            .header("Authorization", "Bearer ${config.uploadToken}")
            .header("X-Device-Id", config.deviceId)
            .post(ProgressRequestBody(file.asRequestBody(mediaType), onProgress))
            .build()

        val response = relayClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val message = when (response.code) {
                429 -> "The share server is rate-limiting you right now — try again in a bit."
                413 -> "That file is too large for the share server."
                else -> "Upload failed for '${song.title}': HTTP ${response.code}"
            }
            throw Exception(message)
        }
        val body = response.body?.string() ?: throw Exception("Empty response uploading '${song.title}'")
        return try {
            JSONObject(body).getString("token")
        } catch (e: Exception) {
            throw Exception("Malformed server response uploading '${song.title}'")
        }
    }

    private suspend fun prepareRemote(song: Song, file: File, ttlHours: Int) = withContext(Dispatchers.IO) {
        try {
            val config = resolveRelayConfig()
            val token = uploadOneFile(song, file, ttlHours, config) { progress ->
                _state.value = TransferState.Uploading(progress)
            }

            val downloadUrl = "${config.serverUrl}/f/$token"
            val qrContent = buildString {
                append("resonance://receive?mode=remote")
                append("&url=").append(Uri.encode(downloadUrl))
                append("&title=").append(Uri.encode(song.title)).append("&artist=").append(Uri.encode(song.artist))
                append("&mime=").append(Uri.encode(song.mimeType)).append("&ext=").append(file.extension)
            }
            _state.value = TransferState.Ready(qrContent, "remote")
        } catch (e: Exception) {
            _state.value = TransferState.Error(e.message ?: "Upload failed")
        }
    }

    fun prepareRemoteMultiTransfer(songs: List<Song>, ttlHours: Int = 24) {
        if (_state.value is TransferState.Preparing) return
        if (songs.isEmpty()) return
        _state.value = TransferState.Preparing

        prepareJob?.cancel()
        prepareJob = scope.launch {
            val files = mutableListOf<Pair<Song, File>>()
            for (song in songs) {
                val scheme = song.uri.scheme ?: ""
                if (scheme == "http" || scheme == "https") {
                    _state.value = TransferState.Error("'${song.title}' is streamed from a remote server and can't be shared as a file.")
                    return@launch
                }
                val file = File(song.path)
                if (!file.exists()) {
                    _state.value = TransferState.Error("Audio file not found for '${song.title}'")
                    return@launch
                }
                files.add(song to file)
            }
            prepareRemoteMulti(files, ttlHours.coerceIn(1, MAX_REMOTE_TTL_HOURS))
        }
    }

    private suspend fun prepareRemoteMulti(files: List<Pair<Song, File>>, ttlHours: Int) = withContext(Dispatchers.IO) {
        try {
            val config = resolveRelayConfig()

            // Upload with bounded concurrency instead of one-at-a-time, so a batch of songs
            // finishes in roughly (N / MAX_CONCURRENT_UPLOADS) upload-lengths instead of N.
            val semaphore = Semaphore(MAX_CONCURRENT_UPLOADS)
            val perFileProgress = ConcurrentHashMap<Int, Float>()
            val completedCount = AtomicInteger(0)

            fun reportOverallProgress() {
                val overall = perFileProgress.values.sum() / files.size
                _state.value = TransferState.Uploading(overall.coerceIn(0f, 1f), completedCount.get(), files.size)
            }

            val tokens = files.mapIndexed { index, (song, file) ->
                async {
                    semaphore.withPermit {
                        val token = uploadOneFile(song, file, ttlHours, config) { progress ->
                            perFileProgress[index] = progress
                            reportOverallProgress()
                        }
                        perFileProgress[index] = 1f
                        completedCount.incrementAndGet()
                        reportOverallProgress()
                        token
                    }
                }
            }.awaitAll()

            val entries = JSONArray()
            files.forEachIndexed { index, (song, file) ->
                entries.put(JSONObject().apply {
                    put("token", tokens[index])
                    put("title", song.title)
                    put("artist", song.artist)
                    put("mime", song.mimeType)
                    put("ext", file.extension)
                })
            }

            val manifestBody = JSONObject().apply {
                put("ttlHours", ttlHours)
                put("songs", entries)
            }.toString().toRequestBody("application/json".toMediaType())

            val manifestRequest = Request.Builder()
                .url("${config.serverUrl}/manifest")
                .header("Authorization", "Bearer ${config.uploadToken}")
                .header("X-Device-Id", config.deviceId)
                .post(manifestBody)
                .build()

            val manifestResponse = relayClient.newCall(manifestRequest).execute()
            if (!manifestResponse.isSuccessful) {
                val message = when (manifestResponse.code) {
                    429 -> "The share server is rate-limiting you right now — try again in a bit."
                    else -> "Failed to create share link: HTTP ${manifestResponse.code}"
                }
                _state.value = TransferState.Error(message)
                return@withContext
            }
            val body = manifestResponse.body?.string() ?: throw Exception("Empty response")
            val manifestToken = try {
                JSONObject(body).getString("token")
            } catch (e: Exception) {
                throw Exception("Malformed server response")
            }

            val qrContent = buildString {
                append("resonance://receive?mode=remote-multi")
                append("&url=").append(Uri.encode(config.serverUrl))
                append("&manifest=").append(manifestToken)
            }
            _state.value = TransferState.Ready(qrContent, "remote-multi")
        } catch (e: Exception) {
            _state.value = TransferState.Error(e.message ?: "Upload failed")
        }
    }

    private suspend fun prepareLan(song: Song, file: File) {
        val ip = getWifiIp()
        if (ip == null) {
            prepareP2p(song, file)
            return
        }

        val handle = fileServer.serve(
            file       = file,
            mimeType   = song.mimeType,
            onProgress = { _state.value = TransferState.Serving(it) },
            onDone     = { _state.value = TransferState.Done },
            onRejected = { _state.value = TransferState.Rejected }
        )

        val qrContent = buildTransferUri(
            mode   = "lan",
            ip     = ip,
            port   = handle.port,
            token  = handle.token,
            song   = song,
            file   = file,
        )

        _state.value = TransferState.Ready(qrContent, "lan")
    }

    @SuppressLint("MissingPermission")
    private suspend fun prepareP2p(song: Song, file: File) {
        if (!ensureP2pInitialized()) return

        val manager = p2pManager!!
        val channel = p2pChannel!!

        suspendCancellableCoroutine { cont ->
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess()        { if (cont.isActive) cont.resume(Unit) }
                override fun onFailure(r: Int)  { if (cont.isActive) cont.resume(Unit) }
            })
        }

        val created = suspendCancellableCoroutine { cont ->
            manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess()       { if (cont.isActive) cont.resume(true) }
                override fun onFailure(r: Int) { if (cont.isActive) cont.resume(false) }
            })
        }
        if (!created) {
            _state.value = TransferState.Error("Could not create Wi-Fi Direct group")
            return
        }

        val group = pollGroupInfo(manager, channel)
        if (group == null) {
            _state.value = TransferState.Error("Wi-Fi Direct group info unavailable")
            return
        }

        val goIp = pollGoIp(manager, channel)
        if (goIp == null) {
            _state.value = TransferState.Error("Could not resolve group owner IP")
            return
        }

        val handle = fileServer.serve(
            file       = file,
            mimeType   = song.mimeType,
            onProgress = { _state.value = TransferState.Serving(it) },
            onDone     = { _state.value = TransferState.Done },
            onRejected = { _state.value = TransferState.Rejected }
        )

        val qrContent = buildTransferUri(
            mode       = "p2p",
            ip         = goIp,
            port       = handle.port,
            token      = handle.token,
            song       = song,
            file       = file,
            p2pSsid    = group.networkName,
            p2pPassphrase = group.passphrase,
        )

        _state.value = TransferState.Ready(qrContent, "p2p")
    }

    @SuppressLint("MissingPermission")
    private suspend fun pollGroupInfo(manager: WifiP2pManager, channel: WifiP2pManager.Channel): WifiP2pGroup? {
        repeat(20) {
            delay(500)
            val group = suspendCancellableCoroutine { cont ->
                manager.requestGroupInfo(channel) { g -> if (cont.isActive) cont.resume(g) }
            }
            if (group?.passphrase != null) return group
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun pollGoIp(manager: WifiP2pManager, channel: WifiP2pManager.Channel): String? {
        repeat(20) {
            delay(500)
            val info = suspendCancellableCoroutine { cont ->
                manager.requestConnectionInfo(channel) { i -> if (cont.isActive) cont.resume(i) }
            }
            val ip = info?.groupOwnerAddress?.hostAddress
            if (!ip.isNullOrBlank()) return ip
        }
        return null
    }

    private fun isOnWifi(): Boolean {
        val cm    = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net   = cm.activeNetwork ?: return false
        val caps  = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun isWifiEnabled(): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wm.isWifiEnabled
    }

    private fun getWifiIp(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val props = cm.getLinkProperties(net) ?: return null
        return props.linkAddresses
            .map { it.address }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull()
            ?.hostAddress
    }

    private fun buildTransferUri(
        mode: String, ip: String, port: Int, token: String, song: Song, file: File,
        p2pSsid: String? = null, p2pPassphrase: String? = null,
    ): String = buildString {
        append("resonance://receive?mode=").append(mode).append("&ip=").append(ip)
        append("&port=").append(port).append("&token=").append(token)
        append("&title=").append(Uri.encode(song.title)).append("&artist=").append(Uri.encode(song.artist))
        append("&mime=").append(Uri.encode(song.mimeType)).append("&ext=").append(file.extension)
        if (p2pSsid != null)       append("&ssid=").append(Uri.encode(p2pSsid))
        if (p2pPassphrase != null) append("&pass=").append(Uri.encode(p2pPassphrase))
    }
}