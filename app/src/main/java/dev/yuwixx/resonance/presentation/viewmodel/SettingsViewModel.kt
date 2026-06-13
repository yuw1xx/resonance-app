package dev.yuwixx.resonance.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yuwixx.resonance.data.network.GitHubApi
import dev.yuwixx.resonance.data.network.GitHubRelease
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.data.model.MusicSource
import dev.yuwixx.resonance.data.network.NavidromeApiProvider
import dev.yuwixx.resonance.data.repository.LastFmAuthState
import dev.yuwixx.resonance.data.repository.LastFmRepository
import dev.yuwixx.resonance.data.repository.MalojaRepository
import dev.yuwixx.resonance.data.repository.NavidromeConnectionState
import dev.yuwixx.resonance.data.repository.NavidromeRepository
import dev.yuwixx.resonance.data.repository.NavidromeSyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val lastFmRepository: LastFmRepository,
    val malojaRepository: MalojaRepository,
    private val prefs: ResonancePreferences,
    private val gitHubApi: GitHubApi,
    private val okHttpClient: OkHttpClient,
    private val navidromeRepository: NavidromeRepository,
    private val navidromeApiProvider: NavidromeApiProvider,
) : ViewModel() {

    sealed class UpdateState {
        data object Idle : UpdateState()
        data class Checking(val isManual: Boolean) : UpdateState()
        data object UpToDate : UpdateState()
        data class Available(val release: GitHubRelease, val assetUrl: String) : UpdateState()
        data class Downloading(val progress: Float) : UpdateState()
        data class ReadyToInstall(val apkFile: File) : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    val updateFrequency: StateFlow<String> = prefs.updateFrequency
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "DAILY")

    fun setUpdateFrequency(freq: String) {
        viewModelScope.launch { prefs.setUpdateFrequency(freq) }
    }

    fun dismissUpdate() {
        _updateState.value = UpdateState.Idle
    }

    fun checkForUpdates(currentVersion: String, isManual: Boolean = false) {
        viewModelScope.launch {
            if (!isManual) {
                val freq = prefs.updateFrequency.first()
                if (freq == "DISABLED") return@launch

                val lastCheck = prefs.lastUpdateCheck.first()
                val now = System.currentTimeMillis()
                val hoursSinceLast = (now - lastCheck) / (1000 * 60 * 60)

                val shouldCheck = when (freq) {
                    "LAUNCH" -> true
                    "DAILY"  -> hoursSinceLast >= 24
                    "WEEKLY" -> hoursSinceLast >= 168
                    else     -> false
                }
                if (!shouldCheck) return@launch
            }

            _updateState.value = UpdateState.Checking(isManual)
            try {
                val release = gitHubApi.getLatestRelease()
                prefs.setLastUpdateCheck(System.currentTimeMillis())

                val remoteVersion = release.tagName.removePrefix("v")
                val localVersion = currentVersion.removePrefix("v")

                if (isNewerVersion(localVersion, remoteVersion)) {
                    val apkAsset = release.assets.find { it.name.endsWith(".apk") }
                    if (apkAsset != null) {
                        _updateState.value = UpdateState.Available(release, apkAsset.browserDownloadUrl)
                    } else {
                        if (isManual) _updateState.value = UpdateState.Error("No APK found in the latest release.")
                        else _updateState.value = UpdateState.Idle
                    }
                } else {
                    if (isManual) _updateState.value = UpdateState.UpToDate
                    else _updateState.value = UpdateState.Idle
                }
            } catch (e: Exception) {
                if (isManual) _updateState.value = UpdateState.Error("Failed to check for updates. Check your connection.")
                else _updateState.value = UpdateState.Idle
            }
        }
    }

    private fun isNewerVersion(local: String, remote: String): Boolean {
        val lParts = local.split(".").mapNotNull { it.toIntOrNull() }
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val length = maxOf(lParts.size, rParts.size)
        for (i in 0 until length) {
            val l = lParts.getOrElse(i) { 0 }
            val r = rParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    fun downloadUpdate(context: Context, url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _updateState.value = UpdateState.Downloading(0f)
            try {
                val request = Request.Builder().url(url).build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val body = response.body ?: throw Exception("Empty response body")

                val file = File(context.cacheDir, "resonance_update.apk")
                if (file.exists()) file.delete()

                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                _updateState.value = UpdateState.Downloading(downloadedBytes.toFloat() / totalBytes)
                            }
                        }
                    }
                }
                _updateState.value = UpdateState.ReadyToInstall(file)
            } catch (e: Exception) {
                _updateState.value = UpdateState.Error(e.message ?: "Download failed")
            }
        }
    }

    val lastFmAuthState: StateFlow<LastFmAuthState> = lastFmRepository.authState

    val darkTheme: StateFlow<String> = prefs.darkTheme
        .stateIn(viewModelScope, SharingStarted.Eagerly, "SYSTEM")

    val lastFmEnabled: StateFlow<Boolean> = lastFmRepository.isEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val lastFmNowPlaying: StateFlow<Boolean> = lastFmRepository.nowPlaying
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val lastFmOnlyWifi: StateFlow<Boolean> = lastFmRepository.onlyWifi
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val lastFmScrobblePct: StateFlow<Float> = prefs.lastFmScrobblePercent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.5f)

    val lastFmScrobbleMinSecs: StateFlow<Int> = prefs.lastFmScrobbleMinSecs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 30)

    val lastFmOfflineQueue: StateFlow<Boolean> = prefs.lastFmScrobbleOffline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setLastFmScrobblePct(v: Float) {
        viewModelScope.launch { prefs.setLastFmScrobblePercent(v) }
    }

    fun setLastFmScrobbleMinSecs(v: Int) {
        viewModelScope.launch { prefs.setLastFmScrobbleMinSecs(v) }
    }

    fun setLastFmOfflineQueue(v: Boolean) {
        viewModelScope.launch { prefs.setLastFmScrobbleOffline(v) }
    }

    private val _pendingScrobbles = MutableStateFlow(0)
    val pendingScrobbles: StateFlow<Int> = _pendingScrobbles.asStateFlow()

    private val _malojaPending = MutableStateFlow(0)
    val malojaPending: StateFlow<Int> = _malojaPending.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                _pendingScrobbles.value = lastFmRepository.pendingScrobbleCount
                _malojaPending.value = malojaRepository.pendingCount
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    fun lastFmLogin(username: String, password: String) {
        viewModelScope.launch { lastFmRepository.authenticate(username, password) }
    }

    fun lastFmLogout() {
        viewModelScope.launch { lastFmRepository.logout() }
    }

    fun setLastFmEnabled(v: Boolean) {
        viewModelScope.launch { lastFmRepository.setEnabled(v) }
    }

    fun setLastFmNowPlaying(v: Boolean) {
        viewModelScope.launch { lastFmRepository.setNowPlaying(v) }
    }

    fun setLastFmOnlyWifi(v: Boolean) {
        viewModelScope.launch { lastFmRepository.setOnlyWifi(v) }
    }

    val partyMode: StateFlow<Boolean> = prefs.partyMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setPartyMode(v: Boolean) {
        viewModelScope.launch { prefs.setPartyMode(v) }
    }

    val malojaEnabled: StateFlow<Boolean> = malojaRepository.isEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val malojaServerUrl: StateFlow<String> = malojaRepository.serverUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    sealed class MalojaTestState {
        data object Idle : MalojaTestState()
        data object Loading : MalojaTestState()
        data class Success(val desc: String) : MalojaTestState()
        data class Error(val message: String) : MalojaTestState()
    }

    private val _malojaTestState = MutableStateFlow<MalojaTestState>(MalojaTestState.Idle)
    val malojaTestState: StateFlow<MalojaTestState> = _malojaTestState.asStateFlow()

    fun saveMalojaConfig(url: String, key: String) {
        viewModelScope.launch { malojaRepository.configure(url, key) }
    }

    fun clearMaloja() {
        viewModelScope.launch { malojaRepository.clear() }
    }

    fun setMalojaEnabled(v: Boolean) {
        viewModelScope.launch { malojaRepository.setEnabled(v) }
    }

    fun testMalojaConnection(url: String, key: String) {
        viewModelScope.launch {
            _malojaTestState.value = MalojaTestState.Loading
            _malojaTestState.value = malojaRepository.testConnection(url, key).fold(
                onSuccess = { MalojaTestState.Success(it) },
                onFailure = { MalojaTestState.Error(it.message ?: "Unknown error") },
            )
        }
    }

    fun resetMalojaTestState() {
        _malojaTestState.value = MalojaTestState.Idle
    }

    val musicSource: StateFlow<MusicSource> = prefs.musicSource
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MusicSource.LOCAL)

    val navidromeServerUrl: StateFlow<String?> = prefs.navidromeServerUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val navidromeUsername: StateFlow<String?> = prefs.navidromeUsername
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val navidromeConnectionState: StateFlow<NavidromeConnectionState> =
        navidromeRepository.connectionState
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NavidromeConnectionState.Idle)

    val navidromeSyncState: StateFlow<NavidromeSyncState> =
        navidromeRepository.syncState
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NavidromeSyncState.Idle)

    fun resetNavidromeSyncState() {
        navidromeRepository.resetSyncState()
    }

        fun testNavidromeConnection(serverUrl: String, username: String, password: String) {
        viewModelScope.launch {
            navidromeRepository.testConnection(serverUrl, username, password)
        }
    }

        fun saveNavidromeAndSwitch(serverUrl: String, username: String, password: String) {
        viewModelScope.launch {
            val effectivePassword = password.ifBlank { prefs.navidromePassword.first() ?: password }
            prefs.setNavidromeCredentials(serverUrl, username, effectivePassword)
            prefs.setMusicSource(MusicSource.NAVIDROME)
            navidromeApiProvider.rebuild(serverUrl, username, effectivePassword)
            navidromeRepository.resetSyncState()
            navidromeRepository.syncLibrary()
        }
    }

        fun switchToLocal() {
        viewModelScope.launch {
            prefs.clearNavidromeCredentials()
            navidromeRepository.clearCache()
            navidromeRepository.resetConnectionState()
        }
    }

    fun resetNavidromeConnectionState() {
        navidromeRepository.resetConnectionState()
    }
}
