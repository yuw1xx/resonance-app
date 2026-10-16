// UI for importing .m3u/.m3u8 playlists: the file picker launcher, the name-conflict prompt,
// progress, and a results sheet listing anything that couldn't be matched.
package dev.yuwixx.resonance.presentation.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yuwixx.resonance.data.playlist.M3uImporter
import dev.yuwixx.resonance.presentation.viewmodel.LibraryViewModel.M3uImportState

/** MIME types playlists turn up with across file managers; `*` last as a catch-all because many
 *  providers report .m3u8 as application/octet-stream. */
val M3U_MIME_TYPES = arrayOf(
    "audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl",
    "application/vnd.apple.mpegurl", "audio/x-scpls", "*/*",
)

@Composable
fun rememberM3uImportLauncher(onPicked: (List<Uri>) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    return { launcher.launch(M3U_MIME_TYPES) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun M3uImportDialogs(
    state: M3uImportState,
    onConfirm: (M3uImporter.ConflictMode) -> Unit,
    onDismiss: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    when (state) {
        M3uImportState.Idle -> Unit

        is M3uImportState.ConfirmConflicts -> {
            val clashing = state.previews.filter { it.existingPlaylistId != null }
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                title = { Text(if (clashing.size == 1) "Playlist already exists" else "${clashing.size} playlists already exist") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            clashing.joinToString(limit = 4) { "“${it.name}”" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Replace their songs with the imported ones, add the imported songs to them, or keep both as separate playlists.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { onConfirm(M3uImporter.ConflictMode.REPLACE) }) { Text("Replace") }
                        FilledTonalButton(onClick = { onConfirm(M3uImporter.ConflictMode.MERGE) }) { Text("Add to existing") }
                        TextButton(onClick = { onConfirm(M3uImporter.ConflictMode.KEEP_BOTH) }) { Text("Keep both") }
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }

        is M3uImportState.Importing -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Importing playlists") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LoadingIndicator(modifier = Modifier.size(48.dp))
                        Text(
                            if (state.total > 1) "${state.done} of ${state.total}" else "Matching songs…",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                },
                confirmButton = {},
            )
        }

        is M3uImportState.Finished -> {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            if (state.results.size == 1) "Playlist imported" else "${state.results.size} playlists imported",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                    items(state.results, key = { it.playlistId }) { result ->
                        ImportResultCard(result, onOpen = { onDismiss(); onOpenPlaylist(result.playlistId) })
                    }
                    items(state.failures) { message ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Button(onClick = onDismiss) { Text("Done") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportResultCard(result: M3uImporter.Result, onOpen: () -> Unit) {
    var expanded by rememberSaveable(result.playlistId) { mutableStateOf(false) }
    val complete = result.unmatched.isEmpty()
    ElevatedCard(onClick = onOpen, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (complete) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                    null,
                    tint = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                )
                Column(Modifier.weight(1f)) {
                    Text(result.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append("${result.matched} of ${result.total - result.skippedRemote} songs found")
                            if (result.skippedRemote > 0) append(" · ${result.skippedRemote} streams skipped")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (result.total > 0) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { result.matched.toFloat() / (result.total - result.skippedRemote).coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!complete) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${result.unmatched.size} not found",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                    exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        result.unmatched.take(MAX_UNMATCHED_SHOWN).forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        }
                        if (result.unmatched.size > MAX_UNMATCHED_SHOWN) {
                            Text("…and ${result.unmatched.size - MAX_UNMATCHED_SHOWN} more", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_UNMATCHED_SHOWN = 50
