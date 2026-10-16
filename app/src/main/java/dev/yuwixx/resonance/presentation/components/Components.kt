package dev.yuwixx.resonance.presentation.components

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import dev.yuwixx.resonance.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlin.math.*
import kotlin.math.pow
import kotlin.random.Random

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
            MixType.FAVORITES      -> listOf(Color(0xFFFF6B6B), Color(0xFFFFE66D)) to Icons.Rounded.Favorite
            MixType.TOP_ARTIST     -> listOf(Color(0xFFA855F7), Color(0xFF6366F1)) to Icons.Rounded.Mic
            MixType.TOP_GENRE      -> listOf(Color(0xFF10B981), Color(0xFF06B6D4)) to Icons.Rounded.LibraryMusic
            MixType.ERA            -> listOf(Color(0xFFF59E0B), Color(0xFFEF4444)) to Icons.Rounded.History
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

/** Shared "icon + title + optional trailing action" header used to introduce a section of
 *  content (a horizontal-scroll row, a settings group, a card). One look for this concept
 *  across the whole app instead of every screen inventing its own. */
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

/** Shared tonal card wrapping a labeled section of related controls — the grouping unit for
 *  "several controls that belong together" (used by Resonance Share's Nearby/Internet Link
 *  sections, and retrofitted onto Settings/Equalizer to replace ungrouped flat lists). */
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

/** Divider between two logical groups sharing one [SectionCard] — use with [SectionSubHeader]
 *  to join related sub-sections into a single continuous card instead of stacking separate
 *  cards with a gap between them. */
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

/** Big, centered, bold screen title meant to be the first item in a scrolling list — it
 *  scrolls away with the content instead of staying pinned behind a filled app-bar
 *  background. Pair with a transparent TopAppBar holding just the actions/back button. */
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

internal const val M3_SHORT2  = 140
internal const val M3_SHORT4  = 280
internal const val M3_MEDIUM2 = 420
internal const val M3_MEDIUM4 = 560
internal const val M3_LONG2   = 700
internal const val M3_LONG4   = 840
internal const val M3_XLONG1  = 980

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

// ─── Shared motion primitives ───
// The house animation vocabulary (Emphasized*-eased tweens, spring press/pop feedback, a
// staggered list-entrance cascade) started as bespoke code at individual call sites; these
// three wrap the same specs so every screen shares one implementation instead of copies that
// can drift apart.

/** Swaps between discrete states (an icon, a status row, a connection-state card — anything
 *  representing "what's happening right now") with a spring pop-in + fade for the incoming
 *  state and a quick fade+scale-out for the outgoing one. This is the exact spec already used
 *  for the mini player's play/pause icon (`player/MiniPlayerBar.kt`) and `SongCard`'s
 *  selection checkmark, promoted here so every other status swap in the app (sync spinner→
 *  check/error, download state, connection state, dialog phase icons) uses the same motion. */
@Composable
fun <T> AnimatedStateSwap(
    targetState: T,
    modifier: Modifier = Modifier,
    label: String = "animated_state_swap",
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        contentAlignment = contentAlignment,
        transitionSpec = {
            (scaleIn(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow), initialScale = 0.85f) +
                fadeIn(tween(280, easing = EmphasizedDecelerate))) togetherWith
                (scaleOut(tween(180, easing = EmphasizedAccelerate), targetScale = 0.85f) +
                    fadeOut(tween(180, easing = EmphasizedAccelerate)))
        },
        label = label,
    ) { state -> content(state) }
}

/** Wraps a "prompt vs. real content" boolean swap (permission not-yet-granted vs. granted,
 *  loading vs. loaded) in a fade+slide transition instead of an instant cut. Named for its
 *  original use — the three near-identical permission-gate composables (`Permissions.kt`,
 *  `ReceiveSheet.kt`, `ShareSheet.kt`) that all hard-swapped between a request prompt and the
 *  real screen with zero transition. */
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

/** Remembers which list items have already played their entrance animation, so items
 *  scrolling back into view don't fade/scale in again. Hoist one per list and pass it to
 *  [staggeredEntrance]. Survives configuration changes. */
private const val UNTRACKED_ENTRANCE_LIMIT = 14

class EntranceTracker internal constructor(internal val seen: MutableSet<Any>) {
    /** True for items composed in the list's first frame — only those get the cascade delay. */
    internal var firstFrame = true
}

@Composable
fun rememberEntranceTracker(): EntranceTracker {
    val seen = rememberSaveable(
        saver = androidx.compose.runtime.saveable.listSaver(
            save = { it.seen.map(Any::toString) },
            restore = { EntranceTracker(it.toMutableSet<Any>()) },
        )
    ) { EntranceTracker(HashSet()) }
    LaunchedEffect(seen) { seen.firstFrame = false }
    return seen
}

/** Staggered fade+scale entrance for list items. Apply alongside `Modifier.animateItem()`:
 *  `.animateItem().staggeredEntrance(index, key, tracker)`.
 *
 *  Plays once per item key: the initial screenful cascades in (40ms per index, capped), items
 *  revealed later by scrolling get a short undelayed fade, and items that already appeared
 *  (tracked by [tracker]) render immediately — previously every item replayed the full
 *  animation each time it scrolled back on screen. Animates via graphicsLayer only, so it never
 *  triggers recomposition or relayout. */
@Composable
fun Modifier.staggeredEntrance(
    index: Int,
    key: Any? = null,
    tracker: EntranceTracker? = null,
    capMs: Int = 320,
): Modifier {
    val trackKey = key?.toString()
    val alreadySeen = trackKey != null && tracker != null && trackKey in tracker.seen
    if (alreadySeen) return this
    // Untracked lists can't tell "first appearance" from "scrolled back into view", so only the
    // initial screenful animates there; deeper items just appear.
    if (tracker == null && index >= UNTRACKED_ENTRANCE_LIMIT) return this
    val cascade = tracker == null || tracker.firstFrame
    val progress = remember(trackKey ?: index) { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    LaunchedEffect(trackKey ?: index) {
        if (cascade) delay((index * 40L).coerceAtMost(capMs.toLong()))
        progress.animateTo(1f, spec)
        if (trackKey != null) tracker?.seen?.add(trackKey)
    }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p
        val scale = 0.94f + 0.06f * p
        scaleX = scale
        scaleY = scale
    }
}

/** Playback progress (0..1) as an animated State that is only ever *read* inside draw
 *  lambdas — position ticks then invalidate a single draw pass instead of recomposing the whole
 *  seekbar (and its parent) several times a second. */
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
            if (kotlin.math.abs(target - progress.value) > 0.05f) progress.snapTo(target)
            else progress.animateTo(target, tween(durationMillis = 170, easing = LinearEasing))
        }
    }
    return progress.asState()
}

/** Shared tap/drag handling for the seekbars: the thumb follows the finger live, but the actual
 *  seek is issued once on release (seeking on every move event flooded the player with seeks). */
private fun Modifier.seekGestures(
    durationMs: () -> Long,
    onDragChange: (dragging: Boolean, fraction: Float) -> Unit,
    onSeek: () -> (Long) -> Unit,
    onTick: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent()
            val first = down.changes.firstOrNull() ?: continue
            if (!first.pressed) continue
            var fraction = (first.position.x / size.width).coerceIn(0f, 1f)
            onDragChange(true, fraction)
            onTick()
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == first.id } ?: break
                val newFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                if (newFraction != fraction) {
                    fraction = newFraction
                    onDragChange(true, fraction)
                }
                change.consume()
                if (!change.pressed) break
            }
            onSeek()((fraction * durationMs()).toLong())
            onTick()
            onDragChange(false, fraction)
        }
    }
}

@Composable
fun MaterialYou3Seekbar(
    position: State<Long>,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
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

    // A thin track reads as a plain rectangle no matter its corner radius (8dp tall with a 4dp
    // radius is imperceptible as "rounded" at normal viewing distance) — 14dp is thick enough
    // for the pill shape to actually be visible, matching the Android 13+ media-notification
    // seekbar this style is meant to evoke.
    val activeHeight = animateDpAsState(
        targetValue = if (isDragging) 22.dp else 14.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "m3_height"
    )
    // Always visible, not just while dragging — on a track this thick, a bare color transition
    // alone doesn't read as "there's a handle here."
    val thumbHeight = animateDpAsState(
        targetValue = if (isDragging) 32.dp else 22.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "m3_thumb_height"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
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
        // A small gap around the thumb keeps the played/remaining segments from visually
        // merging into it — another Android 13+ media-seekbar trait that helps the handle read
        // as a distinct, grabbable element instead of just part of the track.
        val gap = 6.dp.toPx()
        val playedEnd = (splitX - thumbWidth / 2f - gap).coerceAtLeast(0f)
        val remainingStart = (splitX + thumbWidth / 2f + gap).coerceAtMost(width)

        if (playedEnd > 0f) {
            drawRoundRect(
                color = playedColor,
                topLeft = Offset(0f, cy - height / 2f),
                size = androidx.compose.ui.geometry.Size(playedEnd, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(height / 2f)
            )
        }
        if (remainingStart < width) {
            drawRoundRect(
                color = remainingColor,
                topLeft = Offset(remainingStart, cy - height / 2f),
                size = androidx.compose.ui.geometry.Size(width - remainingStart, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(height / 2f)
            )
        }

        val thumbH = thumbHeight.value.toPx()
        drawRoundRect(
            color = playedColor,
            topLeft = Offset(splitX - thumbWidth / 2f, cy - thumbH / 2f),
            size = androidx.compose.ui.geometry.Size(thumbWidth, thumbH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbWidth / 2f)
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

    // Both animated values are read only inside the draw block, so the breathing effect costs
    // a redraw per frame instead of a recomposition per frame.
    // Only animates while playing — an infinite transition would redraw every frame even
    // when paused (where its effect is multiplied away).
    val breathe = remember { androidx.compose.animation.core.Animatable(1f) }
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
        shape = RoundedCornerShape(16.dp),
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
                    .clip(RoundedCornerShape(12.dp))
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
                            contentDescription = song.album,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 12.dp,
                            isAnimating = isPlaying,
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
    isAnimating: Boolean = false,
    // Invoked once, lazily, only if the primary `uri` fails to load (e.g. no embedded art) —
    // lets a caller resolve a fallback image URL (Spotify search, etc.) without fetching it
    // for songs that already have real artwork.
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

/** Whether audio is actually playing (not just "this is the current song"), so decorative
 *  playback animations can stop while paused. Provided by the nav graph. */
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
            val cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f)
            for (i in 0 until barCount) {
                val h = size.height * if (i % 2 == 0) 0.45f else 0.75f
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * barW * 2f, (size.height - h) / 2f),
                    size = androidx.compose.ui.geometry.Size(barW, h),
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
        val cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f)
        for (i in 0 until barCount) {
            val phase = (baseAnim.value + i / barCount.toFloat()) % 1f
            val sineVal = (sin(phase * 2 * PI).toFloat() + 1f) / 2f
            val h = (size.height * (0.25f + sineVal * 0.75f)).coerceAtLeast(barW)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * barW * 2f, (size.height - h) / 2f),
                size = androidx.compose.ui.geometry.Size(barW, h),
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

// "2h 15m" style, for cumulative listening totals — distinct from formatDuration's "3:45"
// track-position style above.
fun formatListenDuration(ms: Long): String {
    if (ms <= 0) return "0m"
    val totalMinutes = ms / 60000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

// Codec label derived from mimeType, shared by the player screen's format chip, the song
// detail info card, and the statistics format-breakdown chart.
fun audioFormatLabel(mimeType: String): String = when {
    mimeType.contains("flac", ignoreCase = true) -> "FLAC"
    mimeType.contains("opus", ignoreCase = true) -> "Opus"
    mimeType.contains("ogg", ignoreCase = true) || mimeType.contains("vorbis", ignoreCase = true) -> "OGG"
    mimeType.contains("mp4", ignoreCase = true) || mimeType.contains("aac", ignoreCase = true) -> "AAC"
    else -> "MP3"
}

// Renders each artist as its own clickable segment (e.g. "ArtistA, ArtistB" from a raw
// "ArtistA/ArtistB" tag) instead of one Text with a single click target resolving to only the
// first artist — see Song.artists/displayArtist.
@Composable
fun MultiArtistText(
    artists: List<String>,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = 1,
) {
    // Memoized on (artists, onArtistClick) — this sits inside the Player's PlaybackControls,
    // which recomposes continuously as playback position ticks (10x/sec); without this, the
    // AnnotatedString + LinkAnnotation objects below were being rebuilt from scratch on every
    // one of those recompositions even though the artist list itself changes only on song change.
    val text = remember(artists, onArtistClick) {
        // Explicit no-op styles — LinkAnnotation.Clickable defaults to the usual blue/underlined
        // hyperlink look, but these should read as plain text that merely happens to be tappable.
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
            // accumulated never decreases — rotation is continuous across cycles
            val accumulated = p.yOffset + progress * p.speed
            val rawY = accumulated % 1.0f
            val half = p.sizeDp.dp.toPx() / 2f
            val cx = (p.x + sin(accumulated * PI.toFloat() * 1.4f) * 0.028f) * size.width
            val cy = rawY * (size.height + half * 2f)
            // fade in over top 6% of screen to hide the wrap-around
            val alpha = (rawY / 0.06f).coerceIn(0f, 0.9f)

            withTransform({
                translate(cx, cy)
                rotate(accumulated * p.rotationsPerFall * 360f)
            }) {
                drawRect(
                    color = p.color,
                    topLeft = androidx.compose.ui.geometry.Offset(-half, -half * 0.5f),
                    size = androidx.compose.ui.geometry.Size(half * 2f, half),
                    alpha = alpha,
                )
            }
        }
    }
}
