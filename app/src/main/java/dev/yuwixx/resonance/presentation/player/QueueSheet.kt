// Up-next queue as a sheet over the player: tap to jump, long-press-drag to reorder (shuffle
// off), swipe away to remove with undo, and save the whole queue as a playlist.
package dev.yuwixx.resonance.presentation.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yuwixx.resonance.presentation.components.SongCard
import dev.yuwixx.resonance.presentation.components.reorderHandle
import dev.yuwixx.resonance.presentation.components.reorderableItem
import dev.yuwixx.resonance.presentation.components.rememberReorderState
import dev.yuwixx.resonance.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    playerViewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val orderedQueue by playerViewModel.orderedQueue.collectAsStateWithLifecycle()
    val currentIndex by playerViewModel.currentQueueIndex.collectAsStateWithLifecycle()
    val shuffle by playerViewModel.shuffleEnabled.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSaveDialog by remember { mutableStateOf(false) }

    // Local copy for live drag-reordering; re-synced from the player whenever it changes and
    // no drag is in progress.
    val items: SnapshotStateList<PlayerViewModel.QueueEntry> = remember { mutableStateListOf() }
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState = listState,
        onMove = { from, to ->
            // List indices are offset by the header item.
            val f = from - 1
            val t = to - 1
            if (f in items.indices && t in items.indices) items.add(t, items.removeAt(f))
        },
        onDragEnd = { from, to ->
            // With shuffle off, display order == window order, so list positions map directly
            // onto the controller's window indices.
            playerViewModel.moveQueueItem(from - 1, to - 1)
        },
        canMoveTo = { key -> key != "header" },
    )
    LaunchedEffect(orderedQueue) {
        if (reorder.draggingKey == null) {
            items.clear()
            items.addAll(orderedQueue)
        }
    }
    // Start scrolled to the playing song.
    LaunchedEffect(Unit) {
        val pos = orderedQueue.indexOfFirst { it.index == currentIndex }
        if (pos > 0) listState.scrollToItem(pos)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Box {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "header") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Up next", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(
                                buildString {
                                    append("${items.size} songs")
                                    if (shuffle) append(" · shuffled")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { showSaveDialog = true }, enabled = items.isNotEmpty()) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Save queue as playlist")
                        }
                        IconButton(onClick = { playerViewModel.clearQueue(); onDismiss() }, enabled = items.isNotEmpty()) {
                            Icon(Icons.Rounded.ClearAll, "Clear queue")
                        }
                    }
                    if (shuffle) {
                        Row(
                            Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Shuffle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Turn off shuffle to reorder",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                itemsIndexed(items, key = { _, e -> "${e.index}_${e.song.id}" }) { _, entry ->
                    val key = "${entry.index}_${entry.song.id}"
                    val isCurrent = entry.index == currentIndex
                    val dismissState = rememberSwipeToDismissBoxState()
                    LaunchedEffect(dismissState.currentValue) {
                        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
                            val removed = playerViewModel.removeFromQueueForUndo(entry.index)
                            if (removed != null) {
                                val result = snackbar.showSnackbar(
                                    message = "Removed “${removed.second.title}”",
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    playerViewModel.restoreQueueItem(removed.first, removed.second)
                                }
                            }
                        }
                    }
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = !isCurrent,
                        enableDismissFromEndToStart = !isCurrent,
                        backgroundContent = {
                            Box(
                                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                                contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
                            ) {
                                Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.animateItem().reorderableItem(reorder, key),
                    ) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            SongCard(
                                song = entry.song,
                                isPlaying = isCurrent,
                                onClick = { playerViewModel.playAtQueueIndex(entry.index) },
                                trailingContent = {
                                    if (!shuffle) {
                                        Icon(
                                            Icons.Rounded.DragHandle,
                                            "Drag to reorder",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .padding(8.dp)
                                                .reorderHandle(reorder, key),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }

    if (showSaveDialog) {
        dev.yuwixx.resonance.presentation.screens.CreatePlaylistDialog(
            onDismiss = { showSaveDialog = false },
            onConfirm = { name ->
                showSaveDialog = false
                scope.launch {
                    val id = playerViewModel.saveQueueAsPlaylist(name)
                    snackbar.showSnackbar(if (id != null) "Saved as “$name”" else "Queue is empty")
                }
            },
        )
    }
}
