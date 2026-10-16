package dev.yuwixx.resonance.presentation.viewmodel

import kotlin.math.roundToInt
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yuwixx.resonance.data.service.EqStateHolder
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val eqStateHolder: EqStateHolder,
) : ViewModel() {

    val capabilities: StateFlow<EqStateHolder.EqCapabilities?> = eqStateHolder.capabilities
    val bandLevels: StateFlow<List<Short>> = eqStateHolder.bandLevels
    val enabled: StateFlow<Boolean> = eqStateHolder.enabled

    fun setBandLevel(band: Int, level: Short) = eqStateHolder.requestBandLevel(band, level)
    fun setEnabled(enabled: Boolean) = eqStateHolder.requestEnabled(enabled)

    fun applyPreset(preset: EqPreset) {
        val caps = eqStateHolder.capabilities.value ?: return
        val levels = preset.gains(caps.bandCount)
        levels.forEachIndexed { i, level ->
            eqStateHolder.requestBandLevel(i, level)
        }
    }
}

enum class EqPreset(val label: String) {
    FLAT("Flat"),
    ROCK("Rock"),
    POP("Pop"),
    JAZZ("Jazz"),
    CLASSICAL("Classical"),
    BASS_BOOST("Bass Boost"),
    VOCAL("Vocal");

    fun gains(bandCount: Int): List<Short> {
        val template = when (this) {
            FLAT      -> listOf(0, 0, 0, 0, 0)
            ROCK      -> listOf(5, 3, -1, 3, 5)
            POP       -> listOf(-1, 2, 5, 2, -1)
            JAZZ      -> listOf(4, 2, -1, 2, 5)
            CLASSICAL -> listOf(5, 3, -2, 3, 4)
            BASS_BOOST -> listOf(8, 5, 1, 0, 0)
            VOCAL     -> listOf(-2, -1, 3, 3, -1)
        }
        // The 5-point curve is stretched over however many bands the device has, interpolating
        // between points, so its shape is the same on a 3-band and a 10-band equalizer.
        if (bandCount <= 1) return List(bandCount.coerceAtLeast(0)) { (template[template.size / 2] * 100).toShort() }
        return List(bandCount) { band ->
            val pos = band.toFloat() / (bandCount - 1) * (template.size - 1)
            val lo = pos.toInt().coerceAtMost(template.size - 2)
            val t = pos - lo
            val db = template[lo] + (template[lo + 1] - template[lo]) * t
            (db * 100).roundToInt().toShort()
        }
    }
}
