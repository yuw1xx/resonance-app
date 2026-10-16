package dev.yuwixx.resonance.presentation.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Long-press drag reordering for LazyColumn items. The dragged item follows the finger through
 * graphicsLayer and swaps with a neighbour once it crosses its midpoint. The caller keeps a local
 * list while dragging and commits the move on drop.
 */
@Stable
class ReorderState internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDragEnd: (from: Int, to: Int) -> Unit,
    private val canMoveTo: (key: Any) -> Boolean,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    internal var dragOffset by mutableFloatStateOf(0f)
    private var startIndex = -1
    private var currentIndex = -1
    private var autoScroll: Job? = null

    private fun itemInfo(key: Any?): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    internal fun start(key: Any) {
        val info = itemInfo(key) ?: return
        draggingKey = key
        startIndex = info.index
        currentIndex = info.index
        dragOffset = 0f
    }

    internal fun drag(delta: Float) {
        val key = draggingKey ?: return
        dragOffset += delta
        val dragged = itemInfo(key) ?: return
        val draggedCenter = dragged.offset + dragOffset + dragged.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { other ->
            other.key != key && canMoveTo(other.key) &&
                draggedCenter in other.offset.toFloat()..(other.offset + other.size).toFloat()
        }
        if (target != null) {
            val from = dragged.index
            val to = target.index
            // The item will be laid out at its new slot, so cancel that distance out of the
            // finger offset to keep it visually under the finger.
            dragOffset += (dragged.offset - target.offset).toFloat()
            currentIndex = to
            onMove(from, to)
        }
        maybeAutoScroll(dragged)
    }

    private fun maybeAutoScroll(dragged: LazyListItemInfo) {
        val viewportEnd = listState.layoutInfo.viewportEndOffset
        val top = dragged.offset + dragOffset
        val bottom = top + dragged.size
        val edge = dragged.size * 0.6f
        val speed = when {
            top < edge -> -(edge - top) / 4f
            bottom > viewportEnd - edge -> (bottom - (viewportEnd - edge)) / 4f
            else -> 0f
        }
        autoScroll?.cancel()
        if (speed != 0f) {
            autoScroll = scope.launch {
                while (isActive && draggingKey != null) {
                    val consumed = listState.scrollBy(speed)
                    if (consumed == 0f) break
                    drag(0f)
                    delay(16)
                }
            }
        }
    }

    internal fun end() {
        autoScroll?.cancel()
        if (draggingKey != null && startIndex >= 0 && currentIndex >= 0 && startIndex != currentIndex) {
            onDragEnd(startIndex, currentIndex)
        }
        draggingKey = null
        dragOffset = 0f
        startIndex = -1
        currentIndex = -1
    }
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    onMove: (from: Int, to: Int) -> Unit,
    onDragEnd: (from: Int, to: Int) -> Unit = { _, _ -> },
    canMoveTo: (key: Any) -> Boolean = { true },
): ReorderState {
    val scope = rememberCoroutineScope()
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentCanMove by rememberUpdatedState(canMoveTo)
    return remember(listState) {
        ReorderState(listState, scope, { f, t -> currentOnMove(f, t) }, { f, t -> currentOnDragEnd(f, t) }, { currentCanMove(it) })
    }
}

/** Apply to the item's root: lifts and offsets it while dragged. */
fun Modifier.reorderableItem(state: ReorderState, key: Any): Modifier =
    this
        .zIndex(if (state.draggingKey == key) 1f else 0f)
        .graphicsLayer {
            val dragging = state.draggingKey == key
            translationY = if (dragging) state.dragOffset else 0f
            val s = if (dragging) 1.03f else 1f
            scaleX = s
            scaleY = s
            shadowElevation = if (dragging) 16f else 0f
        }

/** Apply to the drag handle (or the whole row) to start dragging on long press. */
fun Modifier.reorderHandle(state: ReorderState, key: Any, onStart: () -> Unit = {}): Modifier =
    pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.start(key); onStart() },
            onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
            onDragEnd = { state.end() },
            onDragCancel = { state.end() },
        )
    }
