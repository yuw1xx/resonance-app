// Manages peer-to-peer song sharing via Google Nearby Connections (P2P_STAR strategy).
// Handles discovery, connection, metadata exchange (META||| protocol), and file transfer progress.
package dev.yuwixx.resonance.data.service

import android.app.Activity
import android.content.Context
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.util.DeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NearbyShareManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        const val SERVICE_ID = "dev.yuwixx.resonance.nearby"
        val STRATEGY: Strategy = Strategy.P2P_STAR
    }

    private val appClient: ConnectionsClient = Nearby.getConnectionsClient(context)

    private var client: ConnectionsClient = appClient

    data class NearbyDevice(
        val endpointId: String,
        val name: String,
    )

    private val _nearbyDevices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val nearbyDevices: StateFlow<List<NearbyDevice>> = _nearbyDevices.asStateFlow()

    sealed class NearbyState {
        data object Idle : NearbyState()
        data object Scanning : NearbyState()
        data class Connecting(val endpointId: String) : NearbyState()
        data class Connected(val endpointId: String, val deviceName: String) : NearbyState()
        data object AwaitingAcceptance : NearbyState()
        data class Sending(val progress: Float) : NearbyState()
        data object SendSuccess : NearbyState()
        data object Rejected : NearbyState()
        data class Receiving(val progress: Float) : NearbyState()
        data class Error(val message: String) : NearbyState()
        data object LocationDisabled : NearbyState()
        data object GpsUnavailable : NearbyState()
    }

    private val _state = MutableStateFlow<NearbyState>(NearbyState.Idle)
    val state: StateFlow<NearbyState> = _state.asStateFlow()

    data class IncomingRequest(
        val endpointId: String,
        val senderName: String,
        val songTitle: String,
        val songArtist: String,
    )

    data class IncomingFile(
        val senderName: String,
        val songTitle: String,
        val songArtist: String,
        val file: File,
    )

    private val _incomingRequest = MutableStateFlow<IncomingRequest?>(null)
    val incomingRequest: StateFlow<IncomingRequest?> = _incomingRequest.asStateFlow()

    private val _incomingFile = MutableStateFlow<IncomingFile?>(null)
    val incomingFile: StateFlow<IncomingFile?> = _incomingFile.asStateFlow()

    val localName: String = Build.MODEL

    val isGrapheneOs: Boolean by lazy { DeviceInfo.isGrapheneOs(context) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pendingSongsToSend = java.util.concurrent.ConcurrentHashMap<String, Song>()
    private val pendingIncomingMeta = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    private var filePayloadId: Long? = null

    // Receiver-side tracking: set when onPayloadReceived fires for FILE, cleared after SUCCESS/FAILURE.
    // The file in the Nearby cache is only fully written when onPayloadTransferUpdate reports SUCCESS.
    private var incomingFilePayloadId: Long? = null
    private var incomingPendingPayload: Payload? = null
    private var incomingPendingSender: String = ""

    // ─── Scanning / Connection ───

    fun startScanning(activity: Activity? = null) {
        if (_state.value != NearbyState.Idle) return

        if (isGrapheneOs) {
            _state.value = NearbyState.GpsUnavailable
            return
        }

        val gpsResult = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (gpsResult == ConnectionResult.SERVICE_MISSING ||
            gpsResult == ConnectionResult.SERVICE_DISABLED ||
            gpsResult == ConnectionResult.SERVICE_INVALID) {
            _state.value = NearbyState.GpsUnavailable
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !isLocationEnabled()) {
            _state.value = NearbyState.LocationDisabled
            return
        }

        val wifiEnabled = try {
            (context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
                ?.isWifiEnabled ?: true
        } catch (_: Exception) { true }
        if (!wifiEnabled) {
            _state.value = NearbyState.Error("Wi-Fi is off. Please enable Wi-Fi (no internet needed) and tap Retry.")
            return
        }

        val btEnabled = try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
                ?.adapter?.isEnabled ?: true
        } catch (_: Exception) { true }
        if (!btEnabled) {
            _state.value = NearbyState.Error("Bluetooth is off. Please enable Bluetooth and try again.")
            return
        }

        val permissionError: String? = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val granted = { perm: String ->
                    androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                when {
                    !granted(android.Manifest.permission.NEARBY_WIFI_DEVICES) ->
                        "Nearby Wi-Fi permission missing.\nGo to Settings → Apps → Resonance → Permissions → Nearby devices, then tap Retry."
                    !granted(android.Manifest.permission.ACCESS_FINE_LOCATION) ->
                        "Location permission missing (required by Nearby Share).\nGo to Settings → Apps → Resonance → Permissions → Location, then tap Retry."
                    else -> null
                }
            }
            else -> {
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted)
                    "Location permission missing (required on this Android version).\nGo to Settings → Apps → Resonance → Permissions → Location, then tap Retry."
                else null
            }
        }
        if (permissionError != null) {
            _state.value = NearbyState.Error(permissionError)
            return
        }

        client = if (activity != null) Nearby.getConnectionsClient(activity) else appClient

        _nearbyDevices.value = emptyList()
        _state.value = NearbyState.Scanning

        val strategy = STRATEGY

        val advOpts = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()
        client.startAdvertising(localName, SERVICE_ID, connectionLifecycle, advOpts)
            .addOnFailureListener { e -> _state.value = nearbyFailureState(e.message) }

        val disOpts = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()
        client.startDiscovery(SERVICE_ID, endpointDiscovery, disOpts)
            .addOnFailureListener { e -> _state.value = nearbyFailureState(e.message) }
    }

    private fun nearbyFailureState(raw: String?): NearbyState {
        if (isGrapheneOs && raw?.contains("8029") == true) return NearbyState.GpsUnavailable
        return NearbyState.Error(friendlyNearbyError(raw))
    }

    private fun friendlyNearbyError(raw: String?): String = when {
        raw == null -> "Nearby Share failed"
        raw.contains("8029") ->
            "Nearby Share couldn't start (error 8029).\n" +
            "• Make sure Wi-Fi is ON (no internet connection required).\n" +
            "• If Wi-Fi is on, check Settings → Apps → Resonance → Permissions → Nearby devices."
        raw.contains("8009") ->
            "Nearby Share requires Bluetooth. Please enable Bluetooth and tap Retry."
        raw.contains("8006") ->
            "Nearby Share is already running on another endpoint. Tap Retry to restart."
        else -> raw
    }

    fun stopScanning() {
        _state.value = NearbyState.Idle
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        client = appClient
        _nearbyDevices.value = emptyList()
        pendingSongsToSend.clear()
        pendingIncomingMeta.clear()
        filePayloadId = null
        incomingFilePayloadId = null
        incomingPendingPayload = null
        incomingPendingSender = ""
    }

    fun connectTo(endpointId: String) {
        _state.value = NearbyState.Connecting(endpointId)
        client.requestConnection(localName, endpointId, connectionLifecycle)
            .addOnFailureListener { _state.value = NearbyState.Error(it.message ?: "Connection request failed") }
    }

        fun sendSong(song: Song, endpointId: String) {
        val scheme = song.uri.scheme ?: ""
        if (scheme == "http" || scheme == "https") {
            _state.value = NearbyState.Error("This song is streamed from a remote server and can't be shared as a file.")
            return
        }
        val file = File(song.path)
        if (!file.exists()) {
            _state.value = NearbyState.Error("Audio file not found at ${song.path}")
            return
        }

        pendingSongsToSend[endpointId] = song
        val meta = "META|||${song.title}|||${song.artist}"
        client.sendPayload(endpointId, Payload.fromBytes(meta.toByteArray()))
        _state.value = NearbyState.AwaitingAcceptance
    }

    fun acceptRequest(endpointId: String) {
        client.sendPayload(endpointId, Payload.fromBytes("ACCEPT".toByteArray()))
        _incomingRequest.value = null
    }

    fun rejectRequest(endpointId: String) {
        client.sendPayload(endpointId, Payload.fromBytes("REJECT".toByteArray()))
        _incomingRequest.value = null
    }

    fun disconnect() {
        stopScanning()
    }

    fun clearIncoming() {
        _incomingFile.value = null
        _incomingRequest.value = null
        incomingFilePayloadId = null
        incomingPendingPayload = null
        incomingPendingSender = ""
    }

    // ─── Nearby Callbacks ───

    private val endpointDiscovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.endpointName == localName) return
            val device = NearbyDevice(endpointId, info.endpointName)
            _nearbyDevices.value = _nearbyDevices.value
                .filterNot { it.endpointId == endpointId } + device
        }

        override fun onEndpointLost(endpointId: String) {
            _nearbyDevices.value = _nearbyDevices.value.filterNot { it.endpointId == endpointId }
        }
    }

    private val connectionLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                val name = _nearbyDevices.value.find { it.endpointId == endpointId }?.name ?: endpointId
                _state.value = NearbyState.Connected(endpointId, name)
            } else {
                _state.value = NearbyState.Error("Connection to device failed (${result.status.statusCode})")
            }
        }

        override fun onDisconnected(endpointId: String) {
            _state.value = when (_state.value) {
                is NearbyState.Sending, is NearbyState.AwaitingAcceptance ->
                    NearbyState.Error("Connection lost during transfer")
                is NearbyState.Error -> _state.value
                else -> NearbyState.Idle
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val str = payload.asBytes()?.let { String(it) } ?: return
                    when {
                        str == "ACCEPT" -> {
                            val song = pendingSongsToSend[endpointId] ?: return
                            val filePayload = Payload.fromFile(File(song.path))
                            filePayloadId = filePayload.id
                            client.sendPayload(endpointId, filePayload)
                                .addOnFailureListener { _state.value = NearbyState.Error("Send failed") }
                            _state.value = NearbyState.Sending(0f)
                            pendingSongsToSend.remove(endpointId)
                        }
                        str == "REJECT" -> {
                            _state.value = NearbyState.Rejected
                            pendingSongsToSend.remove(endpointId)
                        }
                        // "META|||title|||artist" is sent before the file payload so the receiver
                        // can show a consent prompt with song info before accepting the transfer.
                        str.startsWith("META|||") -> {
                            val parts = str.split("|||")
                            val senderName = _nearbyDevices.value.find { it.endpointId == endpointId }?.name ?: "Unknown"
                            val title = parts.getOrNull(1) ?: "Unknown"
                            val artist = parts.getOrNull(2) ?: "Unknown"
                            pendingIncomingMeta[endpointId] = title to artist
                            _incomingRequest.value = IncomingRequest(
                                endpointId = endpointId,
                                senderName = senderName,
                                songTitle  = title,
                                songArtist = artist
                            )
                        }
                    }
                }
                Payload.Type.FILE -> {
                    // The Nearby FILE payload is only fully written to disk when
                    // onPayloadTransferUpdate fires with SUCCESS. Store the payload reference
                    // and track progress there; do not touch the file here.
                    incomingPendingPayload = payload
                    incomingFilePayloadId = payload.id
                    incomingPendingSender = _nearbyDevices.value
                        .find { it.endpointId == endpointId }?.name ?: "Unknown"
                    _state.value = NearbyState.Receiving(0f)
                }
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val progress = if (update.totalBytes > 0)
                update.bytesTransferred.toFloat() / update.totalBytes.toFloat()
            else 0f

            when (update.payloadId) {
                filePayloadId -> when (update.status) {
                    PayloadTransferUpdate.Status.IN_PROGRESS -> _state.value = NearbyState.Sending(progress)
                    PayloadTransferUpdate.Status.SUCCESS     -> _state.value = NearbyState.SendSuccess
                    PayloadTransferUpdate.Status.FAILURE     -> _state.value = NearbyState.Error("Transfer failed")
                    else -> Unit
                }
                incomingFilePayloadId -> when (update.status) {
                    PayloadTransferUpdate.Status.IN_PROGRESS -> _state.value = NearbyState.Receiving(progress)
                    PayloadTransferUpdate.Status.SUCCESS -> {
                        val payload = incomingPendingPayload
                        val sender  = incomingPendingSender
                        incomingFilePayloadId = null
                        incomingPendingPayload = null
                        incomingPendingSender = ""
                        val tempUri = payload?.asFile()?.asUri() ?: return
                        val (title, artist) = pendingIncomingMeta.remove(endpointId)
                            ?: (displayNameWithoutExtension(tempUri) to "Unknown artist")
                        scope.launch { saveAndPublishIncomingFile(tempUri, sender, title, artist) }
                    }
                    PayloadTransferUpdate.Status.FAILURE -> {
                        incomingFilePayloadId = null
                        incomingPendingPayload = null
                        incomingPendingSender = ""
                        _state.value = NearbyState.Error("Transfer failed")
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun saveAndPublishIncomingFile(
        tempUri: Uri,
        senderName: String,
        title: String,
        artist: String,
    ) {
        try {
            val ext = displayName(tempUri)?.substringAfterLast('.', "")?.ifEmpty { null } ?: "mp3"
            val destFile = context.contentResolver.openInputStream(tempUri)?.use { input ->
                dev.yuwixx.resonance.data.util.IncomingFileStorage.saveIncoming(
                    context = context,
                    input = input,
                    title = title,
                    ext = ext,
                    mimeType = "audio/$ext",
                )
            } ?: throw Exception("Could not open received file")
            _incomingFile.value = IncomingFile(
                senderName = senderName,
                songTitle  = title,
                songArtist = artist,
                file       = destFile,
            )
            _state.value = NearbyState.Idle
        } catch (e: Exception) {
            _state.value = NearbyState.Error("Could not save received file: ${e.message}")
        }
    }

    private fun displayName(uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun displayNameWithoutExtension(uri: Uri): String =
        displayName(uri)?.substringBeforeLast('.') ?: "Unknown"

    private fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }
}