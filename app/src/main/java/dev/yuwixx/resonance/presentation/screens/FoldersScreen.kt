package dev.yuwixx.resonance.presentation.screens

import dev.yuwixx.resonance.presentation.screens.library.LocalLibrarySections
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.presentation.components.AppSectionHeader
import dev.yuwixx.resonance.presentation.components.EmphasizedAccelerate
import dev.yuwixx.resonance.presentation.components.EmphasizedDecelerate
import dev.yuwixx.resonance.presentation.components.staggeredEntrance
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
            Column {
            TopAppBar(
                title = {
                    AnimatedContent(
                        targetState = topBarTitle to currentPath,
                        transitionSpec = {
                            fadeIn(tween(280, easing = EmphasizedDecelerate)) togetherWith
                                fadeOut(tween(180, easing = EmphasizedAccelerate))
                        },
                        label = "folder_title",
                    ) { (title, path) ->
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (path != null) {
                                Text(
                                    path,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (currentPath != null || LocalLibrarySections.current == null) {
                        IconButton(onClick = { if (currentPath != null) navigateUp() else onBack() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                        }
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
            LocalLibrarySections.current?.invoke()
            }
        }
    ) { padding ->
        // A single AnimatedContent keyed on currentPath covers both concerns at once: the
        // "push forward / pop back" drill-down transition between folder levels, and the
        // empty-vs-populated swap (emptiness is just a function of what's at currentPath).
        AnimatedContent(
            targetState = currentPath,
            transitionSpec = {
                if (targetState != null && (initialState == null || targetState!!.startsWith("${initialState}/") || targetState!!.length > (initialState?.length ?: -1))) {
                    // Drilling deeper — push in from the right, push old content left.
                    (slideInHorizontally(tween(340, easing = EmphasizedDecelerate)) { (it * 0.25f).toInt() } +
                        fadeIn(tween(340, easing = EmphasizedDecelerate))) togetherWith
                        (slideOutHorizontally(tween(220, easing = EmphasizedAccelerate)) { -(it * 0.25f).toInt() } +
                            fadeOut(tween(220, easing = EmphasizedAccelerate)))
                } else {
                    // Going back up — push in from the left, push old content right.
                    (slideInHorizontally(tween(340, easing = EmphasizedDecelerate)) { -(it * 0.25f).toInt() } +
                        fadeIn(tween(340, easing = EmphasizedDecelerate))) togetherWith
                        (slideOutHorizontally(tween(220, easing = EmphasizedAccelerate)) { (it * 0.25f).toInt() } +
                            fadeOut(tween(220, easing = EmphasizedAccelerate)))
                }
            },
            label = "folder_contents",
        ) { path ->
            val (levelSubfolders, levelSongsHere) = remember(allSongs, path) {
                computeFolderContents(allSongs, path)
            }

            if (levelSubfolders.isEmpty() && levelSongsHere.isEmpty()) {
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
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 16.dp,
                    )
                ) {
                    itemsIndexed(levelSubfolders, key = { _, it -> it }) { index, folder ->
                        val songCount = remember(allSongs, folder) {
                            allSongs.count { it.folder == folder || it.folder.startsWith("$folder/") }
                        }
                        FolderRow(
                            name = folder.substringAfterLast('/'),
                            subtitle = "$songCount song${if (songCount != 1) "s" else ""}",
                            icon = Icons.Rounded.FolderOpen,
                            onClick = { currentPath = folder },
                            modifier = Modifier.animateItem().staggeredEntrance(index),
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }

                    if (levelSongsHere.isNotEmpty() && levelSubfolders.isNotEmpty()) {
                        item {
                            AppSectionHeader("Songs in this folder", Icons.Rounded.MusicNote)
                        }
                    }
                    itemsIndexed(levelSongsHere, key = { _, s -> s.id }) { index, song ->
                        SongRow(
                            song = song,
                            onClick = { playerViewModel.play(levelSongsHere, index) },
                            modifier = Modifier.animateItem().staggeredEntrance(index),
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
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
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "folder_row_scale",
    )
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().scale(pressScale),
        color = MaterialTheme.colorScheme.background,
        interactionSource = interactionSource,
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
private fun SongRow(song: Song, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "song_row_scale",
    )
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().scale(pressScale),
        color = MaterialTheme.colorScheme.background,
        interactionSource = interactionSource,
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
