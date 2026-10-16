// The Now Playing sheet: a mini player docked above the navigation bar that drags open into the
// full player. Drag progress (0 = collapsed, 1 = expanded) drives every transition — nav bar
// slide-out, background/shape, mini↔full crossfade and the artwork morph — from the draw and
// placement phases only, so dragging never recomposes the player.
package dev.yuwixx.resonance.presentation.player

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class PlayerSheetValue { Collapsed, Expanded }

@Stable
class PlayerSheetState(initialValue: PlayerSheetValue) {
    internal val draggable = AnchoredDraggableState(initialValue)

    val currentValue: PlayerSheetValue get() = draggable.currentValue

    /** 0 = mini player, 1 = full player. Read it from draw/layout lambdas, not composition. */
    val progress: Float
        get() {
            // Before the sheet is laid out (or while it's hidden) there are no anchors and
            // progress() reports 1 — fall back to the settled value instead.
            val settled = if (draggable.currentValue == PlayerSheetValue.Expanded) 1f else 0f
            if (draggable.anchors.size < 2) return settled
            val p = draggable.progress(PlayerSheetValue.Collapsed, PlayerSheetValue.Expanded)
            return if (p.isNaN()) settled else p
        }

    internal var animationSpec: AnimationSpec<Float>? = null

    suspend fun expand() = animateTo(PlayerSheetValue.Expanded)
    suspend fun collapse() = animateTo(PlayerSheetValue.Collapsed)

    private suspend fun animateTo(value: PlayerSheetValue) {
        val spec = animationSpec
        if (spec != null) draggable.animateTo(value, spec) else draggable.animateTo(value)
    }

    companion object {
        val Saver = Saver<PlayerSheetState, String>(
            save = { it.draggable.currentValue.name },
            restore = { PlayerSheetState(PlayerSheetValue.valueOf(it)) },
        )
    }
}

@Composable
fun rememberPlayerSheetState(): PlayerSheetState {
    val state = rememberSaveable(saver = PlayerSheetState.Saver) { PlayerSheetState(PlayerSheetValue.Collapsed) }
    state.animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()
    return state
}

/** Size/inset of the collapsed mini player for each Appearance → Mini player style. */
@Immutable
data class MiniPlayerMetrics(val contentHeight: Dp, val horizontalInset: Dp, val verticalInset: Dp, val corner: Dp) {
    val totalHeight: Dp get() = contentHeight + verticalInset * 2

    companion object {
        fun forStyle(style: String) = when (style) {
            "COMPACT" -> MiniPlayerMetrics(64.dp, 0.dp, 0.dp, 0.dp)
            "FLOATING" -> MiniPlayerMetrics(64.dp, 12.dp, 8.dp, 28.dp)
            else -> MiniPlayerMetrics(64.dp, 8.dp, 6.dp, 18.dp)
        }
    }
}

/** Links the mini and full player artworks to the floating morph layer drawn by the sheet. */
@Stable
class ArtworkMorph internal constructor(internal val progress: () -> Float) {
    internal var sheet: LayoutCoordinates? = null
    internal var mini: LayoutCoordinates? = null
    internal var full: LayoutCoordinates? = null
    internal var fullSize by mutableStateOf(androidx.compose.ui.unit.IntSize.Zero)
    /** Live scale of the full artwork (it shrinks slightly while paused). */
    var fullScale: () -> Float = { 1f }
    var fullCorner: Dp by mutableStateOf(24.dp)
    var fullIsCircle: Boolean by mutableStateOf(false)
    var miniCorner: Dp by mutableStateOf(10.dp)

    /** True while mid-transition, when the floating copy is shown instead of either artwork. */
    val inFlight: Boolean get() = progress().let { it > 0.001f && it < 0.999f } && full != null

    internal fun miniModifier(): Modifier = Modifier
        .onPlaced { mini = it }
        .graphicsLayer { alpha = if (progress() > 0.001f && full != null) 0f else 1f }

    internal fun fullModifier(): Modifier = Modifier
        .onPlaced { full = it; fullSize = it.size }
        .graphicsLayer { alpha = if (progress() < 0.999f) 0f else 1f }
}

val LocalArtworkMorph = staticCompositionLocalOf<ArtworkMorph?> { null }

/** Clip outline for the sheet: inset on the sides and top (the card/floating mini player),
 *  ending [bottomPx] above the layer's bottom edge so it never paints under the navigation
 *  bar, with corners that square off as the sheet fills the screen. */
private class SheetShape(
    private val insetPx: Float,
    private val topPx: Float,
    private val bottomPx: Float,
    private val cornerPx: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val bottom = (size.height - bottomPx).coerceAtLeast(topPx)
        if (insetPx == 0f && topPx == 0f && cornerPx == 0f) return Outline.Rectangle(Rect(0f, 0f, size.width, bottom))
        return Outline.Rounded(
            androidx.compose.ui.geometry.RoundRect(
                left = insetPx, top = topPx, right = size.width - insetPx, bottom = bottom,
                topLeftCornerRadius = CornerRadius(cornerPx), topRightCornerRadius = CornerRadius(cornerPx),
                bottomLeftCornerRadius = CornerRadius(cornerPx), bottomRightCornerRadius = CornerRadius(cornerPx),
            )
        )
    }
}

@Composable
fun PlayerSheet(
    state: PlayerSheetState,
    metrics: MiniPlayerMetrics,
    /** Height of the navigation bar the collapsed sheet sits on, in px. */
    bottomBarHeightPx: () -> Int,
    collapsedContainerColor: Color,
    expandedContainerColor: Color,
    artworkUri: android.net.Uri?,
    modifier: Modifier = Modifier,
    mini: @Composable (onExpand: () -> Unit) -> Unit,
    full: @Composable (onCollapse: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val morph = remember(state) { ArtworkMorph { state.progress } }
    val backProgress = remember { Animatable(0f) }
    val showFull by remember { derivedStateOf { state.progress > 0.001f } }
    val isExpanded by remember { derivedStateOf { state.progress > 0.999f } }
    val hideMini by remember { derivedStateOf { state.progress > 0.25f } }

    val expand: () -> Unit = remember(state) { { scope.launch { state.expand() } } }
    val collapse: () -> Unit = remember(state) { { scope.launch { state.collapse() } } }

    // Predictive back scrubs the sheet down/inward with the gesture, then collapses on commit.
    PredictiveBackHandler(enabled = isExpanded) { events ->
        try {
            events.collect { backProgress.snapTo(it.progress) }
            state.collapse()
            backProgress.snapTo(0f)
        } catch (e: CancellationException) {
            backProgress.animateTo(0f)
            throw e
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val fullHeightPx = constraints.maxHeight.toFloat()
        val miniHeightPx = with(density) { metrics.totalHeight.toPx() }
        val collapsedOffset = (fullHeightPx - bottomBarHeightPx() - miniHeightPx).coerceAtLeast(0f)

        SideEffect {
            val anchors = DraggableAnchors {
                PlayerSheetValue.Collapsed at collapsedOffset
                PlayerSheetValue.Expanded at 0f
            }
            if (state.draggable.anchors != anchors) state.draggable.updateAnchors(anchors)
        }

        val insetPx = with(density) { metrics.horizontalInset.toPx() }
        val vInsetPx = with(density) { metrics.verticalInset.toPx() }
        val cornerPx = with(density) { metrics.corner.toPx() }
        val backCornerPx = with(density) { 32.dp.toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, state.draggable.offset.takeUnless { it.isNaN() }?.roundToInt() ?: collapsedOffset.roundToInt()) }
                .graphicsLayer {
                    val p = state.progress
                    val offset = state.draggable.offset.takeUnless { it.isNaN() } ?: collapsedOffset
                    val bp = backProgress.value
                    val inv = 1f - p
                    val scale = 1f - 0.08f * bp
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0.35f)
                    shape = SheetShape(
                        insetPx = insetPx * inv,
                        topPx = vInsetPx * inv,
                        // The layer is screen-height but pushed down by `offset`: hide the part
                        // below the screen plus, while collapsed, the nav bar and card margin.
                        bottomPx = offset + (bottomBarHeightPx() + vInsetPx) * inv,
                        cornerPx = lerp(cornerPx, 0f, p) + backCornerPx * bp,
                    )
                    clip = true
                }
                .drawBehind {
                    drawRect(lerp(collapsedContainerColor, expandedContainerColor, state.progress))
                }
                .onPlaced { morph.sheet = it }
                .anchoredDraggable(
                    state = state.draggable,
                    orientation = Orientation.Vertical,
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(state.draggable),
                )
        ) {
            CompositionLocalProvider(LocalArtworkMorph provides morph) {
                if (showFull) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = ((state.progress - 0.15f) / 0.55f).coerceIn(0f, 1f)
                            }
                    ) { full(collapse) }
                }

                // Mini player: fades out quickly as the sheet opens, and stops taking touches.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = metrics.horizontalInset, vertical = metrics.verticalInset)
                        .height(metrics.contentHeight)
                        .graphicsLayer { alpha = (1f - state.progress * 4f).coerceIn(0f, 1f) }
                        // Once faded out it's measured but not placed, so it no longer
                        // intercepts touches meant for the full player beneath it.
                        .then(if (hideMini) Modifier.layout { m, c ->
                            val p = m.measure(c); layout(p.width, p.height) {}
                        } else Modifier)
                ) { mini(expand) }

                if (showFull) ArtworkMorphLayer(morph, artworkUri)
            }
        }
    }
}

/** The floating artwork copy that flies between the mini and full player artwork positions. */
@Composable
private fun BoxScope.ArtworkMorphLayer(morph: ArtworkMorph, artworkUri: android.net.Uri?) {
    val density = LocalDensity.current
    val size = morph.fullSize
    if (size.width == 0) return
    dev.yuwixx.resonance.presentation.components.ArtworkImage(
        uri = artworkUri,
        contentDescription = null,
        cornerRadius = 0.dp,
        modifier = Modifier
            .align(Alignment.TopStart)
            .layout { measurable, _ ->
                val placeable = measurable.measure(
                    androidx.compose.ui.unit.Constraints.fixed(size.width, size.height)
                )
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                val sheet = morph.sheet
                val mini = morph.mini
                val full = morph.full
                if (!morph.inFlight || sheet == null || mini == null || full == null ||
                    !sheet.isAttached || !mini.isAttached || !full.isAttached
                ) {
                    alpha = 0f
                    return@graphicsLayer
                }
                val p = morph.progress()
                val miniRect = sheet.localBoundingBoxOf(mini, clipBounds = false)
                val fullScale = morph.fullScale()
                val fullRectRaw = sheet.localBoundingBoxOf(full, clipBounds = false)
                // The full artwork may be scaled around its center (paused state).
                val fullSize = Size(fullRectRaw.width * fullScale, fullRectRaw.height * fullScale)
                val fullRect = Rect(fullRectRaw.center - Offset(fullSize.width / 2f, fullSize.height / 2f), fullSize)

                // Ease the morph so the artwork leads the rest of the sheet slightly.
                val t = (p * 1.15f).coerceIn(0f, 1f)
                val left = lerp(miniRect.left, fullRect.left, t)
                val top = lerp(miniRect.top, fullRect.top, t)
                val width = lerp(miniRect.width, fullRect.width, t)
                val s = width / size.width
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = left
                translationY = top
                scaleX = s
                scaleY = s
                alpha = 1f
                val miniR = with(density) { morph.miniCorner.toPx() }
                val fullR = if (morph.fullIsCircle) fullRect.width / 2f else with(density) { morph.fullCorner.toPx() } * fullScale
                val displayedRadius = lerp(miniR, fullR, t)
                shape = androidx.compose.foundation.shape.RoundedCornerShape(displayedRadius / s)
                clip = true
            },
    )
}
