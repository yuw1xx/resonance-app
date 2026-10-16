@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

// All library browsing screens: Songs, Albums, Artists, Playlists, LikedSongs, Search,
// AlbumDetail, ArtistDetail, PlaylistDetail, Queue, and the TagEditor screen.
package dev.yuwixx.resonance.presentation.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yuwixx.resonance.data.database.entity.DownloadState
import dev.yuwixx.resonance.data.database.entity.SongDownloadEntity
import dev.yuwixx.resonance.data.model.*
import dev.yuwixx.resonance.data.model.RepeatMode as AppRepeatMode
import dev.yuwixx.resonance.data.repository.NavidromeSyncState
import dev.yuwixx.resonance.presentation.components.*
import dev.yuwixx.resonance.presentation.components.LazyColumnWithScrollbar
import dev.yuwixx.resonance.presentation.components.LazyGridWithScrollbar
import dev.yuwixx.resonance.presentation.components.LocalAppearanceConfig
import dev.yuwixx.resonance.presentation.screens.library.LibraryTitle
import dev.yuwixx.resonance.presentation.screens.library.LocalLibrarySections
import dev.yuwixx.resonance.presentation.viewmodel.ArtistInfoState
import dev.yuwixx.resonance.presentation.viewmodel.ArtistInfoViewModel
import dev.yuwixx.resonance.presentation.viewmodel.LibraryViewModel
import dev.yuwixx.resonance.presentation.viewmodel.PlayerViewModel
import dev.yuwixx.resonance.presentation.viewmodel.ShareViewModel
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SongsScreen(
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onSearchClick: () -> Unit,
    onNavigateToPlayer: () -> Unit,
    onNavigateToTagEditor: (Long) -> Unit,
) {
    val libraryLoaded by libraryViewModel.libraryLoaded.collectAsStateWithLifecycle()
    val rawSongs by libraryViewModel.allSongs.collectAsStateWithLifecycle()
    val defaultSongsSort by libraryViewModel.prefs.defaultSongsSort.collectAsStateWithLifecycle(initialValue = "TITLE")
    val songs = remember(rawSongs, defaultSongsSort) {
        when (defaultSongsSort) {
            "ARTIST" -> rawSongs.sortedBy { it.displayArtist.lowercase() }
            "ALBUM" -> rawSongs.sortedBy { it.album.lowercase() }
            "ADDED" -> rawSongs.sortedByDescending { it.dateAdded }
            else -> rawSongs.sortedBy { it.title.lowercase() }
        }
    }
    val currentSong by playerViewModel.currentSong.collectAsStateWithLifecycle()
    val isPlaying by playerViewModel.isPlaying.collectAsStateWithLifecycle()
    val isSyncing by libraryViewModel.isSyncing.collectAsStateWithLifecycle()
    val navSyncState by libraryViewModel.navSyncState.collectAsStateWithLifecycle()
    val playlists by libraryViewModel.allPlaylists.collectAsStateWithLifecycle()
    val musicSource by libraryViewModel.prefs.musicSource.collectAsStateWithLifecycle(initialValue = MusicSource.LOCAL)
    val downloadStates by libraryViewModel.downloadStates.collectAsStateWithLifecycle()

    var selectedSongs by remember { mutableStateOf(setOf<Song>()) }
    val isSelectionMode = selectedSongs.isNotEmpty()
    // Back clears the selection before it leaves the screen.
    BackHandler(enabled = isSelectionMode) { selectedSongs = emptySet() }

    val shareViewModel: ShareViewModel = hiltViewModel()
    var showShareSheet by remember { mutableStateOf(false) }

    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var songForInfoSheet by remember { mutableStateOf<Song?>(null) }
    var showInfoSheetPlaylistPicker by remember { mutableStateOf(false) }
    var showBatchEditDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            Column {
                AnimatedContent(
                    targetState = isSelectionMode,
                    transitionSpec = {
                        (slideInVertically(tween(700, easing = EmphasizedDecelerate)) { -it / 3 } +
                            fadeIn(tween(700, delayMillis = 70, easing = EmphasizedDecelerate)))
                            .togetherWith(
                                slideOutVertically(tween(560, easing = EmphasizedAccelerate)) { -it / 3 } +
                                    fadeOut(tween(280, easing = EmphasizedAccelerate))
                            )
                    },
                    label = "songs_topbar",
                ) { selectionMode ->
                    if (selectionMode) {
                        TopAppBar(
                            title = { Text("${selectedSongs.size}") },
                            navigationIcon = {
                                IconButton(onClick = { selectedSongs = emptySet() }) {
                                    Icon(Icons.Rounded.Close, "Cancel")
                                }
                            },
                            actions = {
                                IconButton(onClick = { selectedSongs = songs.toSet() }) {
                                    Icon(Icons.Rounded.SelectAll, "Select All")
                                }
                                IconButton(onClick = {
                                    shareViewModel.preselectSongs(selectedSongs.toList())
                                    showShareSheet = true
                                }) {
                                    Icon(Icons.Rounded.Share, "Share")
                                }
                                IconButton(onClick = { showAddToPlaylistDialog = true }) {
                                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to Playlist")
                                }
                                if (musicSource == MusicSource.LOCAL) {
                                    IconButton(onClick = { showBatchEditDialog = true }) {
                                        Icon(Icons.Rounded.Edit, "Edit Tags")
                                    }
                                }
                                IconButton(onClick = {
                                    playerViewModel.addToQueueEnd(songs.filter { it in selectedSongs })
                                    selectedSongs = emptySet()
                                }) {
                                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Add to Queue")
                                }
                                IconButton(onClick = {
                                    playerViewModel.addToQueueNext(songs.filter { it in selectedSongs })
                                    selectedSongs = emptySet()
                                }) {
                                    Icon(Icons.Rounded.SkipNext, "Play Next")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        )
                    } else {
                        TopAppBar(
                            title = { LibraryTitle() },
                            actions = {
                                IconButton(onClick = {
                                    playerViewModel.playShuffled(songs)
                                    onNavigateToPlayer()
                                }, enabled = songs.isNotEmpty()) {
                                    Icon(Icons.Rounded.Shuffle, "Shuffle Play")
                                }
                                IconButton(onClick = onSearchClick) {
                                    Icon(Icons.Rounded.Search, "Search")
                                }
                                IconButton(onClick = { libraryViewModel.syncLibrary(force = true) }) {
                                    if (isSyncing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    } else {
                                        Icon(Icons.Rounded.Refresh, "Refresh")
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                            ),
                        )
                    }
                }
                LocalLibrarySections.current?.invoke()
                // Background sync with a cached library: a slim indicator, not a blocking view.
                val syncing = navSyncState as? NavidromeSyncState.Syncing
                if (syncing != null && songs.isNotEmpty()) {
                    val total = syncing.estimatedTotal
                    if (total > 0) {
                        LinearWavyProgressIndicator(
                            progress = { (syncing.songsDone.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    } else {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // The full-screen sync view is only for a first sync with nothing cached yet;
            // otherwise the cached library stays usable with a slim progress bar on top.
            if (navSyncState is NavidromeSyncState.Syncing && songs.isEmpty()) {
                NavidromeSyncingView(navSyncState = navSyncState)
            } else if (songs.isEmpty()) {
                if (libraryLoaded) EmptyLibraryView(onScan = { libraryViewModel.syncLibrary() })
            } else {
                val listState = rememberLazyListState()
                LazyColumnWithScrollbar(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                    item { if (LocalLibrarySections.current == null) BigScreenTitle("Songs") }
                    itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                        SongCard(
                            song = song,
                            modifier = Modifier.animateItem().staggeredEntrance(index),
                            isPlaying = currentSong?.id == song.id,
                            isSelected = selectedSongs.contains(song),
                            onClick = {
                                if (isSelectionMode) {
                                    selectedSongs = if (selectedSongs.contains(song)) {
                                        selectedSongs - song
                                    } else {
                                        selectedSongs + song
                                    }
                                } else {
                                    playerViewModel.play(songs, index)
                                    onNavigateToPlayer()
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    selectedSongs = setOf(song)
                                }
                            },
                            onLeadingClick = {
                                if (isSelectionMode) {
                                    selectedSongs = if (selectedSongs.contains(song)) {
                                        selectedSongs - song
                                    } else {
                                        selectedSongs + song
                                    }
                                } else {
                                    songForInfoSheet = song
                                }
                            },
                            onArtworkMissing = { libraryViewModel.getSongArtworkUrl(song) },
                        )
                    }
                }
                }
            }
        }
    }

    if (showAddToPlaylistDialog) {
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { showAddToPlaylistDialog = false },
            onPlaylistSelected = { playlist ->
                libraryViewModel.addSongsToPlaylist(playlist.id, selectedSongs.map { it.id })
                selectedSongs = emptySet()
                showAddToPlaylistDialog = false
            },
            onCreatePlaylist = { name ->
                libraryViewModel.createPlaylistWithSongs(name, selectedSongs.map { it.id })
                selectedSongs = emptySet()
                showAddToPlaylistDialog = false
            }
        )
    }

    if (showBatchEditDialog) {
        val batchSongIds = selectedSongs.map { it.id }
        BatchTagEditDialog(
            songCount = batchSongIds.size,
            onDismiss = { showBatchEditDialog = false },
            onApply = { albumArtist, album, genre, year ->
                showBatchEditDialog = false
                libraryViewModel.batchUpdateSongTags(
                    songIds = batchSongIds,
                    albumArtist = albumArtist,
                    album = album,
                    genre = genre,
                    year = year,
                ) { succeeded, total ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (succeeded == total) "Updated $succeeded song${if (succeeded == 1) "" else "s"}"
                            else "Updated $succeeded of $total songs — some files couldn't be written"
                        )
                    }
                }
                selectedSongs = emptySet()
            },
        )
    }

    if (showShareSheet) {
        ShareSheet(
            viewModel = shareViewModel,
            currentSongs = selectedSongs.toList(),
            onDismiss = { showShareSheet = false },
            onPlayNow = { receivedSong ->
                playerViewModel.play(listOf(receivedSong), 0)
                onNavigateToPlayer()
                showShareSheet = false
            },
        )
    }

    songForInfoSheet?.let { song ->
        SongInfoBottomSheet(
            song = song,
            onDismiss = {
                songForInfoSheet = null
                showInfoSheetPlaylistPicker = false
            },
            onPlayNext = { playerViewModel.addToQueueNext(song) },
            onAddToQueue = { playerViewModel.addToQueueEnd(song) },
            onAddToPlaylist = { showInfoSheetPlaylistPicker = true },
            playlists = playlists,
            showPlaylistPicker = showInfoSheetPlaylistPicker,
            onPlaylistSelected = { playlist ->
                libraryViewModel.addSongsToPlaylist(playlist.id, listOf(song.id))
                songForInfoSheet = null
                showInfoSheetPlaylistPicker = false
            },
            onEditTags = { onNavigateToTagEditor(song.id) },
            isNavidromeSource = musicSource == MusicSource.NAVIDROME,
            downloadState = downloadStates[song.id],
            onDownloadToggle = {
                if (downloadStates[song.id]?.state == DownloadState.DOWNLOADED) {
                    libraryViewModel.removeDownload(song.id)
                } else {
                    libraryViewModel.downloadSong(song.id)
                }
            },
        )
    }
}

@Composable
private fun NavidromeSyncingView(navSyncState: NavidromeSyncState) {
    val syncing = navSyncState as? NavidromeSyncState.Syncing

    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(syncing != null) {
        if (syncing != null) {
            elapsedSeconds = 0L
            while (true) {
                delay(1_000)
                elapsedSeconds++
            }
        }
    }

    val albumsDone = syncing?.albumsDone ?: 0
    val songsDone = syncing?.songsDone ?: 0
    val estimatedTotal = syncing?.estimatedTotal ?: 0

    val progress = if (estimatedTotal > 0 && songsDone > 0)
        (songsDone.toFloat() / estimatedTotal).coerceIn(0f, 0.99f) else null

    val etaText: String? = if (elapsedSeconds > 5 && songsDone > 0 && estimatedTotal > songsDone) {
        val etaSecs = (elapsedSeconds * (estimatedTotal - songsDone).toFloat() / songsDone).toLong()
        when {
            etaSecs < 60 -> "Less than a minute remaining"
            etaSecs < 3600 -> "~${etaSecs / 60} min remaining"
            else -> "~${etaSecs / 3600}h ${(etaSecs % 3600) / 60}m remaining"
        }
    } else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val rotation by rememberInfiniteTransition(label = "sync-icon")
            .animateFloat(
                initialValue = 0f, targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(2520, easing = LinearEasing)),
                label = "rotation",
            )
        Icon(
            Icons.Rounded.Sync, null,
            modifier = Modifier.size(48.dp).graphicsLayer { rotationZ = rotation },
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Syncing your library",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Downloading your music from Navidrome…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(20.dp))

        if (albumsDone > 0 || songsDone > 0) {
            Text(
                "$albumsDone albums · $songsDone songs found",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (etaText != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    etaText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                "Starting…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Info, null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "Keep Resonance open while syncing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
fun EmptyLibraryView(onScan: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().staggeredEntrance(0),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.LibraryMusic,
            null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No songs found",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onScan) {
            Icon(Icons.Rounded.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text("Scan for Music")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    libraryViewModel: LibraryViewModel,
    onAlbumClick: (Album) -> Unit,
    onSearchClick: () -> Unit,
) {
    val libraryLoaded by libraryViewModel.libraryLoaded.collectAsStateWithLifecycle()
    val albums by libraryViewModel.allAlbums.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { LibraryTitle() },
                    actions = {
                        IconButton(onClick = onSearchClick) {
                            Icon(Icons.Rounded.Search, "Search")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                )
                LocalLibrarySections.current?.invoke()
            }
        }
    ) { padding ->
        if (albums.isEmpty()) {
            if (libraryLoaded) EmptyLibraryView(onScan = { libraryViewModel.syncLibrary() })
        } else {
            val gridState = rememberLazyGridState()
            LazyGridWithScrollbar(
                state = gridState,
                columnCount = 2,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, top = padding.calculateTopPadding(), bottom = 16.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) { if (LocalLibrarySections.current == null) BigScreenTitle("Albums") }
                    itemsIndexed(albums, key = { _, a -> a.id }) { index, album ->
                        AlbumCard(
                            album = album,
                            onClick = { onAlbumClick(album) },
                            modifier = Modifier.animateItem().staggeredEntrance(index),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumCard(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "album_card_press",
    )
    Column(
        modifier = modifier
            .scale(pressScale)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
            .padding(8.dp),
    ) {
        ArtworkImage(
            uri = album.artworkUri,
            contentDescription = album.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            cornerRadius = 16.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = album.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistsScreen(
    libraryViewModel: LibraryViewModel,
    onArtistClick: (Artist) -> Unit,
    onSearchClick: () -> Unit,
) {
    val libraryLoaded by libraryViewModel.libraryLoaded.collectAsStateWithLifecycle()
    val artists by libraryViewModel.allArtists.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { LibraryTitle() },
                    actions = {
                        IconButton(onClick = onSearchClick) {
                            Icon(Icons.Rounded.Search, "Search")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                )
                LocalLibrarySections.current?.invoke()
            }
        }
    ) { padding ->
        if (artists.isEmpty()) {
            if (libraryLoaded) EmptyLibraryView(onScan = { libraryViewModel.syncLibrary() })
        } else {
            val fetchImages by libraryViewModel.prefs.fetchArtistImages.collectAsStateWithLifecycle(initialValue = false)
            val listState = rememberLazyListState()
            LazyColumnWithScrollbar(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding(), bottom = 16.dp
                    ),
                ) {
                    item { if (LocalLibrarySections.current == null) BigScreenTitle("Artists") }
                    itemsIndexed(artists, key = { _, a -> a.name }) { index, artist ->
                        var imageUrl by remember(artist.name) { mutableStateOf<String?>(null) }
                        LaunchedEffect(artist.name, fetchImages) {
                            imageUrl = if (fetchImages) libraryViewModel.getArtistArtworkUrl(artist.name) else null
                        }
                        ListItem(
                            modifier = Modifier.animateItem().staggeredEntrance(index).clickable { onArtistClick(artist) },
                            headlineContent = { Text(artist.name, fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("${artist.songCount} songs · ${artist.albumCount} albums") },
                            leadingContent = {
                                Crossfade(
                                    targetState = fetchImages && imageUrl != null,
                                    animationSpec = tween(280, easing = EmphasizedDecelerate),
                                    label = "artist_row_avatar",
                                ) { hasImage ->
                                    if (hasImage) {
                                        coil.compose.AsyncImage(
                                            model = imageUrl,
                                            contentDescription = artist.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(52.dp)
                                                .clip(CircleShape),
                                        )
                                    } else {
                                        Surface(
                                            modifier = Modifier.size(52.dp),
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                        ) {
                                            Icon(
                                                Icons.Rounded.Person,
                                                null,
                                                modifier = Modifier.padding(12.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    libraryViewModel: LibraryViewModel,
    onPlaylistClick: (Playlist) -> Unit,
) {
    val playlists by libraryViewModel.allPlaylists.collectAsStateWithLifecycle()
    val importState by libraryViewModel.m3uImportState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showSmartEditor by rememberSaveable { mutableStateOf(false) }
    val launchImport = rememberM3uImportLauncher { uris -> libraryViewModel.startM3uImport(uris) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    IconButton(onClick = launchImport) {
                        Icon(Icons.Rounded.FileOpen, "Import M3U playlist")
                    }
                    IconButton(onClick = { showSmartEditor = true }) {
                        Icon(Icons.Rounded.AutoAwesome, "New smart playlist")
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Rounded.Add, "New Playlist")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        }
    ) { padding ->
        if (playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.staggeredEntrance(0)) {
                    Icon(
                        Icons.AutoMirrored.Rounded.PlaylistAdd, null, modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No playlists yet", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { showCreateDialog = true }) {
                        Text("Create Playlist")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = launchImport) {
                        Text("Import M3U")
                    }
                }
            }
        } else {
            val playlistCols = LocalAppearanceConfig.current.playlistGridColumns
            LazyVerticalGrid(
                columns = GridCells.Fixed(playlistCols),
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp, top = padding.calculateTopPadding(), bottom = 16.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { BigScreenTitle("Playlists") }
                itemsIndexed(playlists, key = { _, p -> p.id }) { index, playlist ->
                    PlaylistCard(
                        playlist = playlist,
                        onClick = { onPlaylistClick(playlist) },
                        modifier = Modifier.animateItem().staggeredEntrance(index),
                    )
                }
            }
        }
    }

    if (showSmartEditor) {
        SmartPlaylistEditor(
            onDismiss = { showSmartEditor = false },
            onSave = { name, rules ->
                libraryViewModel.saveSmartPlaylist(null, name, rules)
                showSmartEditor = false
            },
        )
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name: String ->
                libraryViewModel.createPlaylist(name)
                showCreateDialog = false
            }
        )
    }

    M3uImportDialogs(
        state = importState,
        onConfirm = libraryViewModel::confirmM3uImport,
        onDismiss = libraryViewModel::dismissM3uImport,
        onOpenPlaylist = { id -> playlists.find { it.id == id }?.let(onPlaylistClick) },
    )
}

@Composable
fun PlaylistCard(playlist: Playlist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "playlist_card_press",
    )
    Column(
        modifier = modifier
            .scale(pressScale)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
            .padding(8.dp),
    ) {
        when {
            playlist.isMix && playlist.mixType != null -> MixArtwork(
                mixType = playlist.mixType,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            playlist.artworkUri != null -> ArtworkImage(
                uri = playlist.artworkUri,
                contentDescription = playlist.name,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            else -> Surface(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (playlist.isSmart) Icons.Rounded.AutoAwesome else Icons.AutoMirrored.Rounded.QueueMusic,
                        null,
                        modifier = Modifier.size(48.dp),
                        tint = if (playlist.isSmart) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = if (playlist.isSmart) "Smart · ${playlist.songCount} songs" else "${playlist.songCount} songs",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumId: Long,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onNavigateToPlayer: () -> Unit,
    onNavigateToTagEditor: (Long) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
) {
    val albums by libraryViewModel.allAlbums.collectAsStateWithLifecycle()
    val allSongs by libraryViewModel.allSongs.collectAsStateWithLifecycle()
    val playlists by libraryViewModel.allPlaylists.collectAsStateWithLifecycle()
    val musicSource by libraryViewModel.prefs.musicSource.collectAsStateWithLifecycle(initialValue = MusicSource.LOCAL)
    val downloadStates by libraryViewModel.downloadStates.collectAsStateWithLifecycle()

    val album = remember(albums, albumId) { albums.find { it.id == albumId } }
    val albumSongs = remember(allSongs, album) {
        allSongs.filter { it.albumId == albumId }.sortedBy { it.trackNumber }
    }

    var songForInfoSheet by remember { mutableStateOf<Song?>(null) }
    var showPlaylistPicker by remember { mutableStateOf(false) }

    songForInfoSheet?.let { song ->
        SongInfoBottomSheet(
            song = song,
            onDismiss = { songForInfoSheet = null; showPlaylistPicker = false },
            onPlayNext = { playerViewModel.addToQueueNext(song) },
            onAddToQueue = { playerViewModel.addToQueueEnd(song) },
            onAddToPlaylist = { showPlaylistPicker = true },
            playlists = playlists,
            showPlaylistPicker = showPlaylistPicker,
            onPlaylistSelected = { playlist ->
                libraryViewModel.addSongsToPlaylist(playlist.id, listOf(song.id))
                songForInfoSheet = null
                showPlaylistPicker = false
            },
            onEditTags = { onNavigateToTagEditor(song.id) },
            isNavidromeSource = musicSource == MusicSource.NAVIDROME,
            downloadState = downloadStates[song.id],
            onDownloadToggle = {
                if (downloadStates[song.id]?.state == DownloadState.DOWNLOADED) {
                    libraryViewModel.removeDownload(song.id)
                } else {
                    libraryViewModel.downloadSong(song.id)
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
    ) { padding ->
        album?.let { currentAlbum ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = 16.dp
                ),
            ) {
                item {
                    AlbumHeader(
                        album = currentAlbum,
                        songs = albumSongs,
                        onPlayClick = {
                            playerViewModel.play(albumSongs, 0)
                            onNavigateToPlayer()
                        },
                        onArtistClick = { onArtistClick(currentAlbum.artist) },
                        showDownloadAction = musicSource == MusicSource.NAVIDROME,
                        onDownloadClick = { libraryViewModel.downloadAlbum(albumId) },
                        modifier = Modifier.staggeredEntrance(0),
                    )
                }
                itemsIndexed(albumSongs, key = { _, s -> s.id }) { index, song ->
                    SongCard(
                        song = song,
                        modifier = Modifier.animateItem().staggeredEntrance(index),
                        onClick = {
                            playerViewModel.play(albumSongs, index)
                            onNavigateToPlayer()
                        },
                        onLongClick = { songForInfoSheet = song },
                        onArtworkMissing = { libraryViewModel.getSongArtworkUrl(song) },
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumHeader(
    album: Album,
    songs: List<Song>,
    onPlayClick: () -> Unit,
    onArtistClick: () -> Unit = {},
    showDownloadAction: Boolean = false,
    onDownloadClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtworkImage(
            uri = album.artworkUri,
            contentDescription = album.title,
            modifier = Modifier
                .size(240.dp)
                .shadow(16.dp, MaterialTheme.shapes.large),
            cornerRadius = 24.dp,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = album.artist,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.clickable(onClick = onArtistClick),
        )
        Text(
            text = "${songs.size} songs · ${album.year.takeIf { it > 0 } ?: ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onPlayClick,
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("Play")
            }
            if (showDownloadAction) {
                OutlinedButton(onClick = onDownloadClick, shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Download")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    artistName: String,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onAlbumClick: (Album) -> Unit,
    onBack: () -> Unit,
    onNavigateToPlayer: () -> Unit,
    onNavigateToTagEditor: (Long) -> Unit = {},
) {
    val artists by libraryViewModel.allArtists.collectAsStateWithLifecycle()
    val albums by libraryViewModel.allAlbums.collectAsStateWithLifecycle()
    val playlists by libraryViewModel.allPlaylists.collectAsStateWithLifecycle()
    val musicSource by libraryViewModel.prefs.musicSource.collectAsStateWithLifecycle(initialValue = MusicSource.LOCAL)
    val downloadStates by libraryViewModel.downloadStates.collectAsStateWithLifecycle()

    val artist = remember(artists, artistName) { artists.find { it.name == artistName } }
    val artistSongs = remember(artist) { artist?.songs ?: emptyList() }
    val artistAlbums = remember(albums, artistName) { albums.filter { album -> album.songs.any { artistName in it.artists } } }

    var songForInfoSheet by remember { mutableStateOf<Song?>(null) }
    var showPlaylistPicker by remember { mutableStateOf(false) }

    songForInfoSheet?.let { song ->
        SongInfoBottomSheet(
            song = song,
            onDismiss = { songForInfoSheet = null; showPlaylistPicker = false },
            onPlayNext = { playerViewModel.addToQueueNext(song) },
            onAddToQueue = { playerViewModel.addToQueueEnd(song) },
            onAddToPlaylist = { showPlaylistPicker = true },
            playlists = playlists,
            showPlaylistPicker = showPlaylistPicker,
            onPlaylistSelected = { playlist ->
                libraryViewModel.addSongsToPlaylist(playlist.id, listOf(song.id))
                songForInfoSheet = null
                showPlaylistPicker = false
            },
            onEditTags = { onNavigateToTagEditor(song.id) },
            isNavidromeSource = musicSource == MusicSource.NAVIDROME,
            downloadState = downloadStates[song.id],
            onDownloadToggle = {
                if (downloadStates[song.id]?.state == DownloadState.DOWNLOADED) {
                    libraryViewModel.removeDownload(song.id)
                } else {
                    libraryViewModel.downloadSong(song.id)
                }
            },
        )
    }

    var showArtistInfoSheet by remember { mutableStateOf(false) }
    val artistInfoViewModel: ArtistInfoViewModel = hiltViewModel()
    val artistInfoState by artistInfoViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(showArtistInfoSheet, artistName) {
        if (showArtistInfoSheet) artistInfoViewModel.load(artistName)
    }
    if (showArtistInfoSheet) {
        ArtistInfoBottomSheet(
            state = artistInfoState,
            onDismiss = { showArtistInfoSheet = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showArtistInfoSheet = true }) {
                        Icon(Icons.Rounded.Info, "Artist Info")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
    ) { padding ->
        artist?.let {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 16.dp),
            ) {
                item {
                    ArtistHeader(
                        artist = it,
                        songs = artistSongs,
                        libraryViewModel = libraryViewModel,
                        modifier = Modifier.staggeredEntrance(0),
                    ) {
                        playerViewModel.play(artistSongs, 0)
                        onNavigateToPlayer()
                    }
                }

                if (artistAlbums.isNotEmpty()) {
                    item {
                        Text(
                            "Albums",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(artistAlbums, key = { _, a -> a.id }) { index, album ->
                                Column(
                                    modifier = Modifier
                                        .width(140.dp)
                                        .staggeredEntrance(index)
                                        .clickable { onAlbumClick(album) }
                                ) {
                                    ArtworkImage(
                                        uri = album.artworkUri,
                                        contentDescription = album.title,
                                        modifier = Modifier.size(140.dp),
                                        cornerRadius = 16.dp,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        album.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        "Songs",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                itemsIndexed(artistSongs, key = { _, s -> s.id }) { index, song ->
                    SongCard(
                        song = song,
                        modifier = Modifier.animateItem().staggeredEntrance(index),
                        onClick = {
                            playerViewModel.play(artistSongs, index)
                            onNavigateToPlayer()
                        },
                        onLongClick = { songForInfoSheet = song },
                        onArtworkMissing = { libraryViewModel.getSongArtworkUrl(song) },
                    )
                }
            }
        }
    }
}

@Composable
fun ArtistHeader(
    artist: Artist,
    songs: List<Song>,
    libraryViewModel: LibraryViewModel,
    modifier: Modifier = Modifier,
    onPlayClick: () -> Unit,
) {
    val fetchImages by libraryViewModel.prefs.fetchArtistImages.collectAsStateWithLifecycle(initialValue = false)
    var imageUrl by remember(artist.name) { mutableStateOf<String?>(null) }

    LaunchedEffect(artist.name, fetchImages) {
        if (fetchImages) {
            imageUrl = libraryViewModel.getArtistArtworkUrl(artist.name)
        } else {
            imageUrl = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Crossfade(
            targetState = fetchImages && imageUrl != null,
            animationSpec = tween(280, easing = EmphasizedDecelerate),
            label = "artist_header_avatar",
        ) { hasImage ->
            if (hasImage) {
                coil.compose.AsyncImage(
                    model = imageUrl,
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(160.dp)
                        .shadow(8.dp, CircleShape)
                        .clip(CircleShape),
                )
            } else {
                Surface(
                    modifier = Modifier.size(160.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Person,
                            null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "${artist.albumCount} albums · ${songs.size} songs",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onPlayClick,
            modifier = Modifier.fillMaxWidth(0.6f),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Rounded.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("Play All")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onNavigateToPlayer: () -> Unit,
) {
    val playlists by libraryViewModel.allPlaylists.collectAsStateWithLifecycle()
    val allSongs by libraryViewModel.allSongs.collectAsStateWithLifecycle()
    val musicSource by libraryViewModel.prefs.musicSource.collectAsStateWithLifecycle(initialValue = MusicSource.LOCAL)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val playlist = remember(playlists, playlistId) { playlists.find { it.id == playlistId } }

    var sortOrder by rememberSaveable { mutableStateOf(SortOrder.NATURAL) }
    val playlistSongs: List<Song> = remember(allSongs, playlist, sortOrder) {
        val base = playlist?.songs ?: emptyList()
        when (sortOrder) {
            SortOrder.TITLE_ASC -> base.sortedBy { it.title.lowercase() }
            SortOrder.TITLE_DESC -> base.sortedByDescending { it.title.lowercase() }
            SortOrder.ARTIST_ASC -> base.sortedBy { it.artist.lowercase() }
            SortOrder.ARTIST_DESC -> base.sortedByDescending { it.artist.lowercase() }
            SortOrder.ALBUM_ASC -> base.sortedBy { it.album.lowercase() }
            SortOrder.ALBUM_DESC -> base.sortedByDescending { it.album.lowercase() }
            SortOrder.DURATION_ASC -> base.sortedBy { it.duration }
            SortOrder.DURATION_DESC -> base.sortedByDescending { it.duration }
            SortOrder.DATE_ADDED_ASC -> base.sortedBy { it.dateAdded }
            SortOrder.DATE_ADDED_DESC -> base.sortedByDescending { it.dateAdded }
            SortOrder.LISTEN_COUNT_DESC -> base.sortedByDescending { it.listenCount }
            else -> base
        }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSmartEditor by rememberSaveable { mutableStateOf(false) }

    // Dragging only works in natural order; any other sort would undo it right away. Smart
    // playlists and mixes are read-only.
    val editable = playlist?.isReadOnly != true
    val canReorder = editable && sortOrder == SortOrder.NATURAL
    val localOrder = remember { mutableStateListOf<Song>() }
    var draggedSongId by remember { mutableStateOf<Long?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    // After a drag, the dropped row springs back into place. settleOffset animates that row
    // while dragOffsetY is already reset for the next drag.
    var settlingSongId by remember { mutableStateOf<Long?>(null) }
    val settleSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val settleOffset = remember { Animatable(0f) }
    var rowHeightPx by remember { mutableIntStateOf(0) }
    val playlistListState = rememberLazyListState()
    LaunchedEffect(playlistSongs) {
        if (draggedSongId == null) {
            localOrder.clear()
            localOrder.addAll(playlistSongs)
        }
    }

    // Scrolls while a dragged row is held near the top or bottom edge. onDrag only fires on
    // movement, so this loop keeps scrolling while the finger rests at the edge.
    LaunchedEffect(draggedSongId) {
        val id = draggedSongId ?: return@LaunchedEffect
        while (isActive && draggedSongId == id) {
            val layoutInfo = playlistListState.layoutInfo
            val itemInfo = layoutInfo.visibleItemsInfo.find { it.key == id }
            if (itemInfo != null) {
                val itemTop = itemInfo.offset + dragOffsetY
                val itemBottom = itemTop + itemInfo.size
                val edgeThresholdPx = 150f
                val viewportStart = layoutInfo.viewportStartOffset
                val viewportEnd = layoutInfo.viewportEndOffset
                when {
                    itemTop < viewportStart + edgeThresholdPx -> playlistListState.scrollBy(-14f)
                    itemBottom > viewportEnd - edgeThresholdPx -> playlistListState.scrollBy(14f)
                }
            }
            delay(16)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { libraryViewModel.updatePlaylistArtwork(playlistId, it) } }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        val target = playlist
        if (uri != null && target != null) {
            scope.launch {
                val error = libraryViewModel.exportPlaylist(target, uri)
                snackbarHostState.showSnackbar(if (error == null) "Playlist exported" else "Export failed: $error")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, "More options")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            val smart = playlist?.isSmart == true
                            val mix = playlist?.isMix == true
                            if (smart) {
                                DropdownMenuItem(
                                    text = { Text("Edit rules") },
                                    leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) },
                                    onClick = { showOverflowMenu = false; showSmartEditor = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Convert to normal playlist") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        libraryViewModel.convertSmartToStatic(playlist)
                                    }
                                )
                            } else if (!mix) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                    onClick = { showOverflowMenu = false; showRenameDialog = true }
                                )
                            }
                            if (editable) DropdownMenuItem(
                                text = { Text("Change Cover") },
                                leadingIcon = { Icon(Icons.Rounded.Image, null) },
                                onClick = { showOverflowMenu = false; imagePicker.launch("image/*") }
                            )
                            if (editable) DropdownMenuItem(
                                text = { Text("Sort by…") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Sort, null) },
                                onClick = { showOverflowMenu = false; showSortDialog = true }
                            )
                            if (editable) DropdownMenuItem(
                                text = { Text("Remove Duplicates") },
                                leadingIcon = { Icon(Icons.Rounded.FilterList, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    scope.launch {
                                        val removed = libraryViewModel.deduplicatePlaylist(playlistId)
                                        snackbarHostState.showSnackbar(
                                            if (removed == 0) "No duplicates found"
                                            else "$removed duplicate${if (removed == 1) "" else "s"} removed"
                                        )
                                    }
                                }
                            )
                            if (musicSource == MusicSource.NAVIDROME) {
                                DropdownMenuItem(
                                    text = { Text("Download Playlist") },
                                    leadingIcon = { Icon(Icons.Rounded.Download, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        libraryViewModel.downloadPlaylist(playlistId)
                                        scope.launch { snackbarHostState.showSnackbar("Downloading playlist…") }
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Export as M3U") },
                                leadingIcon = { Icon(Icons.Rounded.FileDownload, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    exportLauncher.launch("${playlist?.name ?: "playlist"}.m3u8")
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Delete Playlist", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { showOverflowMenu = false; showDeleteDialog = true }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
    ) { padding ->
        playlist?.let {
            LazyColumn(
                state = playlistListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 16.dp),
            ) {
                item {
                    PlaylistHeader(
                        playlist = it,
                        songs = playlistSongs,
                        onChangeCover = { if (editable) imagePicker.launch("image/*") },
                        onPlayClick = {
                            if (playlistSongs.isNotEmpty()) {
                                playerViewModel.play(playlistSongs, 0)
                                onNavigateToPlayer()
                            }
                        },
                        onShuffleClick = {
                            playerViewModel.playShuffled(playlistSongs)
                            onNavigateToPlayer()
                        },
                        modifier = Modifier.staggeredEntrance(0),
                    )
                }
                itemsIndexed(localOrder, key = { _, s -> s.id }) { index, song ->
                    val isDragged = draggedSongId == song.id
                    val isSettling = settlingSongId == song.id
                    val handleAlpha by animateFloatAsState(
                        targetValue = if (canReorder) 1f else 0.3f,
                        animationSpec = tween(280, easing = EmphasizedDecelerate),
                        label = "drag_handle_alpha",
                    )
                    SongCard(
                        song = song,
                        modifier = Modifier
                            .animateItem(tween(310))
                            .graphicsLayer {
                                translationY = when {
                                    isDragged -> dragOffsetY
                                    isSettling -> settleOffset.value
                                    else -> 0f
                                }
                            }
                            .zIndex(if (isDragged || isSettling) 1f else 0f)
                            .onSizeChanged { rowHeightPx = it.height },
                        onClick = {
                            playerViewModel.play(localOrder, index)
                            onNavigateToPlayer()
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (editable) IconButton(onClick = {
                                    libraryViewModel.removeSongsFromPlaylist(playlistId, listOf(song.id)) { undo ->
                                        scope.launch {
                                            snackbarHostState.currentSnackbarData?.dismiss()
                                            val result = snackbarHostState.showSnackbar(
                                                "“${song.title}” removed", actionLabel = "Undo", duration = SnackbarDuration.Short,
                                            )
                                            if (result == SnackbarResult.ActionPerformed) undo()
                                        }
                                    }
                                }) {
                                    Icon(Icons.Rounded.RemoveCircleOutline, "Remove")
                                }
                                if (editable) Icon(
                                    Icons.Rounded.DragHandle,
                                    contentDescription = "Reorder",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = handleAlpha),
                                    modifier = if (canReorder) {
                                        Modifier.pointerInput(song.id) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { draggedSongId = song.id; dragOffsetY = 0f },
                                                onDragEnd = {
                                                    val endedId = draggedSongId
                                                    val endedOffset = dragOffsetY
                                                    draggedSongId = null
                                                    dragOffsetY = 0f
                                                    libraryViewModel.reorderPlaylist(playlistId, localOrder.toList())
                                                    if (endedId != null && endedOffset != 0f) {
                                                        settlingSongId = endedId
                                                        scope.launch {
                                                            settleOffset.snapTo(endedOffset)
                                                            settleOffset.animateTo(
                                                                0f,
                                                                settleSpec,
                                                            )
                                                            settlingSongId = null
                                                        }
                                                    }
                                                },
                                                onDragCancel = {
                                                    val endedId = draggedSongId
                                                    val endedOffset = dragOffsetY
                                                    draggedSongId = null
                                                    dragOffsetY = 0f
                                                    if (endedId != null && endedOffset != 0f) {
                                                        settlingSongId = endedId
                                                        scope.launch {
                                                            settleOffset.snapTo(endedOffset)
                                                            settleOffset.animateTo(
                                                                0f,
                                                                settleSpec,
                                                            )
                                                            settlingSongId = null
                                                        }
                                                    }
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragOffsetY += dragAmount.y
                                                    val currentId = draggedSongId ?: return@detectDragGesturesAfterLongPress
                                                    val fromIndex = localOrder.indexOfFirst { it.id == currentId }
                                                    if (fromIndex == -1) return@detectDragGesturesAfterLongPress
                                                    val heightPx = rowHeightPx.coerceAtLeast(1)
                                                    if (abs(dragOffsetY) >= heightPx) {
                                                        val moveBy = (dragOffsetY / heightPx).toInt()
                                                        val toIndex = (fromIndex + moveBy).coerceIn(0, localOrder.lastIndex)
                                                        if (toIndex != fromIndex) {
                                                            val moved = localOrder.removeAt(fromIndex)
                                                            localOrder.add(toIndex, moved)
                                                            dragOffsetY -= moveBy * heightPx
                                                        }
                                                    }
                                                },
                                            )
                                        }
                                    } else {
                                        Modifier.clickable {
                                            scope.launch { snackbarHostState.showSnackbar("Reordering requires Natural sort order") }
                                        }
                                    },
                                )
                            }
                        },
                        onArtworkMissing = { libraryViewModel.getSongArtworkUrl(song) },
                    )
                }
            }
        }
    }

    if (showSmartEditor) {
        playlist?.smartRules?.let { rules ->
            SmartPlaylistEditor(
                initialName = playlist.name,
                initialRules = rules,
                onDismiss = { showSmartEditor = false },
                onSave = { name, newRules ->
                    libraryViewModel.saveSmartPlaylist(playlistId, name, newRules)
                    showSmartEditor = false
                },
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Playlist") },
            text = { Text("Are you sure you want to delete \"${playlist?.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    libraryViewModel.deletePlaylist(playlistId)
                    showDeleteDialog = false
                    onBack()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showRenameDialog) {
        RenamePlaylistDialog(
            currentName = playlist?.name ?: "",
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                libraryViewModel.renamePlaylist(playlistId, newName)
                showRenameDialog = false
            }
        )
    }

    if (showSortDialog) {
        PlaylistSortDialog(
            current = sortOrder,
            onDismiss = { showSortDialog = false },
            onSelect = { order ->
                sortOrder = order
                showSortDialog = false
            }
        )
    }
}

@Composable
fun PlaylistHeader(
    playlist: Playlist,
    songs: List<Song>,
    onChangeCover: () -> Unit,
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(MaterialTheme.shapes.large)
                .clickable(onClick = onChangeCover),
            contentAlignment = Alignment.Center,
        ) {
            when {
                playlist.isMix && playlist.mixType != null -> MixArtwork(
                    mixType = playlist.mixType,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 24.dp,
                )
                playlist.artworkUri != null -> ArtworkImage(
                    uri = playlist.artworkUri,
                    contentDescription = playlist.name,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 24.dp,
                )
                else -> Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                    }
                }
            }
            if (!playlist.isReadOnly) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = "Change cover",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "${songs.size} songs · ${formatDuration(songs.sumOf { it.duration })}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onPlayClick,
                shape = MaterialTheme.shapes.medium,
                enabled = songs.isNotEmpty()
            ) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("Play All")
            }
            OutlinedButton(
                onClick = onShuffleClick,
                shape = MaterialTheme.shapes.medium,
                enabled = songs.isNotEmpty()
            ) {
                Icon(Icons.Rounded.Shuffle, null)
                Spacer(Modifier.width(8.dp))
                Text("Shuffle")
            }
        }
    }
}

@Composable
fun RenamePlaylistDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Playlist Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun PlaylistSortDialog(
    current: SortOrder,
    onDismiss: () -> Unit,
    onSelect: (SortOrder) -> Unit,
) {
    val options = listOf(
        SortOrder.NATURAL        to "Default order",
        SortOrder.TITLE_ASC      to "Title (A → Z)",
        SortOrder.TITLE_DESC     to "Title (Z → A)",
        SortOrder.ARTIST_ASC     to "Artist (A → Z)",
        SortOrder.ARTIST_DESC    to "Artist (Z → A)",
        SortOrder.ALBUM_ASC      to "Album (A → Z)",
        SortOrder.ALBUM_DESC     to "Album (Z → A)",
        SortOrder.DURATION_ASC   to "Duration (shortest first)",
        SortOrder.DURATION_DESC  to "Duration (longest first)",
        SortOrder.DATE_ADDED_ASC to "Date added (oldest first)",
        SortOrder.DATE_ADDED_DESC to "Date added (newest first)",
        SortOrder.LISTEN_COUNT_DESC to "Most played",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort playlist") },
        text = {
            Column {
                options.forEach { (order, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(order) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    ) {
                        RadioButton(
                            selected = current == order,
                            onClick = { onSelect(order) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPlaylistSelected: (Playlist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Playlist") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.5f).dp),
            ) {
                item {
                    ListItem(
                        modifier = Modifier.clickable { showCreateDialog = true },
                        headlineContent = { Text("New Playlist...", color = MaterialTheme.colorScheme.primary) },
                        leadingContent = { Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary) }
                    )
                }
                items(playlists, key = { it.id }) { playlist ->
                    ListItem(
                        modifier = Modifier.clickable { onPlaylistSelected(playlist) },
                        headlineContent = { Text(playlist.name) },
                        leadingContent = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                onCreatePlaylist(name)
                showCreateDialog = false
            }
        )
    }
}

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Playlist Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// Batch tag edit. A blank field leaves that tag unchanged on every selected song.
@Composable
fun BatchTagEditDialog(
    songCount: Int,
    onDismiss: () -> Unit,
    onApply: (albumArtist: String?, album: String?, genre: String?, year: Int?) -> Unit,
) {
    var albumArtist by rememberSaveable { mutableStateOf("") }
    var album by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Tags for $songCount Songs") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Only fields you fill in will be changed — leave a field blank to keep each song's existing value.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = albumArtist,
                    onValueChange = { albumArtist = it },
                    label = { Text("Album Artist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Album") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = genre,
                    onValueChange = { genre = it },
                    label = { Text("Genre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = year,
                    onValueChange = { year = it },
                    label = { Text("Year") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onApply(
                        albumArtist.trim().ifBlank { null },
                        album.trim().ifBlank { null },
                        genre.trim().ifBlank { null },
                        year.trim().toIntOrNull(),
                    )
                },
                enabled = albumArtist.isNotBlank() || album.isNotBlank() || genre.isNotBlank() || year.isNotBlank(),
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistInfoBottomSheet(
    state: ArtistInfoState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            when (state) {
                is ArtistInfoState.Loading,
                is ArtistInfoState.Idle -> {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                }
                is ArtistInfoState.Error -> {
                    Text(
                        "Couldn't load artist info.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ArtistInfoState.Loaded -> {
                    val info = state.info
                    Text("About", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))

                    if (info.formedYear != null || info.type != null) {
                        val statusText = buildString {
                            info.type?.let { append(it) }
                            if (info.formedYear != null) {
                                if (isNotEmpty()) append(" · ")
                                append("Formed ${info.formedYear}")
                            }
                            if (!info.isActive && info.dissolvedYear != null) {
                                append(" – ${info.dissolvedYear}")
                            }
                        }
                        Text(statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                    }

                    if (info.genres.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            info.genres.forEach { genre ->
                                AssistChip(onClick = {}, label = { Text(genre) })
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    if (!info.members.isNullOrEmpty()) {
                        Text("Members", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(info.members.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                    }

                    if (!info.bio.isNullOrBlank()) {
                        Text(info.bio, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                    }

                    if (info.formedYear == null && info.genres.isEmpty() && info.members.isNullOrEmpty() && info.bio.isNullOrBlank()) {
                        Text(
                            "No additional info found for this artist.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (info.sourceAttribution.isNotEmpty()) {
                        Text(
                            "Data from ${info.sourceAttribution.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongInfoBottomSheet(
    song: Song,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    playlists: List<Playlist>,
    showPlaylistPicker: Boolean,
    onPlaylistSelected: (Playlist) -> Unit,
    onEditTags: () -> Unit,
    isNavidromeSource: Boolean = false,
    downloadState: SongDownloadEntity? = null,
    onDownloadToggle: () -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (showPlaylistPicker) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Text(
                    "Add to Playlist",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp)
                )
                LazyColumn {
                    items(playlists, key = { it.id }) { playlist ->
                        ListItem(
                            modifier = Modifier.clickable { onPlaylistSelected(playlist) },
                            headlineContent = { Text(playlist.name) },
                            leadingContent = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) }
                        )
                    }
                }
            }
        } else {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                ListItem(
                    headlineContent = { Text(song.title, fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(song.displayArtist) },
                    leadingContent = {
                        ArtworkImage(
                            uri = song.artworkUri,
                            contentDescription = song.album,
                            modifier = Modifier.size(48.dp),
                            cornerRadius = 8.dp
                        )
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                ListItem(
                    modifier = Modifier.clickable {
                        onPlayNext()
                        onDismiss()
                    },
                    headlineContent = { Text("Play Next") },
                    leadingContent = { Icon(Icons.Rounded.SkipNext, null) }
                )
                ListItem(
                    modifier = Modifier.clickable {
                        onAddToQueue()
                        onDismiss()
                    },
                    headlineContent = { Text("Add to Queue") },
                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) }
                )
                ListItem(
                    modifier = Modifier.clickable { onAddToPlaylist() },
                    headlineContent = { Text("Add to Playlist") },
                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) }
                )

                if (isNavidromeSource) {
                    val (downloadLabel, downloadIcon, downloadEnabled) = when (downloadState?.state) {
                        DownloadState.DOWNLOADED -> Triple("Remove Download", Icons.Rounded.DownloadDone, true)
                        DownloadState.DOWNLOADING -> Triple("Downloading…", Icons.Rounded.Downloading, false)
                        DownloadState.QUEUED -> Triple("Queued…", Icons.Rounded.Downloading, false)
                        DownloadState.FAILED -> Triple("Retry Download", Icons.Rounded.Download, true)
                        else -> Triple("Download", Icons.Rounded.Download, true)
                    }
                    ListItem(
                        modifier = Modifier.clickable(enabled = downloadEnabled) { onDownloadToggle() },
                        headlineContent = {
                            AnimatedStateSwap(
                                targetState = downloadLabel,
                                contentAlignment = Alignment.CenterStart,
                                label = "download_state_label",
                            ) { label -> Text(label) }
                        },
                        leadingContent = {
                            AnimatedStateSwap(targetState = downloadIcon, label = "download_state_icon") { icon ->
                                Icon(icon, null)
                            }
                        }
                    )
                }

                // Streamed songs have no local file to write tags to.
                if (song.navidromeId == null) {
                    ListItem(
                        modifier = Modifier.clickable {
                            onEditTags()
                            onDismiss()
                        },
                        headlineContent = { Text("Edit Tags") },
                        leadingContent = { Icon(Icons.Rounded.Edit, null) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LikedSongsScreen(
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel,
    onBack: () -> Unit,
    onNavigateToPlayer: () -> Unit,
) {
    val libraryLoaded by libraryViewModel.libraryLoaded.collectAsStateWithLifecycle()
    val allSongs by libraryViewModel.allSongs.collectAsStateWithLifecycle()
    val likedIds by playerViewModel.likedSongIds.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlists by libraryViewModel.allPlaylists.collectAsStateWithLifecycle()

    val likedSongs: List<Song> = remember(allSongs, likedIds) {
        val songMap = allSongs.associateBy { it.id }
        likedIds.mapNotNull { songMap[it] }
    }

    var selectedSongs by remember { mutableStateOf(setOf<Song>()) }
    val isSelectionMode = selectedSongs.isNotEmpty()
    // Back clears the selection before it leaves the screen.
    BackHandler(enabled = isSelectionMode) { selectedSongs = emptySet() }

    val shareViewModel: ShareViewModel = hiltViewModel()
    var showShareSheet by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                AnimatedContent(
                    targetState = isSelectionMode,
                    transitionSpec = {
                        (slideInVertically(tween(700, easing = EmphasizedDecelerate)) { -it / 3 } +
                            fadeIn(tween(700, delayMillis = 70, easing = EmphasizedDecelerate)))
                            .togetherWith(
                                slideOutVertically(tween(560, easing = EmphasizedAccelerate)) { -it / 3 } +
                                    fadeOut(tween(280, easing = EmphasizedAccelerate))
                            )
                    },
                    label = "liked_songs_topbar",
                ) { selectionMode ->
                    if (selectionMode) {
                        TopAppBar(
                            title = { Text("${selectedSongs.size}") },
                            navigationIcon = {
                                IconButton(onClick = { selectedSongs = emptySet() }) {
                                    Icon(Icons.Rounded.Close, "Cancel")
                                }
                            },
                            actions = {
                                IconButton(onClick = { selectedSongs = likedSongs.toSet() }) {
                                    Icon(Icons.Rounded.SelectAll, "Select All")
                                }
                                IconButton(onClick = {
                                    shareViewModel.preselectSongs(selectedSongs.toList())
                                    showShareSheet = true
                                }) {
                                    Icon(Icons.Rounded.Share, "Share")
                                }
                                IconButton(onClick = { showAddToPlaylistDialog = true }) {
                                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to Playlist")
                                }
                                IconButton(onClick = {
                                    playerViewModel.addToQueueEnd(likedSongs.filter { it in selectedSongs })
                                    selectedSongs = emptySet()
                                }) {
                                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Add to Queue")
                                }
                                IconButton(onClick = {
                                    playerViewModel.addToQueueNext(likedSongs.filter { it in selectedSongs })
                                    selectedSongs = emptySet()
                                }) {
                                    Icon(Icons.Rounded.SkipNext, "Play Next")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        )
                    } else {
                        TopAppBar(
                            title = { LibraryTitle() },
                            actions = {
                                if (likedSongs.isNotEmpty()) {
                                    IconButton(onClick = {
                                        playerViewModel.playShuffled(likedSongs)
                                        onNavigateToPlayer()
                                    }) {
                                        Icon(Icons.Rounded.Shuffle, "Shuffle Play")
                                    }
                                    IconButton(onClick = {
                                        playerViewModel.play(likedSongs, 0)
                                        onNavigateToPlayer()
                                    }) {
                                        Icon(
                                            Icons.Rounded.PlayArrow,
                                            "Play all",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                            ),
                        )
                    }
                }
                LocalLibrarySections.current?.invoke()
            }
        },
    ) { padding ->
        if (likedSongs.isEmpty()) {
            if (libraryLoaded) Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val pulse = rememberInfiniteTransition(label = "heart_pulse")
                    val heartScale by pulse.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.12f,
                        animationSpec = infiniteRepeatable(tween(1120), RepeatMode.Reverse),
                        label = "heart_scale",
                    )
                    Icon(
                        Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .graphicsLayer { scaleX = heartScale; scaleY = heartScale },
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No liked songs yet",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap ♥ in the player to like a song",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = padding,
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Column {
                        Text(
                            "${likedSongs.size} songs",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        )
                    }
                }
                itemsIndexed(likedSongs, key = { _, s -> s.id }) { index, song ->
                    SongCard(
                        song = song,
                        modifier = Modifier.animateItem().staggeredEntrance(index),
                        isSelected = selectedSongs.contains(song),
                        onClick = {
                            if (isSelectionMode) {
                                selectedSongs = if (selectedSongs.contains(song)) {
                                    selectedSongs - song
                                } else {
                                    selectedSongs + song
                                }
                            } else {
                                playerViewModel.play(likedSongs, index)
                                onNavigateToPlayer()
                            }
                        },
                        onLongClick = {
                            if (!isSelectionMode) {
                                selectedSongs = setOf(song)
                            }
                        },
                        onLeadingClick = {
                            if (isSelectionMode) {
                                selectedSongs = if (selectedSongs.contains(song)) {
                                    selectedSongs - song
                                } else {
                                    selectedSongs + song
                                }
                            }
                        },
                        onArtworkMissing = { libraryViewModel.getSongArtworkUrl(song) },
                    )
                }
            }
        }
    }

    if (showAddToPlaylistDialog) {
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { showAddToPlaylistDialog = false },
            onPlaylistSelected = { playlist ->
                libraryViewModel.addSongsToPlaylist(playlist.id, selectedSongs.map { it.id })
                selectedSongs = emptySet()
                showAddToPlaylistDialog = false
            },
            onCreatePlaylist = { name ->
                libraryViewModel.createPlaylistWithSongs(name, selectedSongs.map { it.id })
                selectedSongs = emptySet()
                showAddToPlaylistDialog = false
            }
        )
    }

    if (showShareSheet) {
        ShareSheet(
            viewModel = shareViewModel,
            currentSongs = selectedSongs.toList(),
            onDismiss = { showShareSheet = false },
            onPlayNow = { receivedSong ->
                playerViewModel.play(listOf(receivedSong), 0)
                onNavigateToPlayer()
                showShareSheet = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onAlbumClick: (Album) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onNavigateToPlayer: () -> Unit,
) {
    val allSongs by libraryViewModel.allSongs.collectAsStateWithLifecycle()
    val allAlbums by libraryViewModel.allAlbums.collectAsStateWithLifecycle()
    val allArtists by libraryViewModel.allArtists.collectAsStateWithLifecycle()
    val playlists by libraryViewModel.allPlaylists.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    val trimmed = query.trim()

    // Filtered off the main thread, after a short pause in typing.
    var filteredSongs by remember { mutableStateOf(emptyList<Song>()) }
    var filteredAlbums by remember { mutableStateOf(emptyList<Album>()) }
    var filteredArtists by remember { mutableStateOf(emptyList<Artist>()) }
    LaunchedEffect(trimmed, allSongs, allAlbums, allArtists) {
        if (trimmed.isBlank()) {
            filteredSongs = emptyList(); filteredAlbums = emptyList(); filteredArtists = emptyList()
            return@LaunchedEffect
        }
        delay(120)
        withContext(Dispatchers.Default) {
            val songs = allSongs.filter {
                it.title.contains(trimmed, ignoreCase = true) ||
                    it.artist.contains(trimmed, ignoreCase = true) ||
                    it.album.contains(trimmed, ignoreCase = true)
            }
            val albums = allAlbums.filter {
                it.title.contains(trimmed, ignoreCase = true) || it.artist.contains(trimmed, ignoreCase = true)
            }
            val artists = allArtists.filter { it.name.contains(trimmed, ignoreCase = true) }
            filteredSongs = songs; filteredAlbums = albums; filteredArtists = artists
        }
    }

    val hasResults = filteredSongs.isNotEmpty() || filteredAlbums.isNotEmpty() || filteredArtists.isNotEmpty()

    var selectedSongs by remember { mutableStateOf(setOf<Song>()) }
    val isSelectionMode = selectedSongs.isNotEmpty()
    // Back clears the selection before it leaves the screen.
    BackHandler(enabled = isSelectionMode) { selectedSongs = emptySet() }

    val shareViewModel: ShareViewModel = hiltViewModel()
    var showShareSheet by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = isSelectionMode,
                transitionSpec = {
                    (slideInVertically(tween(700, easing = EmphasizedDecelerate)) { -it / 3 } +
                        fadeIn(tween(700, delayMillis = 70, easing = EmphasizedDecelerate)))
                        .togetherWith(
                            slideOutVertically(tween(560, easing = EmphasizedAccelerate)) { -it / 3 } +
                                fadeOut(tween(280, easing = EmphasizedAccelerate))
                        )
                },
                label = "search_topbar",
            ) { selectionMode ->
                if (selectionMode) {
                    TopAppBar(
                        title = { Text("${selectedSongs.size}") },
                        navigationIcon = {
                            IconButton(onClick = { selectedSongs = emptySet() }) {
                                Icon(Icons.Rounded.Close, "Cancel")
                            }
                        },
                        actions = {
                            IconButton(onClick = { selectedSongs = filteredSongs.toSet() }) {
                                Icon(Icons.Rounded.SelectAll, "Select All")
                            }
                            IconButton(onClick = {
                                shareViewModel.preselectSongs(selectedSongs.toList())
                                showShareSheet = true
                            }) {
                                Icon(Icons.Rounded.Share, "Share")
                            }
                            IconButton(onClick = { showAddToPlaylistDialog = true }) {
                                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to Playlist")
                            }
                            IconButton(onClick = {
                                playerViewModel.addToQueueEnd(filteredSongs.filter { it in selectedSongs })
                                selectedSongs = emptySet()
                            }) {
                                Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Add to Queue")
                            }
                            IconButton(onClick = {
                                playerViewModel.addToQueueNext(filteredSongs.filter { it in selectedSongs })
                                selectedSongs = emptySet()
                            }) {
                                Icon(Icons.Rounded.SkipNext, "Play Next")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    )
                } else {
                    TopAppBar(
                        title = {
                            TextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Search songs, albums, artists…") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                                leadingIcon = {
                                    Icon(Icons.Rounded.Search, contentDescription = null)
                                },
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { query = "" }) {
                                            Icon(Icons.Rounded.Close, contentDescription = "Clear")
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    ) { padding ->
        val searchPhase = when {
            query.isBlank() -> "blank"
            !hasResults -> "empty"
            else -> "results"
        }
        AnimatedContent(
            targetState = searchPhase,
            transitionSpec = {
                fadeIn(tween(340, easing = EmphasizedDecelerate)) togetherWith
                    fadeOut(tween(210, easing = EmphasizedAccelerate))
            },
            label = "search_phase",
        ) { phase ->
            when (phase) {
                "blank" -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.staggeredEntrance(0)) {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = null,
                                modifier = Modifier.size(72.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Search your library",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                "empty" -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.staggeredEntrance(0)) {
                            Icon(
                                Icons.Rounded.SearchOff,
                                contentDescription = null,
                                modifier = Modifier.size(72.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "No results for \"$trimmed\"",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = padding,
                    ) {
                        if (filteredArtists.isNotEmpty()) {
                            item {
                                AppSectionHeader("Artists", Icons.Rounded.Person)
                            }
                            itemsIndexed(filteredArtists, key = { _, a -> a.name }) { index, artist ->
                                ListItem(
                                    headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = {
                                        Text(
                                            "${artist.albumCount} album${if (artist.albumCount != 1) "s" else ""} · " +
                                                    "${artist.songCount} song${if (artist.songCount != 1) "s" else ""}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    leadingContent = {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            modifier = Modifier.size(48.dp),
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Rounded.Person, contentDescription = null)
                                            }
                                        }
                                    },
                                    modifier = Modifier.staggeredEntrance(index).clickable { onArtistClick(artist) },
                                )
                            }
                        }

                        if (filteredAlbums.isNotEmpty()) {
                            item {
                                AppSectionHeader("Albums", Icons.Rounded.Album)
                            }
                            itemsIndexed(filteredAlbums, key = { _, a -> a.id }) { index, album ->
                                ListItem(
                                    headlineContent = { Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = {
                                        Text(
                                            "${album.artist} · ${album.songCount} song${if (album.songCount != 1) "s" else ""}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    leadingContent = {
                                        ArtworkImage(
                                            uri = album.artworkUri,
                                            contentDescription = album.title,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(MaterialTheme.shapes.medium),
                                        )
                                    },
                                    modifier = Modifier.staggeredEntrance(index).clickable { onAlbumClick(album) },
                                )
                            }
                        }

                        if (filteredSongs.isNotEmpty()) {
                            item {
                                AppSectionHeader("Songs", Icons.Rounded.MusicNote)
                            }
                            itemsIndexed(filteredSongs, key = { _, s -> s.id }) { index, song ->
                                SongCard(
                                    song = song,
                                    modifier = Modifier.animateItem().staggeredEntrance(index),
                                    isSelected = selectedSongs.contains(song),
                                    onClick = {
                                        if (isSelectionMode) {
                                            selectedSongs = if (selectedSongs.contains(song)) {
                                                selectedSongs - song
                                            } else {
                                                selectedSongs + song
                                            }
                                        } else {
                                            playerViewModel.play(filteredSongs, index)
                                            onNavigateToPlayer()
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectionMode) {
                                            selectedSongs = setOf(song)
                                        }
                                    },
                                    onLeadingClick = {
                                        if (isSelectionMode) {
                                            selectedSongs = if (selectedSongs.contains(song)) {
                                                selectedSongs - song
                                            } else {
                                                selectedSongs + song
                                            }
                                        }
                                    },
                                    onArtworkMissing = { libraryViewModel.getSongArtworkUrl(song) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddToPlaylistDialog) {
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { showAddToPlaylistDialog = false },
            onPlaylistSelected = { playlist ->
                libraryViewModel.addSongsToPlaylist(playlist.id, selectedSongs.map { it.id })
                selectedSongs = emptySet()
                showAddToPlaylistDialog = false
            },
            onCreatePlaylist = { name ->
                libraryViewModel.createPlaylistWithSongs(name, selectedSongs.map { it.id })
                selectedSongs = emptySet()
                showAddToPlaylistDialog = false
            }
        )
    }

    if (showShareSheet) {
        ShareSheet(
            viewModel = shareViewModel,
            currentSongs = selectedSongs.toList(),
            onDismiss = { showShareSheet = false },
            onPlayNow = { receivedSong ->
                playerViewModel.play(listOf(receivedSong), 0)
                onNavigateToPlayer()
                showShareSheet = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagEditorScreen(
    songId: Long,
    libraryViewModel: LibraryViewModel,
    onBack: () -> Unit
) {
    val allSongs by libraryViewModel.allSongs.collectAsStateWithLifecycle()
    val song = remember(allSongs, songId) { allSongs.find { it.id == songId } }

    LaunchedEffect(song) {
        if (song == null && allSongs.isNotEmpty()) onBack()
    }

    if (song == null) return

    var title by rememberSaveable { mutableStateOf(song.title) }
    var artist by rememberSaveable { mutableStateOf(song.artist) }
    var albumArtist by rememberSaveable { mutableStateOf(song.albumArtist) }
    var album by rememberSaveable { mutableStateOf(song.album) }
    var genre by rememberSaveable { mutableStateOf(song.genre) }
    var year by rememberSaveable { mutableStateOf(if (song.year > 0) song.year.toString() else "") }
    var track by rememberSaveable { mutableStateOf(if (song.trackNumber > 0) song.trackNumber.toString() else "") }
    var disc by rememberSaveable { mutableStateOf(if (song.discNumber > 0) song.discNumber.toString() else "") }

    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Tags") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                actions = {
                    TextButton(
                        onClick = {
                            isSaving = true
                            libraryViewModel.updateSongTags(
                                songId = songId,
                                title = title,
                                artist = artist,
                                albumArtist = albumArtist,
                                album = album,
                                genre = genre,
                                year = year.toIntOrNull() ?: 0,
                                trackNumber = track.toIntOrNull() ?: 0,
                                discNumber = disc.toIntOrNull() ?: 0,
                                onResult = { success ->
                                    if (success) {
                                        onBack()
                                    } else {
                                        isSaving = false
                                        scope.launch { snackbarHostState.showSnackbar("Couldn't save tags — the file couldn't be written") }
                                    }
                                },
                            )
                        },
                        enabled = !isSaving,
                    ) {
                        Text("Save")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = artist,
                onValueChange = { artist = it },
                label = { Text("Artist") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = albumArtist,
                onValueChange = { albumArtist = it },
                label = { Text("Album Artist") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = album,
                onValueChange = { album = it },
                label = { Text("Album") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = genre,
                onValueChange = { genre = it },
                label = { Text("Genre") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = year,
                    onValueChange = { year = it },
                    label = { Text("Year") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = track,
                    onValueChange = { track = it },
                    label = { Text("Track") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = disc,
                    onValueChange = { disc = it },
                    label = { Text("Disc") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                "File Info",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TagInfoRow("Path",        song.path)
                    TagInfoRow("Format",      song.mimeType.substringAfterLast('/').uppercase())
                    TagInfoRow("Duration",    formatDuration(song.duration))
                    TagInfoRow("Bitrate",     if (song.bitrate > 0) "${song.bitrate} kbps" else "—")
                    TagInfoRow("Sample Rate", if (song.sampleRate > 0) "${song.sampleRate} Hz" else "—")
                    TagInfoRow("File Size",   formatFileSize(song.size))
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TagInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.65f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
