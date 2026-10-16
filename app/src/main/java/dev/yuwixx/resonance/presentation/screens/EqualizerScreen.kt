package dev.yuwixx.resonance.presentation.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yuwixx.resonance.data.service.EqStateHolder
import dev.yuwixx.resonance.presentation.components.EmphasizedDecelerate
import dev.yuwixx.resonance.presentation.components.SectionCard
import dev.yuwixx.resonance.presentation.viewmodel.EqPreset
import dev.yuwixx.resonance.presentation.viewmodel.EqualizerViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    onBack: () -> Unit,
    viewModel: EqualizerViewModel = hiltViewModel(),
) {
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val bandLevels by viewModel.bandLevels.collectAsStateWithLifecycle()
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()

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
            Crossfade(
                targetState = capabilities,
                animationSpec = tween(340, easing = EmphasizedDecelerate),
                label = "eq_capabilities",
            ) { caps ->
                if (caps == null) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Equalizer is not available on this device.\nStart playing a song to initialize it.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Enable Equalizer", style = MaterialTheme.typography.titleMedium)
                            Switch(checked = enabled, onCheckedChange = viewModel::setEnabled)
                        }

                        SectionCard(icon = Icons.Rounded.Tune, title = "Presets") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(EqPreset.entries) { preset ->
                                    val interactionSource = remember { MutableInteractionSource() }
                                    val isPressed by interactionSource.collectIsPressedAsState()
                                    val chipScale by animateFloatAsState(
                                        targetValue = if (isPressed) 0.92f else 1f,
                                        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                                        label = "eq_preset_scale",
                                    )
                                    AssistChip(
                                        onClick = {
                                            viewModel.applyPreset(preset)
                                            if (!enabled) viewModel.setEnabled(true)
                                        },
                                        label = { Text(preset.label) },
                                        enabled = caps.bandCount > 0,
                                        interactionSource = interactionSource,
                                        modifier = Modifier.scale(chipScale),
                                    )
                                }
                            }
                        }

                        SectionCard(icon = Icons.Rounded.GraphicEq, title = "Bands") {
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
    var isDragging by remember { mutableStateOf(false) }
    // Non-drag changes (preset application, programmatic reset) glide into place; an active
    // finger-drag snaps instantly so the thumb keeps 1:1 tracking with the touch.
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (isDragging) snap() else spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium),
        label = "band_slider_fraction",
    )
    val labelColor by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(280, easing = EmphasizedDecelerate),
        label = "band_slider_label_color",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            dbLabel,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            textAlign = TextAlign.Center,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .width(40.dp)
                // Readable and adjustable with TalkBack, like a regular slider.
                .semantics(mergeDescendants = true) {
                    contentDescription = "$freqLabel band, $dbLabel"
                    progressBarRangeInfo = ProgressBarRangeInfo(level.toFloat(), min.toFloat()..max.toFloat())
                    if (enabled) {
                        setProgress { target ->
                            onLevelChange(target.roundToInt().coerceIn(min.toInt(), max.toInt()).toShort())
                            true
                        }
                    } else {
                        disabled()
                    }
                }
                .pointerInput(enabled, min, max) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown()
                        isDragging = true
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
                        isDragging = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val trackColor = MaterialTheme.colorScheme.surfaceVariant
            val fillColor by animateColorAsState(
                targetValue = if (enabled) MaterialTheme.colorScheme.primary
                              else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                animationSpec = tween(280, easing = EmphasizedDecelerate),
                label = "band_slider_fill_color",
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackW = 6.dp.toPx()
                val cx = size.width / 2
                val trackH = size.height
                val thumbY = trackH * (1f - animatedFraction)

                drawRect(
                    color = trackColor,
                    topLeft = Offset(cx - trackW / 2, 0f),
                    size = Size(trackW, trackH),
                )
                drawRect(
                    color = fillColor,
                    topLeft = Offset(cx - trackW / 2, thumbY),
                    size = Size(trackW, trackH - thumbY),
                )
                drawCircle(
                    color = fillColor,
                    radius = 10.dp.toPx(),
                    center = Offset(cx, thumbY),
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
