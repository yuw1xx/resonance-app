package dev.yuwixx.resonance.presentation.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.presentation.components.AppSectionHeader
import dev.yuwixx.resonance.presentation.viewmodel.LibraryViewModel
import dev.yuwixx.resonance.presentation.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    val allSongs by libraryViewModel.allSongs.collectAsState()

    var currentPath by remember { mutableStateOf<String?>(null) }

    fun navigateUp() {
        currentPath = currentPath?.substringBeforeLast('/')?.ifEmpty { null }
    }

    BackHandler(enabled = currentPath != null) { navigateUp() }

    val (subfolders, songsHere) = remember(allSongs, currentPath) {
        computeFolderContents(allSongs, currentPath)
    }

    val topBarTitle = remember(currentPath) {
        currentPath?.substringAfterLast('/') ?: "Folders"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(topBarTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (currentPath != null) {
                            Text(
                                currentPath!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (currentPath != null) navigateUp() else onBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (songsHere.isNotEmpty()) {
                        IconButton(onClick = { playerViewModel.play(songsHere, 0) }) {
                            Icon(Icons.Rounded.PlayArrow, "Play all")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        }
    ) { padding ->
        if (subfolders.isEmpty() && songsHere.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No music found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 80.dp,
            )
        ) {
            items(subfolders, key = { it }) { folder ->
                val songCount = remember(allSongs, folder) {
                    allSongs.count { it.folder == folder || it.folder.startsWith("$folder/") }
                }
                FolderRow(
                    name = folder.substringAfterLast('/'),
                    subtitle = "$songCount song${if (songCount != 1) "s" else ""}",
                    icon = Icons.Rounded.FolderOpen,
                    onClick = { currentPath = folder },
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }

            if (songsHere.isNotEmpty() && subfolders.isNotEmpty()) {
                item {
                    AppSectionHeader("Songs in this folder", Icons.Rounded.MusicNote)
                }
            }
            itemsIndexed(songsHere, key = { _, s -> s.id }) { index, song ->
                SongRow(song = song, onClick = { playerViewModel.play(songsHere, index) })
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }
        }
    }
}

private fun computeFolderContents(
    allSongs: List<Song>,
    currentPath: String?,
): Pair<List<String>, List<Song>> {
    if (currentPath == null) {
        val roots = allSongs
            .map { it.folder }
            .flatMap { folder ->
                buildList {
                    var p = folder
                    while (p.isNotEmpty()) {
                        add(p)
                        val parent = p.substringBeforeLast('/', "")
                        if (parent == p || parent.isEmpty()) break
                        p = parent
                    }
                }
            }
            .toSet()

        val songFolders = allSongs.map { it.folder }.toSet()
        val minimalRoots = songFolders.filter { folder ->
            songFolders.none { other -> other != folder && folder.startsWith("$other/") }
        }.toSortedSet()

        val commonPrefix = minimalRoots.fold(minimalRoots.first()) { acc, f ->
            acc.commonPrefixWith(f).substringBeforeLast('/')
        }
        return if (commonPrefix.isNotEmpty() && minimalRoots.size > 1) {
            val nextLevel = minimalRoots
                .map { it.removePrefix("$commonPrefix/").substringBefore('/') }
                .filter { it.isNotEmpty() }
                .map { "$commonPrefix/$it" }
                .distinct()
                .sorted()
            nextLevel to emptyList()
        } else {
            minimalRoots.toList() to emptyList()
        }
    }

    val directChildren = allSongs
        .map { it.folder }
        .filter { it.startsWith("$currentPath/") }
        .map { it.removePrefix("$currentPath/").substringBefore('/') }
        .filter { it.isNotEmpty() }
        .map { "$currentPath/$it" }
        .distinct()
        .sorted()

    val songsHere = allSongs
        .filter { it.folder == currentPath }
        .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.title }))

    return directChildren to songsHere
}

@Composable
private fun FolderRow(
    name: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp).padding(4.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SongRow(song: Song, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (song.trackNumber > 0) {
                Text(
                    text = song.trackNumber.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp),
                    fontWeight = FontWeight.Medium,
                )
            } else {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp).padding(4.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
