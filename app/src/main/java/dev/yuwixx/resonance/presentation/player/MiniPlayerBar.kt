// Collapsed player content: artwork, title/artist, play-pause and skip. Tap or drag up to
// open the full player; swipe sideways to skip (the neighbouring song peeks in as you drag).
package dev.yuwixx.resonance.presentation.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.presentation.components.ArtworkImage
import dev.yuwixx.resonance.presentation.components.LocalAppearanceConfig
import dev.yuwixx.resonance.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MiniPlayerBar(
    playerViewModel: PlayerViewModel,
    metrics: MiniPlayerMetrics,
    onExpand: () -> Unit,
) {
    val song by playerViewModel.currentSong.collectAsStateWithLifecycle()
    val isPlaying by playerViewModel.isPlaying.collectAsStateWithLifecycle()
    val durationMs by playerViewModel.durationMs.collectAsStateWithLifecycle()
    val position = playerViewModel.positionMs.collectAsStateWithLifecycle()
    val nextSong by playerViewModel.nextSong.collectAsStateWithLifecycle()
    val previousSong by playerViewModel.previousSong.collectAsStateWithLifecycle()
    val config = LocalAppearanceConfig.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val current = song ?: return

    val morph = LocalArtworkMorph.current
    SideEffect { morph?.miniCorner = if (metrics.corner == 0.dp) 6.dp else 12.dp }

    // Horizontal swipe-to-skip. The row follows the finger; past 30% of the width it flings
    // out and the track changes, otherwise it springs back.
    var widthPx by remember { mutableFloatStateOf(1f) }
    val swipe = remember { Animatable(0f) }
    val motion = MaterialTheme.motionScheme
    val spatial = motion.defaultSpatialSpec<Float>()
    val fast = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val dragState = rememberDraggableState { delta ->
        scope.launch { swipe.snapTo(swipe.value + delta) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .clickable(onClick = onExpand)
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStopped = { velocity ->
                    val offset = swipe.value
                    val passed = abs(offset) > widthPx * 0.3f || abs(velocity) > 2500f
                    val towardsNext = offset < 0
                    val target = if (towardsNext) nextSong else previousSong
                    if (passed && (target != null || !towardsNext)) {
                        if (config.hapticEnabled) haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                        swipe.animateTo(if (towardsNext) -widthPx else widthPx, fast)
                        if (towardsNext) playerViewModel.skipNext() else playerViewModel.skipPrevious()
                        swipe.snapTo(0f)
                    } else {
                        swipe.animateTo(0f, spatial)
                    }
                },
            )
    ) {
        // Peek of the song the swipe would switch to.
        val peekSong = if (swipe.value < 0) nextSong else if (swipe.value > 0) previousSong else null
        if (peekSong != null) {
            MiniPlayerRow(
                song = peekSong,
                metrics = metrics,
                modifier = Modifier.graphicsLayer {
                    val o = swipe.value
                    translationX = if (o < 0) widthPx + o else o - widthPx
                    alpha = (abs(o) / widthPx * 2f).coerceIn(0f, 1f)
                },
                trailing = {},
            )
        }

        MiniPlayerRow(
            song = current,
            metrics = metrics,
            artworkModifier = morph?.miniModifier() ?: Modifier,
            modifier = Modifier.graphicsLayer {
                translationX = swipe.value
                alpha = 1f - (abs(swipe.value) / widthPx).coerceIn(0f, 1f) * 0.8f
            },
            trailing = {
                FilledTonalIconButton(
                    onClick = { playerViewModel.playPause() },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(48.dp),
                ) {
                    AnimatedContent(
                        targetState = isPlaying,
                        transitionSpec = {
                            (scaleIn(motion.fastSpatialSpec(), initialScale = 0.6f) +
                                fadeIn(motion.fastEffectsSpec())) togetherWith
                                (scaleOut(motion.fastSpatialSpec(), targetScale = 0.6f) +
                                    fadeOut(motion.fastEffectsSpec()))
                        },
                        label = "mini_play",
                    ) { playing ->
                        Icon(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                        )
                    }
                }
                if (config.miniPlayerShowSkipBtn) {
                    IconButton(
                        onClick = { playerViewModel.skipNext() },
                        shapes = IconButtonDefaults.shapes(),
                        enabled = nextSong != null,
                    ) {
                        Icon(Icons.Rounded.SkipNext, "Next")
                    }
                }
            },
        )

        if (config.miniPlayerShowProgress) {
            // Wavy while playing, flat when paused — the M3 Expressive "it's alive" cue.
            val amplitude by animateFloatAsState(
                targetValue = if (isPlaying) 1f else 0f,
                animationSpec = motion.slowEffectsSpec(),
                label = "mini_wave",
            )
            LinearWavyProgressIndicator(
                progress = {
                    val d = durationMs
                    if (d > 0) (position.value.toFloat() / d).coerceIn(0f, 1f) else 0f
                },
                amplitude = { amplitude },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 3.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}

@Composable
private fun MiniPlayerRow(
    song: Song,
    metrics: MiniPlayerMetrics,
    modifier: Modifier = Modifier,
    artworkModifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            artworkModifier
                .size(48.dp)
                .clip(RoundedCornerShape(if (metrics.corner == 0.dp) 6.dp else 12.dp))
        ) {
            ArtworkImage(
                uri = song.artworkUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 0.dp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.displayArtist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}
