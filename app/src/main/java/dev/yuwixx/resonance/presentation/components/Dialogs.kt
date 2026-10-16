package dev.yuwixx.resonance.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.yuwixx.resonance.data.model.SleepTimer
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val MINUTE_PRESETS = listOf(15, 30, 45, 60, 90)

/** Sleep timer: quick presets, "end of track", a custom duration or track count, and an
 *  optional 30-second fade-out. The timer runs in the playback service, so it keeps counting
 *  after the app is closed. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun SleepTimerDialog(
    currentTimer: SleepTimer,
    onSetTimer: (SleepTimer) -> Unit,
    onSetAfterTracks: (tracks: Int, fadeOut: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf("minutes") }
    var selectedMinutes by rememberSaveable { mutableIntStateOf(30) }
    var selectedTracks by rememberSaveable { mutableIntStateOf(3) }
    var fadeOut by rememberSaveable {
        mutableStateOf(
            when (currentTimer) {
                is SleepTimer.Time -> currentTimer.fadeOut
                is SleepTimer.Tracks -> currentTimer.fadeOut
                else -> true
            }
        )
    }
    val motion = MaterialTheme.motionScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Bedtime, null) },
        title = { Text("Sleep timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AnimatedVisibility(
                    visible = currentTimer !is SleepTimer.Off,
                    enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                    exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
                ) {
                    ActiveTimerCard(currentTimer)
                }

                // Quick picks: one tap sets the timer and closes the dialog.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MINUTE_PRESETS.forEach { minutes ->
                        SuggestionChip(
                            onClick = {
                                onSetTimer(SleepTimer.Time(minutes, System.currentTimeMillis(), fadeOut))
                                onDismiss()
                            },
                            label = { Text(if (minutes < 60) "$minutes min" else "${minutes / 60}h${if (minutes % 60 > 0) " ${minutes % 60}" else ""}") },
                        )
                    }
                    SuggestionChip(
                        onClick = { onSetAfterTracks(1, fadeOut); onDismiss() },
                        label = { Text("End of track") },
                        icon = { Icon(Icons.Rounded.Timer, null, Modifier.size(18.dp)) },
                    )
                }

                HorizontalDivider()

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = mode == "minutes",
                        onClick = { mode = "minutes" },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Custom time") }
                    SegmentedButton(
                        selected = mode == "tracks",
                        onClick = { mode = "tracks" },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("After tracks") }
                }

                AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        (fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 4 }) togetherWith
                            fadeOut(motion.fastEffectsSpec())
                    },
                    label = "sleep_timer_mode",
                ) { currentMode ->
                    if (currentMode == "minutes") {
                        Column {
                            Text("Stop after $selectedMinutes min", style = MaterialTheme.typography.titleMedium)
                            Slider(
                                value = selectedMinutes.toFloat(),
                                // Slider reports the raw drag position (it only snaps the thumb
                                // visually), so round to the 5-minute grid ourselves.
                                onValueChange = { selectedMinutes = (it / 5f).roundToInt() * 5 },
                                valueRange = 5f..180f,
                                steps = 34,
                            )
                        }
                    } else {
                        Column {
                            Text(
                                if (selectedTracks == 1) "Stop after this track" else "Stop after $selectedTracks tracks",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Slider(
                                value = selectedTracks.toFloat(),
                                onValueChange = { selectedTracks = it.roundToInt() },
                                valueRange = 1f..20f,
                                steps = 18,
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Fade out", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Lower the volume over the last 30 seconds",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = fadeOut, onCheckedChange = { fadeOut = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (mode == "minutes") {
                    onSetTimer(SleepTimer.Time(selectedMinutes, System.currentTimeMillis(), fadeOut))
                } else {
                    onSetAfterTracks(selectedTracks, fadeOut)
                }
                onDismiss()
            }) { Text("Start") }
        },
        dismissButton = {
            Row {
                if (currentTimer !is SleepTimer.Off) {
                    TextButton(onClick = {
                        onSetTimer(SleepTimer.Off)
                        onDismiss()
                    }) { Text("Turn off") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun ActiveTimerCard(timer: SleepTimer) {
    // Ticks once a second while a time-based timer is showing.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer) {
        while (timer is SleepTimer.Time) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val label = when (timer) {
        is SleepTimer.Time -> {
            val remaining = ((timer.endsAt - now) / 1000).coerceAtLeast(0)
            "%d:%02d remaining".format(remaining / 60, remaining % 60)
        }
        is SleepTimer.Tracks -> if (timer.tracksLeft == 1) "Stopping after this track" else "Stopping after ${timer.tracksLeft} tracks"
        SleepTimer.Off -> ""
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Timer, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
