package dev.yuwixx.resonance.presentation.viewmodel

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
        return when {
            bandCount <= template.size -> template.take(bandCount).map { (it * 100).toShort() }
            else -> {
                val out = MutableList(bandCount) { 0.toShort() }
                template.forEachIndexed { i, v ->
                    val idx = (i.toFloat() / (template.size - 1) * (bandCount - 1)).toInt()
                    out[idx.coerceIn(0, bandCount - 1)] = (v * 100).toShort()
                }
                out
            }
        }
    }
}
