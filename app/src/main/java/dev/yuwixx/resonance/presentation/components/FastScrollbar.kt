package dev.yuwixx.resonance.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.yuwixx.resonance.presentation.components.LocalAppearanceConfig
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LazyColumnWithScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    hideDelayMs: Long = 1_200,
    content: @Composable BoxScope.() -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val config = LocalAppearanceConfig.current
    val scope  = rememberCoroutineScope()

    var visible by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    var trackHeightPx by remember { mutableFloatStateOf(0f) }

    val thumbHeightFraction by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val total = info.totalItemsCount.coerceAtLeast(1)
            val visible2 = info.visibleItemsInfo.size.coerceAtLeast(1)
            (visible2.toFloat() / total).coerceIn(0.05f, 1f)
        }
    }

    val canScroll by remember { derivedStateOf { thumbHeightFraction < 1f } }

    val isScrollInProgress by remember { derivedStateOf { state.isScrollInProgress } }
    LaunchedEffect(isScrollInProgress) {
        if (isScrollInProgress && canScroll) {
            visible = true
        } else if (!isDragging) {
            delay(hideDelayMs)
            visible = false
        }
    }
    LaunchedEffect(isDragging) {
        if (!isDragging && !isScrollInProgress) {
            delay(hideDelayMs)
            visible = false
        }
    }
    LaunchedEffect(canScroll) {
        if (!canScroll) visible = false
    }

    val thumbAlpha by animateFloatAsState(
        targetValue = if (visible && canScroll) 1f else 0f,
        animationSpec = tween(durationMillis = if (visible) 150 else 600),
        label = "scrollbar_alpha",
    )

    val thumbOffsetFraction by remember {
        derivedStateOf {
            val info  = state.layoutInfo
            val total = info.totalItemsCount.coerceAtLeast(1)
            val first = state.firstVisibleItemIndex
            val itemFraction = first.toFloat() / total
            itemFraction.coerceIn(0f, 1f - thumbHeightFraction)
        }
    }

    var lastHapticItem by remember { mutableIntStateOf(-1) }

    val thumbModifier = Modifier
        .fillMaxHeight()
        .width(28.dp)
        .alpha(thumbAlpha)
        .onSizeChanged { trackHeightPx = it.height.toFloat() }
        .pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = {
                    isDragging = true
                    visible = true
                    if (config.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDragEnd = {
                    isDragging = false
                },
                onDragCancel = {
                    isDragging = false
                },
                onVerticalDrag = { _, dragAmount ->
                    if (trackHeightPx <= 0f) return@detectVerticalDragGestures
                    val thumbTravel = trackHeightPx * (1f - thumbHeightFraction)
                    if (thumbTravel <= 0f) return@detectVerticalDragGestures

                    val delta = dragAmount / thumbTravel
                    val total = state.layoutInfo.totalItemsCount
                    val newFirst = ((state.firstVisibleItemIndex + delta * total)
                        .toInt()).coerceIn(0, (total - 1).coerceAtLeast(0))

                    if (newFirst != lastHapticItem) {
                        if (config.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        lastHapticItem = newFirst
                    }

                    scope.launch { state.scrollToItem(newFirst) }
                }
            )
        }

    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val thumbScale by animateFloatAsState(
        targetValue = if (isDragging) 1.15f else 1f,
        animationSpec = tween(150),
        label = "thumb_scale",
    )

    Box(modifier = modifier) {
        content()

        Box(
            modifier = Modifier.align(Alignment.CenterEnd)
                .then(thumbModifier)
                .drawBehind {
                    val thumbH = size.height * thumbHeightFraction
                    val thumbTop = (size.height - thumbH) * thumbOffsetFraction
                    val w = 4.dp.toPx()
                    val x = size.width - w - 2.dp.toPx()
                    drawRoundRect(
                        color = thumbColor,
                        topLeft = androidx.compose.ui.geometry.Offset(x, thumbTop),
                        size = androidx.compose.ui.geometry.Size(w, thumbH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
                    )
                }
                .graphicsLayer { scaleX = thumbScale; scaleY = thumbScale }
        )
    }
}

@Composable
fun LazyGridWithScrollbar(
    state: LazyGridState,
    columnCount: Int,
    modifier: Modifier = Modifier,
    hideDelayMs: Long = 1_200,
    content: @Composable BoxScope.() -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val config = LocalAppearanceConfig.current
    val scope  = rememberCoroutineScope()

    var visible by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    var trackHeightPx by remember { mutableFloatStateOf(0f) }

    val thumbHeightFraction by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val totalItems = info.totalItemsCount.coerceAtLeast(1)
            val totalRows = ((totalItems + columnCount - 1) / columnCount).coerceAtLeast(1)
            val visibleRows = ((info.visibleItemsInfo.size + columnCount - 1) / columnCount).coerceAtLeast(1)
            (visibleRows.toFloat() / totalRows).coerceIn(0.05f, 1f)
        }
    }

    val canScroll by remember { derivedStateOf { thumbHeightFraction < 1f } }

    val isScrollInProgress by remember { derivedStateOf { state.isScrollInProgress } }
    LaunchedEffect(isScrollInProgress) {
        if (isScrollInProgress && canScroll) {
            visible = true
        } else if (!isDragging) {
            delay(hideDelayMs)
            visible = false
        }
    }
    LaunchedEffect(isDragging) {
        if (!isDragging && !isScrollInProgress) {
            delay(hideDelayMs)
            visible = false
        }
    }
    LaunchedEffect(canScroll) {
        if (!canScroll) visible = false
    }

    val thumbAlpha by animateFloatAsState(
        targetValue = if (visible && canScroll) 1f else 0f,
        animationSpec = tween(durationMillis = if (visible) 150 else 600),
        label = "grid_scrollbar_alpha",
    )

    val thumbOffsetFraction by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val totalItems = info.totalItemsCount.coerceAtLeast(1)
            val totalRows = ((totalItems + columnCount - 1) / columnCount).coerceAtLeast(1)
            val firstRow = state.firstVisibleItemIndex / columnCount
            (firstRow.toFloat() / totalRows).coerceIn(0f, 1f - thumbHeightFraction)
        }
    }

    var lastHapticRow by remember { mutableIntStateOf(-1) }

    val thumbModifier = Modifier
        .fillMaxHeight()
        .width(28.dp)
        .alpha(thumbAlpha)
        .onSizeChanged { trackHeightPx = it.height.toFloat() }
        .pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = {
                    isDragging = true
                    visible = true
                    if (config.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDragEnd   = { isDragging = false },
                onDragCancel = { isDragging = false },
                onVerticalDrag = { _, dragAmount ->
                    if (trackHeightPx <= 0f) return@detectVerticalDragGestures
                    val thumbTravel = trackHeightPx * (1f - thumbHeightFraction)
                    if (thumbTravel <= 0f) return@detectVerticalDragGestures

                    val delta = dragAmount / thumbTravel
                    val totalItems = state.layoutInfo.totalItemsCount
                    val totalRows = ((totalItems + columnCount - 1) / columnCount).coerceAtLeast(1)
                    val currentRow = state.firstVisibleItemIndex / columnCount
                    val newRow = (currentRow + (delta * totalRows).toInt())
                        .coerceIn(0, (totalRows - 1).coerceAtLeast(0))
                    val newItem = (newRow * columnCount).coerceIn(0, (totalItems - 1).coerceAtLeast(0))

                    if (newRow != lastHapticRow) {
                        if (config.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        lastHapticRow = newRow
                    }

                    scope.launch { state.scrollToItem(newItem) }
                }
            )
        }

    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val thumbScale by animateFloatAsState(
        targetValue = if (isDragging) 1.15f else 1f,
        animationSpec = tween(150),
        label = "grid_thumb_scale",
    )

    Box(modifier = modifier) {
        content()

        Box(
            modifier = Modifier.align(Alignment.CenterEnd)
                .then(thumbModifier)
                .drawBehind {
                    val thumbH = size.height * thumbHeightFraction
                    val thumbTop = (size.height - thumbH) * thumbOffsetFraction
                    val w = 4.dp.toPx()
                    val x = size.width - w - 2.dp.toPx()
                    drawRoundRect(
                        color = thumbColor,
                        topLeft = androidx.compose.ui.geometry.Offset(x, thumbTop),
                        size = androidx.compose.ui.geometry.Size(w, thumbH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
                    )
                }
                .graphicsLayer { scaleX = thumbScale; scaleY = thumbScale }
        )
    }
}
