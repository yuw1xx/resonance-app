package dev.yuwixx.resonance.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.yuwixx.resonance.data.model.SleepTimer
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerDialog(
    currentTimer: SleepTimer,
    onSetTimer: (SleepTimer) -> Unit,
    onSetAfterTracks: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedMinutes by remember { mutableIntStateOf(30) }
    var selectedTracks by remember { mutableIntStateOf(3) }
    var mode by remember { mutableStateOf("minutes") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Bedtime, null) },
        title = { Text("Sleep Timer") },
        text = {
            Column {
                AnimatedVisibility(visible = currentTimer !is SleepTimer.Off) {
                    Column {
                        Text(
                            "Timer active",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }

                Row {
                    FilterChip(
                        selected = mode == "minutes",
                        onClick = { mode = "minutes" },
                        label = { Text("Minutes") },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    FilterChip(
                        selected = mode == "tracks",
                        onClick = { mode = "tracks" },
                        label = { Text("After tracks") },
                    )
                }

                Spacer(Modifier.height(12.dp))

                AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        (fadeIn(tween(280, easing = EmphasizedDecelerate)) +
                            slideInVertically(tween(280, easing = EmphasizedDecelerate)) { it / 4 }) togetherWith
                            fadeOut(tween(210, easing = EmphasizedAccelerate))
                    },
                    label = "sleep_timer_mode",
                ) { currentMode ->
                    if (currentMode == "minutes") {
                        Column {
                            Text("Stop after $selectedMinutes min")
                            Slider(
                                value = selectedMinutes.toFloat(),
                                // Slider reports the raw continuous drag position via
                                // onValueChange — it only snaps the thumb visually, not the
                                // reported value — so round to the step grid ourselves
                                // (5-minute steps: 115-minute span / 22 steps = 5 exactly).
                                onValueChange = { selectedMinutes = (it / 5f).roundToInt() * 5 },
                                valueRange = 5f..120f,
                                steps = 22,
                            )
                        }
                    } else {
                        Column {
                            Text("Stop after $selectedTracks tracks")
                            Slider(
                                value = selectedTracks.toFloat(),
                                onValueChange = { selectedTracks = it.roundToInt() },
                                valueRange = 1f..20f,
                                steps = 18,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (mode == "minutes") {
                    onSetTimer(SleepTimer.Time(selectedMinutes, System.currentTimeMillis()))
                } else {
                    onSetAfterTracks(selectedTracks)
                }
                onDismiss()
            }) { Text("Set") }
        },
        dismissButton = {
            Row {
                if (currentTimer !is SleepTimer.Off) {
                    TextButton(onClick = {
                        onSetTimer(SleepTimer.Off)
                        onDismiss()
                    }) { Text("Cancel timer") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
