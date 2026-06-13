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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class ShareTransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileServer: LocalFileServer,
) {

    sealed class TransferState {
        data object Idle      : TransferState()
        data object Preparing : TransferState()
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