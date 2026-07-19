// Bridges the live FFT spectrum captured off the ExoPlayer audio session (in MusicService)
// to the Cava seekbar composable (in the UI process/scope), the same way EqStateHolder
// bridges the equalizer. MusicService publishes bars; the UI requests capture on/off so the
// Visualizer only runs while the Cava style is actually visible and playing.
package dev.yuwixx.resonance.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VisualizerStateHolder @Inject constructor() {

    companion object {
        const val BAR_COUNT = 32
    }

    private val _magnitudes = MutableStateFlow(FloatArray(BAR_COUNT))
    val magnitudes: StateFlow<FloatArray> = _magnitudes.asStateFlow()

    var onActiveRequestChanged: ((Boolean) -> Unit)? = null

    fun publishMagnitudes(values: FloatArray) {
        _magnitudes.value = values
    }

    fun requestActive(active: Boolean) {
        onActiveRequestChanged?.invoke(active)
    }
}
