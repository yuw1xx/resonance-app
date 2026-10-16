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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yuwixx.resonance.presentation.components.SongCard
import dev.yuwixx.resonance.presentation.components.rememberHaptics
import dev.yuwixx.resonance.presentation.components.rememberReorderState
import dev.yuwixx.resonance.presentation.components.reorderHandle
import dev.yuwixx.resonance.presentation.components.reorderableItem
import dev.yuwixx.resonance.presentation.screens.CreatePlaylistDialog
import dev.yuwixx.resonance.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    playerViewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val haptic = rememberHaptics()
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
    // Start scrolled to the playing song (item 0 is the header).
    LaunchedEffect(Unit) {
        val pos = orderedQueue.indexOfFirst { it.index == currentIndex }
        if (pos > 0) listState.scrollToItem(pos + 1)
    }

    // Runs in the sheet's scope: the removed row leaves composition, which would cancel the
    // snackbar if it were shown from the row itself.
    fun removeWithUndo(entry: PlayerViewModel.QueueEntry, onFailed: () -> Unit) {
        val removed = playerViewModel.removeFromQueueForUndo(entry.index)
        if (removed == null) {
            onFailed()
            return
        }
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = "Removed “${removed.song.title}”",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                playerViewModel.restoreQueueItem(removed)
            }
        }
    }

    // Song ID plus occurrence: stays the same for every row when an earlier row is removed.
    fun rowKeys(entries: List<PlayerViewModel.QueueEntry>): List<String> {
        val seen = HashMap<Long, Int>()
        return entries.map { e ->
            val n = seen.merge(e.song.id, 1, Int::plus)!!
            "${e.song.id}#$n"
        }
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

                val keys = rowKeys(items)
                itemsIndexed(items, key = { i, _ -> keys[i] }) { i, entry ->
                    val key = keys[i]
                    val isCurrent = entry.index == currentIndex
                    val dismissState = rememberSwipeToDismissBoxState()
                    val currentEntry by rememberUpdatedState(entry)
                    LaunchedEffect(dismissState) {
                        // The swipe state is saved per row key, so a song brought back by Undo
                        // returns already "dismissed". Reset it instead of removing it again.
                        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
                            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
                        }
                        snapshotFlow { dismissState.currentValue }
                            .filter { it != SwipeToDismissBoxValue.Settled }
                            .collect {
                                haptic(HapticFeedbackType.GestureThresholdActivate)
                                removeWithUndo(currentEntry) { scope.launch { dismissState.reset() } }
                            }
                    }
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = !isCurrent,
                        enableDismissFromEndToStart = !isCurrent,
                        backgroundContent = {
                            Box(
                                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                                contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                                    Alignment.CenterStart
                                } else {
                                    Alignment.CenterEnd
                                },
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
        CreatePlaylistDialog(
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
