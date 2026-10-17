package dev.yuwixx.resonance.cast

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** No-op Cast support for the foss build, with the same API as the gms version. */
@Singleton
class CastManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()

    private val _castIsPlaying = MutableStateFlow(false)
    val castIsPlaying: StateFlow<Boolean> = _castIsPlaying.asStateFlow()

    fun initialize() {
        // No Cast SDK in this build.
    }

    fun castSong(audioUrl: String, song: Song, positionMs: Long = 0L, artworkUrl: String? = null) {
        // isCasting is always false, so callers never reach here in practice.
    }

    fun playPause() {}

    fun seekTo(positionMs: Long) {}

    fun getCurrentPosition(): Long = 0L

    fun getStreamDuration(): Long = -1L
}
