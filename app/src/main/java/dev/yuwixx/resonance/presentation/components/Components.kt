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

internal const val M3_SHORT2  = 100
internal const val M3_SHORT4  = 200
internal const val M3_MEDIUM2 = 300
internal const val M3_MEDIUM4 = 400
internal const val M3_LONG2   = 500
internal const val M3_LONG4   = 600
internal const val M3_XLONG1  = 700

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
)

val LocalAppearanceConfig = compositionLocalOf { AppearanceConfig() }

@android.annotation.SuppressLint("NewApi")
internal fun Modifier.preferredFrameRateSafe(hz: Float): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) preferredFrameRate(hz)
    else this

@Composable
fun MaterialYou3Seekbar(
    positionProvider: () -> Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    playedColor: Color = MaterialTheme.colorScheme.primary,
    remainingColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
) {
    val haptics = LocalHapticFeedback.current
    val config = LocalAppearanceConfig.current
    val positionMs by remember { derivedStateOf { positionProvider() } }

    val targetProgress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "m3_progress",
    )

    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val displayProgress = if (isDragging) dragProgress else animatedProgress

    // A thin track reads as a plain rectangle no matter its corner radius (8dp tall with a 4dp
    // radius is imperceptible as "rounded" at normal viewing distance) — 14dp is thick enough
    // for the pill shape to actually be visible, matching the Android 13+ media-notification
    // seekbar this style is meant to evoke.
    val activeHeight by animateDpAsState(
        targetValue = if (isDragging) 22.dp else 14.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "m3_height"
    )
    // Always visible, not just while dragging — on a track this thick, a bare color transition
    // alone doesn't read as "there's a handle here."
    val thumbHeight by animateDpAsState(
        targetValue = if (isDragging) 32.dp else 22.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "m3_thumb_height"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .preferredFrameRateSafe(120f)
            .pointerInput(durationMs) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        dragProgress = fraction
                        if (change.pressed) {
                            isDragging = true
                            onSeek((fraction * durationMs).toLong())
                            if (config.hapticEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } else {
                            isDragging = false
                        }
                    }
                }
            }
    ) {
        val width = size.width
        val height = activeHeight.toPx()
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

        val thumbH = thumbHeight.toPx()
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
    positionProvider: () -> Long,
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
    val positionMs by remember { derivedStateOf { positionProvider() } }

    val targetProgress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "waveform_progress",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "wave_breathe")
    val breathe by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe_amp",
    )
    val ampMultiplier by animateFloatAsState(
        targetValue = if (isPlaying) breathe else 1f,
        animationSpec = tween(400),
        label = "amp_multiplier",
    )

    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val displayProgress = if (isDragging) dragProgress else animatedProgress

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
            .preferredFrameRateSafe(120f)
            .pointerInput(durationMs) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        dragProgress = fraction
                        if (change.pressed) {
                            isDragging = true
                            onSeek((fraction * durationMs).toLong())
                            if (config.hapticEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } else {
                            isDragging = false
                        }
                    }
                }
            }
            .drawWithCache {
                onDrawWithContent {
                    val barW = barWidthDp.toPx()
                    val gap = barSpacingDp.toPx()
                    val step = barW + gap
                    val count = (size.width / step).toInt().coerceAtLeast(1)
                    val cy = size.height / 2f
                    val splitX = displayProgress * size.width

                    val safeProgress = displayProgress.coerceIn(0.001f, 0.999f)
                    val smoothBrush = Brush.horizontalGradient(
                        0f to playedColor,
                        safeProgress to playedColor,
                        safeProgress to remainingColor,
                        1f to remainingColor,
                        startX = 0f,
                        endX = size.width
                    )

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

                        val top = cy - barH / 2f
                        val bottom = cy + barH / 2f

                        drawLine(
                            brush = smoothBrush,
                            start = Offset(barCX, top),
                            end = Offset(barCX, bottom),
                            strokeWidth = barW,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            },
    ) {
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
            stiffness = Spring.StiffnessMediumLow,
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
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "song_color",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .preferredFrameRateSafe(120f)
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
                        fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.8f) togetherWith
                                fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.8f)
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

@Composable
fun PlayingBarsIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    barCount: Int = 3,
) {
    val sharedTransition = rememberInfiniteTransition(label = "bars_shared")
    val baseAnim by sharedTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bars_base",
    )
    Canvas(modifier = modifier) {
        val barW = size.width / (barCount * 2 - 1)
        val cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f)
        for (i in 0 until barCount) {
            val phase = (baseAnim + i / barCount.toFloat()) % 1f
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MiniPlayer(
    playerViewModel: PlayerViewModel,
    style: String = "CARD",
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val currentSong by playerViewModel.currentSong.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val durationMs by playerViewModel.durationMs.collectAsState()

    val positionProvider = remember { { playerViewModel.positionMs.value } }

    val song = currentSong ?: return

    val (outerPadding, cornerRadius, elevation) = when (style) {
        "COMPACT"  -> Triple(0.dp, 0.dp, 0.dp)
        "FLOATING" -> Triple(16.dp, 28.dp, 8.dp)
        else       -> Triple(8.dp, 12.dp, 2.dp)
    }

    val miniConfig = LocalAppearanceConfig.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(outerPadding)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(cornerRadius),
        tonalElevation = elevation,
        shadowElevation = elevation,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {

            if (miniConfig.miniPlayerShowProgress) {
                LinearProgressIndicator(
                    progress = {
                        if (durationMs > 0) (positionProvider().toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.BottomCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val artworkModifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(if (style == "COMPACT") 4.dp else 8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)

                Box(
                    modifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            artworkModifier.sharedElement(
                                rememberSharedContentState(key = "artwork"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    } else artworkModifier
                ) {
                    ArtworkImage(
                        uri = song.artworkUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 0.dp,
                        onArtworkMissing = { playerViewModel.getSongArtworkUrl(song) },
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                AnimatedContent(
                    targetState = song.title to song.displayArtist,
                    transitionSpec = {
                        (fadeIn(tween(280, delayMillis = 80, easing = EmphasizedDecelerate)) +
                            slideInVertically(tween(320, easing = EmphasizedDecelerate)) { (it * 0.12f).toInt() })
                            .togetherWith(fadeOut(tween(120, easing = EmphasizedAccelerate)))
                    },
                    label = "mini_song_info",
                    modifier = Modifier.weight(1f),
                ) { (title, artist) ->
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(onClick = { playerViewModel.playPause() }) {
                    AnimatedContent(
                        targetState = isPlaying,
                        transitionSpec = {
                            (scaleIn(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)) +
                                fadeIn(tween(140, easing = EmphasizedDecelerate))) togetherWith
                                (scaleOut(tween(80)) + fadeOut(tween(80)))
                        },
                        label = "mini_play_icon",
                    ) { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                if (miniConfig.miniPlayerShowSkipBtn) {
                    IconButton(onClick = { playerViewModel.skipNext() }) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
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
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
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
