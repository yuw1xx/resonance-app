// FOSS-flavor stub: Nearby Connections requires Google Play Services, unavailable in this
// build. Keeps the exact public API of the gms-flavor NearbyShareManager (including the
// NearbyState/NearbyDevice shapes) so ShareViewModel and ShareSheet compile unchanged.
// startScanning() reports GpsUnavailable — a state the shared UI already handles gracefully
// for GrapheneOS users ("QR Code works without Google Play Services"), so it applies here too
// without needing any new UI.
package dev.yuwixx.resonance.data.service

import android.app.Activity
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.util.DeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NearbyShareManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
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

    val localName: String = android.os.Build.MODEL

    val isGrapheneOs: Boolean by lazy { DeviceInfo.isGrapheneOs(context) }

    fun startScanning(activity: Activity? = null) {
        _state.value = NearbyState.GpsUnavailable
    }

    fun stopScanning() {
        _state.value = NearbyState.Idle
        _nearbyDevices.value = emptyList()
    }

    fun connectTo(endpointId: String) {}

    fun sendSong(song: Song, endpointId: String) {}

    fun acceptRequest(endpointId: String) { _incomingRequest.value = null }

    fun rejectRequest(endpointId: String) { _incomingRequest.value = null }

    fun disconnect() { stopScanning() }

    fun clearIncoming() {
        _incomingFile.value = null
        _incomingRequest.value = null
    }

    sealed class DiagnosticResult {
        data class Success(val message: String) : DiagnosticResult()
        data class Failure(val message: String) : DiagnosticResult()
    }

    fun checkAvailability(): DiagnosticResult =
        DiagnosticResult.Failure(
            "Nearby Share isn't available in this build (no Google Play Services support). " +
            "Use QR Code or Internet Share instead."
        )
}
