package dev.yuwixx.resonance.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import dev.yuwixx.resonance.data.model.MixType
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.model.WaveformData
import kotlin.math.*
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.delay

val Emphasized = CubicBezierEasing(0.2f, 0.0f, 0f, 1.0f)
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

@Composable
fun MixArtwork(
    mixType: MixType,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
) {
    val (gradientColors, icon) = remember(mixType) {
        when (mixType) {
            MixType.FAVORITES -> listOf(Color(0xFFFF6B6B), Color(0xFFFFE66D)) to Icons.Rounded.Favorite
            MixType.TOP_ARTIST -> listOf(Color(0xFFA855F7), Color(0xFF6366F1)) to Icons.Rounded.Mic
            MixType.TOP_GENRE -> listOf(Color(0xFF10B981), Color(0xFF06B6D4)) to Icons.Rounded.LibraryMusic
            MixType.ERA -> listOf(Color(0xFFF59E0B), Color(0xFFEF4444)) to Icons.Rounded.History
            MixType.RECENTLY_LOVED -> listOf(Color(0xFFEC4899), Color(0xFF8B5CF6)) to Icons.Rounded.AutoAwesome
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    colors = gradientColors,
                    start = Offset.Zero,
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.15f),
            modifier = Modifier.fillMaxSize(0.75f),
        )
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.95f),
            modifier = Modifier.fillMaxSize(0.35f),
        )
    }
}
internal val EaseInOutSine = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

/** Icon, title and an optional trailing action above a section of content. */
@Composable
fun AppSectionHeader(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Tonal card around a labeled group of related controls. */
@Composable
fun SectionCard(
    icon: ImageVector?,
    title: String,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, style = titleStyle, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** Divider between two groups in one [SectionCard]; use with [SectionSubHeader]. */
@Composable
fun SectionDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
    )
}

/** Small label introducing a sub-group within a [SectionCard], paired with [SectionDivider]. */
@Composable
fun SectionSubHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

/** Large title as the first item of a list, so it scrolls away with the content. Pair it with
 *  a transparent TopAppBar for the actions. */
@Composable
fun BigScreenTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
    )
}

data class AppearanceConfig(
    val compactListMode: Boolean = false,
    val showDurationInList: Boolean = false,
    val playerArtworkShape: String = "ROUNDED",
    val lyricAlignment: String = "CENTER",
    val seekbarColor: String = "PRIMARY",
    val tintedNavBar: Boolean = false,
    val amoledMode: Boolean = false,
    val floatingNavBar: Boolean = false,
    val navLabelVisibility: String = "ALWAYS",
    val playlistGridColumns: Int = 2,
    val showAlbumInList: Boolean = false,
    val listArtworkSize: String = "MEDIUM",
    val showRemainingTime: Boolean = false,
    val showNextSongInPlayer: Boolean = false,
    val lyricsLineSpacing: String = "NORMAL",
    val miniPlayerShowProgress: Boolean = true,
    val miniPlayerShowSkipBtn: Boolean = true,
    val showEqualizerInPlayer: Boolean = true,
    val hapticEnabled: Boolean = true,
    val showBitrateInfo: Boolean = false,
    val lyricsFontFamily: String = "DEFAULT",
)

val LocalAppearanceConfig = compositionLocalOf { AppearanceConfig() }

// Shared motion helpers, so every screen uses the same specs.

/** Swaps status content (an icon, a status row, a connection card): the new state pops in with
 *  a spring, the old one fades and shrinks out. Same motion as the play/pause icon. */
@Composable
fun <T> AnimatedStateSwap(
    targetState: T,
    modifier: Modifier = Modifier,
    label: String = "animated_state_swap",
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable (T) -> Unit,
) {
    val popIn = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        contentAlignment = contentAlignment,
        transitionSpec = {
            (scaleIn(popIn, initialScale = 0.85f) +
                fadeIn(tween(280, easing = EmphasizedDecelerate))) togetherWith
                (scaleOut(tween(180, easing = EmphasizedAccelerate), targetScale = 0.85f) +
                    fadeOut(tween(180, easing = EmphasizedAccelerate)))
        },
        label = label,
    ) { state -> content(state) }
}

/** Fades and slides between a prompt (such as a permission request) and the real content. */
@Composable
fun AnimatedPermissionGate(
    granted: Boolean,
    modifier: Modifier = Modifier,
    prompt: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    AnimatedContent(
        targetState = granted,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(340, easing = EmphasizedDecelerate)) +
                slideInVertically(tween(340, easing = EmphasizedDecelerate)) { it / 4 }) togetherWith
                fadeOut(tween(210, easing = EmphasizedAccelerate))
        },
        label = "animated_permission_gate",
    ) { isGranted -> if (isGranted) content() else prompt() }
}

// Lists can't tell an item's first appearance from scrolling back into view, so only the
// first screenful animates; deeper items just appear.
private const val ENTRANCE_LIMIT = 14

/**
 * Staggered fade and scale-in for the first screenful of a list, 40ms apart. Use it after
 * `Modifier.animateItem()`. Animates in graphicsLayer only, so it never recomposes.
 */
@Composable
fun Modifier.staggeredEntrance(index: Int, capMs: Int = 320): Modifier {
    if (index >= ENTRANCE_LIMIT) return this
    val progress = remember(index) { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    LaunchedEffect(index) {
        delay((index * 40L).coerceAtMost(capMs.toLong()))
        progress.animateTo(1f, spec)
    }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p
        val scale = 0.94f + 0.06f * p
        scaleX = scale
        scaleY = scale
    }
}

/** Playback progress (0..1) as an animated State. Read it only while drawing, so position
 *  ticks redraw the seekbar instead of recomposing it. */
@Composable
private fun rememberAnimatedProgress(position: State<Long>, durationMs: Long): State<Float> {
    val progress = remember { Animatable(0f) }
    val currentDuration by rememberUpdatedState(durationMs)
    LaunchedEffect(position) {
        snapshotFlow {
            val d = currentDuration
            if (d > 0) (position.value.toFloat() / d).coerceIn(0f, 1f) else 0f
        }.collect { target ->
            // Big jumps (a seek, a track change) snap; normal ticks glide.
            if (abs(target - progress.value) > 0.05f) progress.snapTo(target)
            else progress.animateTo(target, tween(durationMillis = 170, easing = LinearEasing))
        }
    }
    return progress.asState()
}

/** Exposes the seekbar to TalkBack as an adjustable slider, updated once a second. */
@Composable
private fun Modifier.seekSemantics(position: State<Long>, durationMs: Long, onSeek: (Long) -> Unit): Modifier {
    val seconds by remember(position) { derivedStateOf { position.value / 1000 } }
    val fraction = if (durationMs > 0) (seconds * 1000f / durationMs).coerceIn(0f, 1f) else 0f
    return semantics {
        contentDescription = "Seek"
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        setProgress { target ->
            if (durationMs <= 0) return@setProgress false
            onSeek((target.coerceIn(0f, 1f) * durationMs).toLong())
            true
        }
    }
}

/**
 * Tap or horizontal drag to seek. The thumb follows the finger, and one seek is issued on release.
 * Vertical swipes are left alone so they reach the player sheet (swipe down to close).
 */
private fun Modifier.seekGestures(
    durationMs: () -> Long,
    onDragChange: (dragging: Boolean, fraction: Float) -> Unit,
    onSeek: () -> (Long) -> Unit,
    onTick: () -> Unit,
): Modifier = pointerInput(Unit) {
    fun fractionAt(x: Float) = (x / size.width).coerceIn(0f, 1f)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
        if (slop == null) {
            // Released without moving: a tap. Anything else was a vertical gesture.
            val up = currentEvent.changes.firstOrNull { it.id == down.id }
            if (up != null && !up.pressed && !up.isConsumed) {
                val fraction = fractionAt(down.position.x)
                onTick()
                onSeek()((fraction * durationMs()).toLong())
                onDragChange(false, fraction)
            }
            return@awaitEachGesture
        }
        var fraction = fractionAt(slop.position.x)
        onDragChange(true, fraction)
        onTick()
        horizontalDrag(slop.id) { change ->
            fraction = fractionAt(change.position.x)
            onDragChange(true, fraction)
            change.consume()
        }
        onSeek()((fraction * durationMs()).toLong())
        onTick()
        onDragChange(false, fraction)
    }
}

@Composable
fun MaterialYou3Seekbar(
    position: State<Long>,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    playedColor: Color = MaterialTheme.colorScheme.primary,
    remainingColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
) {
    val haptics = LocalHapticFeedback.current
    val config = LocalAppearanceConfig.current
    val progress = rememberAnimatedProgress(position, durationMs)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentDuration by rememberUpdatedState(durationMs)
    val hapticsEnabled by rememberUpdatedState(config.hapticEnabled)

    var isDragging by remember { mutableStateOf(false) }
    val dragProgress = remember { mutableFloatStateOf(0f) }

    // Thick enough for the rounded ends to show, like the Android 13 media notification seekbar.
    val activeHeight = animateDpAsState(
        targetValue = if (isDragging) 22.dp else 14.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "m3_height"
    )
    // Always shown: on a track this thick, the color change alone doesn't read as a handle.
    val thumbHeight = animateDpAsState(
        targetValue = if (isDragging) 32.dp else 22.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "m3_thumb_height"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .seekSemantics(position, durationMs) { currentOnSeek(it) }
            .seekGestures(
                durationMs = { currentDuration },
                onDragChange = { dragging, fraction -> isDragging = dragging; dragProgress.floatValue = fraction },
                onSeek = { currentOnSeek },
                onTick = { if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
            )
    ) {
        val displayProgress = if (isDragging) dragProgress.floatValue else progress.value
        val width = size.width
        val height = activeHeight.value.toPx()
        val cy = size.height / 2f
        val splitX = displayProgress * width
        val thumbWidth = 4.dp.toPx()
        // A gap on both sides of the thumb so it reads as a separate handle.
        val gap = 6.dp.toPx()
        val playedEnd = (splitX - thumbWidth / 2f - gap).coerceAtLeast(0f)
        val remainingStart = (splitX + thumbWidth / 2f + gap).coerceAtMost(width)

        if (playedEnd > 0f) {
            drawRoundRect(
                color = playedColor,
                topLeft = Offset(0f, cy - height / 2f),
                size = Size(playedEnd, height),
                cornerRadius = CornerRadius(height / 2f)
            )
        }
        if (remainingStart < width) {
            drawRoundRect(
                color = remainingColor,
                topLeft = Offset(remainingStart, cy - height / 2f),
                size = Size(width - remainingStart, height),
                cornerRadius = CornerRadius(height / 2f)
            )
        }

        val thumbH = thumbHeight.value.toPx()
        drawRoundRect(
            color = playedColor,
            topLeft = Offset(splitX - thumbWidth / 2f, cy - thumbH / 2f),
            size = Size(thumbWidth, thumbH),
            cornerRadius = CornerRadius(thumbWidth / 2f)
        )
    }
}

@Composable
fun WaveformSeekbar(
    waveformData: WaveformData?,
    position: State<Long>,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    playedColor: Color = MaterialTheme.colorScheme.primary,
    remainingColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
    barWidthDp: Dp = 2.5.dp,
    barSpacingDp: Dp = 1.5.dp,
    minHeightFraction: Float = 0.12f,
) {
    val haptics = LocalHapticFeedback.current
    val config = LocalAppearanceConfig.current
    val progress = rememberAnimatedProgress(position, durationMs)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentDuration by rememberUpdatedState(durationMs)
    val hapticsEnabled by rememberUpdatedState(config.hapticEnabled)

    // Read only while drawing, so breathing costs a redraw per frame, not a recomposition. It runs
    // only while playing; paused, it would redraw for nothing.
    val breathe = remember { Animatable(1f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            breathe.animateTo(1f, tween(600, easing = EaseInOutSine))
            return@LaunchedEffect
        }
        while (true) {
            breathe.animateTo(1.04f, tween(1260, easing = EaseInOutSine))
            breathe.animateTo(0.96f, tween(2520, easing = EaseInOutSine))
        }
    }
    val playingBlend = animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "amp_blend",
    )

    var isDragging by remember { mutableStateOf(false) }
    val dragProgress = remember { mutableFloatStateOf(0f) }

    val amplitudes = remember(waveformData) {
        waveformData?.amplitudes ?: FloatArray(300) { i ->
            val t = i / 300f
            val base = abs(sin(t * PI.toFloat() * 14f)) * 0.5f
            val detail = abs(sin(t * PI.toFloat() * 43f)) * 0.25f
            val envelope = sin(t * PI.toFloat()).pow(0.4f)
            (base + detail) * envelope + 0.08f
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .seekSemantics(position, durationMs) { currentOnSeek(it) }
            .seekGestures(
                durationMs = { currentDuration },
                onDragChange = { dragging, fraction -> isDragging = dragging; dragProgress.floatValue = fraction },
                onSeek = { currentOnSeek },
                onTick = { if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
            ),
    ) {
        val displayProgress = if (isDragging) dragProgress.floatValue else progress.value
        val ampMultiplier = 1f + (breathe.value - 1f) * playingBlend.value
        val barW = barWidthDp.toPx()
        val gap = barSpacingDp.toPx()
        val step = barW + gap
        val count = (size.width / step).toInt().coerceAtLeast(1)
        val cy = size.height / 2f
        val splitX = displayProgress * size.width

        for (i in 0 until count) {
            val barCX = i * step + barW / 2f

            val ampIdx = (i.toFloat() / count * amplitudes.size).toInt()
                .coerceIn(0, amplitudes.lastIndex)
            val rawAmp = amplitudes[ampIdx].coerceAtLeast(minHeightFraction)

            val distFromSplit = abs(barCX - splitX) / size.width
            val lift = if (distFromSplit < 0.04f) {
                val t = 1f - (distFromSplit / 0.04f)
                1f + t * t * 0.55f
            } else 1f

            val barH = (rawAmp * ampMultiplier * lift * size.height)
                .coerceIn(size.height * minHeightFraction, size.height * 0.95f)

            drawLine(
                color = if (barCX <= splitX) playedColor else remainingColor,
                start = Offset(barCX, cy - barH / 2f),
                end = Offset(barCX, cy + barH / 2f),
                strokeWidth = barW,
                cap = StrokeCap.Round,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongCard(
    song: Song,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onLeadingClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    onArtworkMissing: (suspend () -> String?)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (isPlaying || isSelected) 1.015f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "song_scale",
    )

    val appearanceConfig = LocalAppearanceConfig.current

    val targetColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        isPlaying -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
        else -> Color.Transparent
    }
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "song_color",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { lc ->
                    {
                        if (appearanceConfig.hapticEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        lc()
                    }
                },
            ),
        color = animatedColor,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 12.dp,
                vertical = if (appearanceConfig.compactListMode) 4.dp else 8.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artworkSizeDp = when (appearanceConfig.listArtworkSize) {
                "SMALL" -> 40.dp
                "LARGE" -> 64.dp
                else -> 52.dp
            }
            Box(
                modifier = Modifier
                    .size(artworkSizeDp)
                    .clip(MaterialTheme.shapes.medium)
                    .let { base ->
                        if (onLeadingClick != null) {
                            base.clickable {
                                if (appearanceConfig.hapticEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLeadingClick()
                            }
                        } else base
                    },
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = isSelected,
                    transitionSpec = {
                        fadeIn(tween(280)) + scaleIn(tween(280), initialScale = 0.8f) togetherWith
                                fadeOut(tween(210)) + scaleOut(tween(210), targetScale = 0.8f)
                    },
                    label = "leading_selection"
                ) { selected ->
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                Icons.Rounded.Check,
                                null,
                                modifier = Modifier.align(Alignment.Center),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    } else {
                        ArtworkImage(
                            uri = song.artworkUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 12.dp,
                            onArtworkMissing = onArtworkMissing,
                        )
                    }
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                    color = if (isPlaying || isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.displayArtist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (appearanceConfig.showAlbumInList && song.album.isNotEmpty()) {
                    Text(
                        text = song.album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (trailingContent != null) {
                trailingContent()
            } else if (isPlaying) {
                PlayingBarsIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (appearanceConfig.showDurationInList) {
                Text(
                    text = formatDuration(song.duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ArtworkImage(
    uri: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    // Called once if `uri` fails to load, to look up a fallback image URL.
    onArtworkMissing: (suspend () -> String?)? = null,
) {
    var primaryFailed by remember(uri) { mutableStateOf(false) }
    var fallbackAttempted by remember(uri) { mutableStateOf(false) }
    var fallbackUrl by remember(uri) { mutableStateOf<String?>(null) }

    LaunchedEffect(uri, primaryFailed) {
        if (primaryFailed && !fallbackAttempted && onArtworkMissing != null) {
            fallbackAttempted = true
            fallbackUrl = onArtworkMissing()
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = fallbackUrl ?: uri,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onState = { state ->
                if (!primaryFailed && fallbackUrl == null && state is AsyncImagePainter.State.Error) {
                    primaryFailed = true
                }
            },
        )
    }
}

/** Whether audio is playing, so decorative animations can stop while paused. */
val LocalPlaybackActive = compositionLocalOf { true }

@Composable
fun PlayingBarsIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    barCount: Int = 3,
) {
    if (!LocalPlaybackActive.current) {
        // Paused: static bars, no per-frame animation.
        Canvas(modifier = modifier) {
            val barW = size.width / (barCount * 2 - 1)
            val cornerRadius = CornerRadius(barW / 2f)
            for (i in 0 until barCount) {
                val h = size.height * if (i % 2 == 0) 0.45f else 0.75f
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * barW * 2f, (size.height - h) / 2f),
                    size = Size(barW, h),
                    cornerRadius = cornerRadius,
                )
            }
        }
        return
    }
    val sharedTransition = rememberInfiniteTransition(label = "bars_shared")
    // Read only in the draw phase: redraws per frame without recomposing.
    val baseAnim = sharedTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1680, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bars_base",
    )
    Canvas(modifier = modifier) {
        val barW = size.width / (barCount * 2 - 1)
        val cornerRadius = CornerRadius(barW / 2f)
        for (i in 0 until barCount) {
            val phase = (baseAnim.value + i / barCount.toFloat()) % 1f
            val sineVal = (sin(phase * 2 * PI).toFloat() + 1f) / 2f
            val h = (size.height * (0.25f + sineVal * 0.75f)).coerceAtLeast(barW)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * barW * 2f, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = cornerRadius,
            )
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

// "2h 15m", for listening totals.
fun formatListenDuration(ms: Long): String {
    if (ms <= 0) return "0m"
    val totalMinutes = ms / 60000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

// Codec label from a MIME type.
fun audioFormatLabel(mimeType: String): String = when {
    mimeType.contains("flac", ignoreCase = true) -> "FLAC"
    mimeType.contains("opus", ignoreCase = true) -> "Opus"
    mimeType.contains("ogg", ignoreCase = true) || mimeType.contains("vorbis", ignoreCase = true) -> "OGG"
    mimeType.contains("mp4", ignoreCase = true) || mimeType.contains("aac", ignoreCase = true) -> "AAC"
    else -> "MP3"
}

// Each artist is its own tap target, so "A, B" from an "A/B" tag can open either artist.
@Composable
fun MultiArtistText(
    artists: List<String>,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = 1,
) {
    // Remembered because the player recomposes often while the artist list rarely changes.
    val text = remember(artists, onArtistClick) {
        // Plain styles: without them, LinkAnnotation draws blue underlined links.
        val linkStyles = TextLinkStyles(style = SpanStyle(color = Color.Unspecified, textDecoration = TextDecoration.None))
        buildAnnotatedString {
            artists.forEachIndexed { i, artist ->
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "artist_$i",
                        styles = linkStyles,
                        linkInteractionListener = { onArtistClick(artist) },
                    )
                ) {
                    append(artist)
                }
                if (i != artists.lastIndex) append(", ")
            }
        }
    }
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

private data class ConfettiPiece(
    val x: Float,
    val yOffset: Float,
    val speed: Float,
    val sizeDp: Float,
    val color: Color,
    val rotationsPerFall: Float,
)

@Composable
fun ConfettiOverlay(modifier: Modifier = Modifier) {
    val pieces = remember {
        val colors = listOf(
            Color(0xFFFF1744.toInt()), Color(0xFFFF9100.toInt()), Color(0xFFFFD600.toInt()),
            Color(0xFF00E676.toInt()), Color(0xFF2979FF.toInt()), Color(0xFFD500F9.toInt()),
            Color(0xFFFF80AB.toInt()), Color(0xFF69F0AE.toInt()), Color(0xFFFFFF00.toInt()),
        )
        List(60) { i ->
            ConfettiPiece(
                x = Random.nextFloat(),
                yOffset = Random.nextFloat(),
                speed = 0.18f + Random.nextFloat() * 0.3f,
                sizeDp = 5f + Random.nextFloat() * 8f,
                color = colors[i % colors.size],
                rotationsPerFall = (1.5f + Random.nextFloat() * 2f) * if (Random.nextBoolean()) 1f else -1f,
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "confetti")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12600, easing = LinearEasing)),
        label = "confetti_t",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        pieces.forEach { p ->
            // Only ever grows, so rotation stays continuous across cycles.
            val accumulated = p.yOffset + progress * p.speed
            val rawY = accumulated % 1.0f
            val half = p.sizeDp.dp.toPx() / 2f
            val cx = (p.x + sin(accumulated * PI.toFloat() * 1.4f) * 0.028f) * size.width
            val cy = rawY * (size.height + half * 2f)
            // Fade in over the top 6% of the screen to hide the wrap-around.
            val alpha = (rawY / 0.06f).coerceIn(0f, 0.9f)

            withTransform({
                translate(cx, cy)
                rotate(accumulated * p.rotationsPerFall * 360f)
            }) {
                drawRect(
                    color = p.color,
                    topLeft = Offset(-half, -half * 0.5f),
                    size = Size(half * 2f, half),
                    alpha = alpha,
                )
            }
        }
    }
}
