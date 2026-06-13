package dev.yuwixx.resonance.presentation.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.yuwixx.resonance.data.service.EqStateHolder
import dev.yuwixx.resonance.presentation.viewmodel.EqPreset
import dev.yuwixx.resonance.presentation.viewmodel.EqualizerViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    onBack: () -> Unit,
    viewModel: EqualizerViewModel = hiltViewModel(),
) {
    val capabilities by viewModel.capabilities.collectAsState()
    val bandLevels by viewModel.bandLevels.collectAsState()
    val enabled by viewModel.enabled.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Equalizer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (capabilities == null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        "Equalizer is not available on this device.\nStart playing a song to initialize it.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            } else {
                val caps = capabilities!!

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Enable Equalizer", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = enabled, onCheckedChange = viewModel::setEnabled)
                }

                HorizontalDivider()

                Text(
                    "Presets",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(EqPreset.entries) { preset ->
                        AssistChip(
                            onClick = {
                                viewModel.applyPreset(preset)
                                if (!enabled) viewModel.setEnabled(true)
                            },
                            label = { Text(preset.label) },
                            enabled = caps.bandCount > 0,
                        )
                    }
                }

                HorizontalDivider()

                Text(
                    "Bands",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    bandLevels.forEachIndexed { index, level ->
                        if (index >= caps.bandCount) return@forEachIndexed
                        val freqHz = caps.centerFreqs.getOrNull(index) ?: 0
                        BandSlider(
                            band = index,
                            level = level,
                            min = caps.minLevel,
                            max = caps.maxLevel,
                            freqLabel = formatFreq(freqHz),
                            enabled = enabled,
                            onLevelChange = { newLevel -> viewModel.setBandLevel(index, newLevel) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BandSlider(
    band: Int,
    level: Short,
    min: Short,
    max: Short,
    freqLabel: String,
    enabled: Boolean,
    onLevelChange: (Short) -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = (max - min).toFloat().coerceAtLeast(1f)
    val fraction = ((level - min) / range).coerceIn(0f, 1f)
    val dbLabel = "${(level / 100f).roundToInt()} dB"

    val currentLevel = rememberUpdatedState(level)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            dbLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .width(40.dp)
                .pointerInput(enabled, min, max) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown()
                        var localLevel = currentLevel.value
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                val delta = change.positionChange().y
                                val levelDelta = -(delta / size.height * range)
                                val newLevel = (localLevel + levelDelta)
                                    .toInt()
                                    .toShort()
                                    .coerceIn(min, max)
                                if (newLevel != localLevel) {
                                    onLevelChange(newLevel)
                                    localLevel = newLevel
                                }
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val trackColor = MaterialTheme.colorScheme.surfaceVariant
            val fillColor = if (enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val trackW = 6.dp.toPx()
                val cx = size.width / 2
                val trackH = size.height
                val thumbY = trackH * (1f - fraction)

                drawRect(
                    color = trackColor,
                    topLeft = androidx.compose.ui.geometry.Offset(cx - trackW / 2, 0f),
                    size = androidx.compose.ui.geometry.Size(trackW, trackH),
                )
                drawRect(
                    color = fillColor,
                    topLeft = androidx.compose.ui.geometry.Offset(cx - trackW / 2, thumbY),
                    size = androidx.compose.ui.geometry.Size(trackW, trackH - thumbY),
                )
                drawCircle(
                    color = fillColor,
                    radius = 10.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(cx, thumbY),
                )
            }
        }

        Text(
            freqLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatFreq(hz: Int): String = when {
    hz >= 1000 -> {
        val khz = hz / 1000f
        if (khz == khz.toLong().toFloat()) "${khz.toInt()}kHz" else "${khz}kHz"
    }
    else -> "${hz}Hz"
}

private fun Short.coerceIn(min: Short, max: Short): Short =
    this.toInt().coerceIn(min.toInt(), max.toInt()).toShort()
