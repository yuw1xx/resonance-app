package dev.yuwixx.resonance.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EqStateHolder @Inject constructor() {

    data class EqCapabilities(
        val bandCount: Int,
        val centerFreqs: List<Int>,
        val minLevel: Short,
        val maxLevel: Short,
    )

    private val _capabilities = MutableStateFlow<EqCapabilities?>(null)
    val capabilities: StateFlow<EqCapabilities?> = _capabilities.asStateFlow()

    private val _bandLevels = MutableStateFlow<List<Short>>(emptyList())
    val bandLevels: StateFlow<List<Short>> = _bandLevels.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    var onBandLevelChanged: ((band: Int, level: Short) -> Unit)? = null
    var onEnabledChanged: ((Boolean) -> Unit)? = null

    fun publishCapabilities(caps: EqCapabilities) {
        _capabilities.value = caps
    }

    fun publishBandLevels(levels: List<Short>) {
        _bandLevels.value = levels
    }

    fun publishEnabled(enabled: Boolean) {
        _enabled.value = enabled
    }

    fun requestBandLevel(band: Int, level: Short) {
        onBandLevelChanged?.invoke(band, level)
    }

    fun requestEnabled(enabled: Boolean) {
        onEnabledChanged?.invoke(enabled)
    }
}
