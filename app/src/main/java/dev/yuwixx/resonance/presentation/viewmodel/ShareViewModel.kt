package dev.yuwixx.resonance.presentation.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.repository.MusicRepository
import dev.yuwixx.resonance.data.service.NearbyShareManager
import dev.yuwixx.resonance.data.service.ShareTransferManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class ShareViewModel @Inject constructor(
    private val nearbyManager: NearbyShareManager,
    private val transferManager: ShareTransferManager,
    private val musicRepository: MusicRepository,
) : ViewModel() {

    val nearbyState     = nearbyManager.state
    val nearbyDevices   = nearbyManager.nearbyDevices
    val localName       = nearbyManager.localName
    val incomingRequest = nearbyManager.incomingRequest
    val incomingFile    = nearbyManager.incomingFile
    val isGrapheneOs    = nearbyManager.isGrapheneOs

    fun startScanning(activity: Activity? = null) = nearbyManager.startScanning(activity)
    fun stopScanning()                = nearbyManager.stopScanning()
    fun connectTo(endpointId: String) = nearbyManager.connectTo(endpointId)
    fun disconnectNearby()            = nearbyManager.disconnect()
    fun clearIncoming()               = nearbyManager.clearIncoming()

    fun sendViaNearby(endpointId: String) {
        val song = _selectedSongs.value.singleOrNull() ?: return
        nearbyManager.sendSong(song, endpointId)
    }

    fun acceptNearbyRequest(endpointId: String) = nearbyManager.acceptRequest(endpointId)
    fun rejectNearbyRequest(endpointId: String) = nearbyManager.rejectRequest(endpointId)

    val transferState = transferManager.state

    fun prepareTransfer(song: Song) {
        transferManager.prepareTransfer(song)
    }
    private val _remoteTtlHours = MutableStateFlow(24)
    val remoteTtlHours: StateFlow<Int> = _remoteTtlHours.asStateFlow()
    fun setRemoteTtlHours(hours: Int) { _remoteTtlHours.value = hours }

    /** Shares whatever's currently selected — a single song via the existing one-song relay
     *  path, or multiple songs via the manifest-linked multi-song path. Capped to match the
     *  relay's own per-manifest song limit so a huge selection fails fast instead of
     *  uploading everything and only then being rejected when the manifest is created. */
    fun prepareRemoteTransfer() {
        val songs = _selectedSongs.value.take(MAX_SHARE_SONGS)
        if (songs.isEmpty()) return
        if (songs.size == 1) transferManager.prepareRemoteTransfer(songs.first(), _remoteTtlHours.value)
        else transferManager.prepareRemoteMultiTransfer(songs, _remoteTtlHours.value)
    }

    fun cancelTransfer()            = transferManager.cancel()
    fun dismissNoWifi()             = transferManager.dismissNoWifi()

    private val _selectedSongs = MutableStateFlow<List<Song>>(emptyList())
    val selectedSongs: StateFlow<List<Song>> = _selectedSongs.asStateFlow()

    fun preselectSongs(songs: List<Song>) { if (_selectedSongs.value.isEmpty()) _selectedSongs.value = songs }
    fun selectSong(song: Song)            { _selectedSongs.value = listOf(song) }
    fun clearSelection()                  { _selectedSongs.value = emptyList() }

    companion object {
        const val MAX_SHARE_SONGS = 50
    }

    val allSongs: StateFlow<List<Song>> = musicRepository.allSongs
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    sealed class DiagnosticState {
        data object Idle : DiagnosticState()
        data object Running : DiagnosticState()
        data class Success(val message: String) : DiagnosticState()
        data class Failure(val message: String) : DiagnosticState()
    }

    private val _diagnosticState = MutableStateFlow<DiagnosticState>(DiagnosticState.Idle)
    val diagnosticState = _diagnosticState.asStateFlow()

    fun runDiagnostics() {
        if (_diagnosticState.value is DiagnosticState.Running) return
        _diagnosticState.value = DiagnosticState.Running

        viewModelScope.launch {
            when (val result = nearbyManager.checkAvailability()) {
                is NearbyShareManager.DiagnosticResult.Success -> {
                    delay(800)
                    _diagnosticState.value = DiagnosticState.Success(result.message)
                }
                is NearbyShareManager.DiagnosticResult.Failure -> {
                    _diagnosticState.value = DiagnosticState.Failure(result.message)
                }
            }
        }
    }

    fun clearDiagnostics() {
        _diagnosticState.value = DiagnosticState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        nearbyManager.stopScanning()
        transferManager.cancel()
    }
}