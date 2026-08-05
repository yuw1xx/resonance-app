// FOSS-flavor stub: Chromecast requires Google Play Services (com.google.android.gms.cast.*),
// which isn't available in this build. Keeps the exact public API of the gms-flavor
// CastManager (app/src/gms/.../cast/CastManager.kt) so every call site (MainActivity,
// PlayerViewModel) compiles unchanged and simply never observes a casting session.
package dev.yuwixx.resonance.cast

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CastManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()

    private val _castIsPlaying = MutableStateFlow(false)
    val castIsPlaying: StateFlow<Boolean> = _castIsPlaying.asStateFlow()

    fun initialize() {
        // No Cast SDK in this build — nothing to initialize.
    }

    fun castSong(audioUrl: String, song: Song, positionMs: Long = 0L, artworkUrl: String? = null) {
        // isCasting is always false, so callers never reach here in practice.
    }

    fun playPause() {}

    fun seekTo(positionMs: Long) {}

    fun getCurrentPosition(): Long = 0L

    fun getStreamDuration(): Long = -1L
}
