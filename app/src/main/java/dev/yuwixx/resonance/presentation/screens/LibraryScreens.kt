// All library browsing screens: Songs, Albums, Artists, Playlists, LikedSongs, Search,
// AlbumDetail, ArtistDetail, PlaylistDetail, Queue, and the TagEditor screen.
package dev.yuwixx.resonance.presentation.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.yuwixx.resonance.data.model.*
import dev.yuwixx.resonance.data.model.RepeatMode as AppRepeatMode
import dev.yuwixx.resonance.data.repository.NavidromeSyncState
import dev.yuwixx.resonance.presentation.components.*
import dev.yuwixx.resonance.presentation.components.LazyColumnWithScrollbar
import dev.yuwixx.resonance.presentation.components.LazyGridWithScrollbar
import dev.yuwixx.resonance.presentation.components.LocalAppearanceConfig
import dev.yuwixx.resonance.presentation.viewmodel.LibraryViewModel
import dev.yuwixx.resonance.presentation.viewmodel.PlayerViewModel
import dev.yuwixx.resonance.presentation.viewmodel.ShareViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.presentation.navigation.Screen
import dev.yuwixx.resonance.ui.theme.PresetColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Emphasized = CubicBezierEasing(0.2f, 0.0f, 0f, 1.0f)
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SongsScreen(
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onSearchClick: () -> Unit,
    onNavigateToPlayer: () -> Unit,
    onNavigateToTagEditor: (Long) -> Unit,
) {
    val rawSongs by libraryViewModel.allSongs.collectAsState()
    val defaultSongsSort by libraryViewModel.prefs.defaultSongsSort.collectAsState(initial = "TITLE")
    val songs = remember(rawSongs, defaultSongsSort) {
        when (defaultSongsSort) {
            "ARTIST" -> rawSongs.sortedBy { it.displayArtist.lowercase() }
            "ALBUM"  -> rawSongs.sortedBy { it.album.lowercase() }
            "ADDED"  -> rawSongs.sortedByDescending { it.dateAdded }
            else     -> rawSongs.sortedBy { it.title.lowercase() }
        }
    }
    val currentSong by playerViewModel.currentSong.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val isSyncing by libraryViewModel.isSyncing.collectAsState()
    val navSyncState by libraryViewModel.navSyncState.collectAsState()
    val playlists by libraryViewModel.allPlaylists.collectAsState()

    var selectedSongs by remember { mutableStateOf(setOf<Song>()) }
    val isSelectionMode = selectedSongs.isNotEmpty()

    val shareViewModel: ShareViewModel = hiltViewModel()
    var showShareSheet by remember { mutableStateOf(false) }

    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var songForInfoSheet by remember { mutableStateOf<Song?>(null) }
    var showInfoSheetPlaylistPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text("${selectedSongs.size} selected") },
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
                            if (selectedSongs.size == 1) {
                                shareViewModel.preselectSong(selectedSongs.first())
                            } else {
                                shareViewModel.preselectSong(null)
                            }
                            showShareSheet = true
                        }) {
                            Icon(Icons.Rounded.Share, "Share")
                        }
                        IconButton(onClick = { showAddToPlaylistDialog = true }) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to Playlist")
                        }
                        IconButton(onClick = {
                            selectedSongs.forEach { playerViewModel.addToQueueEnd(it) }
                            selectedSongs = emptySet()
                        }) {
                            Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Add to Queue")
                        }
                        IconButton(onClick = {
                            selectedSongs.forEach { playerViewModel.addToQueueNext(it) }
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
                LargeTopAppBar(
                    title = { Text("Songs", fontWeight = FontWeight.Bold) },
                    actions = {
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
                    colors = TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (navSyncState is NavidromeSyncState.Syncing) {
                NavidromeSyncingView(navSyncState = navSyncState)
            } else if (songs.isEmpty()) {
                EmptyLibraryView(onScan = { libraryViewModel.syncLibrary() })
            } else {
                val listState = rememberLazyListState()
                LazyColumnWithScrollbar(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 80.dp),
                    ) {
                    items(songs, key = { it.id }) { song ->
                        SongCard(
                            song = song,
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
                                    playerViewModel.play(songs, songs.indexOf(song))
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
                            }
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
                libraryViewModel.createPlaylist(name)
            }
        )
    }

    if (showShareSheet) {
        ShareSheet(
            viewModel   = shareViewModel,
            currentSong = selectedSongs.firstOrNull(),
            onDismiss   = { showShareSheet = false },
            onPlayNow   = { receivedSong ->
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
            onEditTags = { onNavigateToTagEditor(song.id) }
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

    val albumsDone     = syncing?.albumsDone     ?: 0
    val songsDone      = syncing?.songsDone      ?: 0
    val estimatedTotal = syncing?.estimatedTotal ?: 0

    val progress = if (estimatedTotal > 0 && songsDone > 0)
        (songsDone.toFloat() / estimatedTotal).coerceIn(0f, 0.99f) else null

    val etaText: String? = if (elapsedSeconds > 5 && songsDone > 0 && estimatedTotal > songsDone) {
        val etaSecs = (elapsedSeconds * (estimatedTotal - songsDone).toFloat() / songsDone).toLong()
        when {
            etaSecs < 60   -> "Less than a minute remaining"
            etaSecs < 3600 -> "~${etaSecs / 60} min remaining"
            else           -> "~${etaSecs / 3600}h ${(etaSecs % 3600) / 60}m remaining"
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
                animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
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
        modifier = Modifier.fillMaxSize(),
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
    val albums by libraryViewModel.allAlbums.collectAsState()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Albums", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Rounded.Search, "Search")
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        }
    ) { padding ->
        if (albums.isEmpty()) {
            EmptyLibraryView(onScan = { libraryViewModel.syncLibrary() })
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
                    start = 12.dp, end = 12.dp, top = padding.calculateTopPadding(), bottom = 80.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(albums, key = { it.id }) { album ->
                    AlbumCard(album = album, onClick = { onAlbumClick(album) })
                }
            }
            }
        }
    }
}

@Composable
fun AlbumCard(album: Album, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
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
    val artists by libraryViewModel.allArtists.collectAsState()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Artists", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Rounded.Search, "Search")
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        }
    ) { padding ->
        if (artists.isEmpty()) {
            EmptyLibraryView(onScan = { libraryViewModel.syncLibrary() })
        } else {
            val fetchImages by libraryViewModel.prefs.fetchArtistImages.collectAsState(initial = true)
            val listState = rememberLazyListState()
            LazyColumnWithScrollbar(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(), bottom = 80.dp
                ),
            ) {
                items(artists, key = { it.name }) { artist ->
                    var imageUrl by remember(artist.name) { mutableStateOf<String?>(null) }
                    LaunchedEffect(artist.name, fetchImages) {
                        imageUrl = if (fetchImages) libraryViewModel.getArtistArtworkUrl(artist.name) else null
                    }
                    ListItem(
                        modifier = Modifier.clickable { onArtistClick(artist) },
                        headlineContent = { Text(artist.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text("${artist.songCount} songs · ${artist.albumCount} albums") },
                        leadingContent = {
                            if (fetchImages && imageUrl != null) {
                                coil.compose.AsyncImage(
                                    model = imageUrl,
                                    contentDescription = artist.name,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
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
    val playlists by libraryViewModel.allPlaylists.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Playlists", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Rounded.Add, "New Playlist")
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        }
    ) { padding ->
        if (playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                }
            }
        } else {
            val playlistCols = LocalAppearanceConfig.current.playlistGridColumns
            LazyVerticalGrid(
                columns = GridCells.Fixed(playlistCols),
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp, top = padding.calculateTopPadding(), bottom = 80.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistCard(playlist = playlist, onClick = { onPlaylistClick(playlist) })
                }
            }
        }
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
}

@Composable
fun PlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
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
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
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
            text = "${playlist.songCount} songs",
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
) {
    val albums by libraryViewModel.allAlbums.collectAsState()
    val allSongs by libraryViewModel.allSongs.collectAsState()
    val playlists by libraryViewModel.allPlaylists.collectAsState()

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
        album?.let {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = 80.dp
                ),
            ) {
                item {
                    AlbumHeader(album = it, songs = albumSongs) {
                        playerViewModel.play(albumSongs, 0)
                        onNavigateToPlayer()
                    }
                }
                itemsIndexed(albumSongs, key = { _, s -> s.id }) { index, song ->
                    SongCard(
                        song = song,
                        onClick = {
                            playerViewModel.play(albumSongs, index)
                            onNavigateToPlayer()
                        },
                        onLongClick = { songForInfoSheet = song },
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumHeader(album: Album, songs: List<Song>, onPlayClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtworkImage(
            uri = album.artworkUri,
            contentDescription = album.title,
            modifier = Modifier
                .size(240.dp)
                .shadow(16.dp, RoundedCornerShape(24.dp)),
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
        )
        Text(
            text = "${songs.size} songs · ${album.year.takeIf { it > 0 } ?: ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onPlayClick,
            modifier = Modifier.fillMaxWidth(0.6f),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Rounded.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("Play")
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
    val artists by libraryViewModel.allArtists.collectAsState()
    val albums by libraryViewModel.allAlbums.collectAsState()
    val playlists by libraryViewModel.allPlaylists.collectAsState()

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
        artist?.let {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 80.dp),
            ) {
                item {
                    ArtistHeader(artist = it, songs = artistSongs, libraryViewModel = libraryViewModel) {
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
                            items(artistAlbums, key = { it.id }) { album ->
                                Column(
                                    modifier = Modifier
                                        .width(140.dp)
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
                        onClick = {
                            playerViewModel.play(artistSongs, index)
                            onNavigateToPlayer()
                        },
                        onLongClick = { songForInfoSheet = song },
                    )
                }
            }
        }
    }
}

@Composable
fun ArtistHeader(artist: Artist, songs: List<Song>, libraryViewModel: LibraryViewModel, onPlayClick: () -> Unit) {
    val fetchImages by libraryViewModel.prefs.fetchArtistImages.collectAsState(initial = true)
    var imageUrl by remember(artist.name) { mutableStateOf<String?>(null) }

    LaunchedEffect(artist.name, fetchImages) {
        if (fetchImages) {
            imageUrl = libraryViewModel.getArtistArtworkUrl(artist.name)
        } else {
            imageUrl = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (fetchImages && imageUrl != null) {
            coil.compose.AsyncImage(
                model = imageUrl,
                contentDescription = artist.name,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .shadow(8.dp, CircleShape),
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
            shape = RoundedCornerShape(16.dp),
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
    val playlists by libraryViewModel.allPlaylists.collectAsState()
    val allSongs by libraryViewModel.allSongs.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val playlist = remember(playlists, playlistId) { playlists.find { it.id == playlistId } }

    var sortOrder by remember { mutableStateOf(SortOrder.NATURAL) }
    val playlistSongs: List<Song> = remember(allSongs, playlist, sortOrder) {
        val base = playlist?.songs ?: emptyList()
        when (sortOrder) {
            SortOrder.TITLE_ASC          -> base.sortedBy { it.title.lowercase() }
            SortOrder.TITLE_DESC         -> base.sortedByDescending { it.title.lowercase() }
            SortOrder.ARTIST_ASC         -> base.sortedBy { it.artist.lowercase() }
            SortOrder.ARTIST_DESC        -> base.sortedByDescending { it.artist.lowercase() }
            SortOrder.ALBUM_ASC          -> base.sortedBy { it.album.lowercase() }
            SortOrder.ALBUM_DESC         -> base.sortedByDescending { it.album.lowercase() }
            SortOrder.DURATION_ASC       -> base.sortedBy { it.duration }
            SortOrder.DURATION_DESC      -> base.sortedByDescending { it.duration }
            SortOrder.DATE_ADDED_ASC     -> base.sortedBy { it.dateAdded }
            SortOrder.DATE_ADDED_DESC    -> base.sortedByDescending { it.dateAdded }
            SortOrder.LISTEN_COUNT_DESC  -> base.sortedByDescending { it.listenCount }
            else                         -> base
        }
    }

    var showDeleteDialog  by remember { mutableStateOf(false) }
    var showRenameDialog  by remember { mutableStateOf(false) }
    var showSortDialog    by remember { mutableStateOf(false) }
    var showOverflowMenu  by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { libraryViewModel.updatePlaylistArtwork(playlistId, it) } }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        if (uri != null && playlist != null) {
            try {
                val m3u = libraryViewModel.exportPlaylistAsM3U(playlist)
                context.contentResolver.openOutputStream(uri)?.use { it.write(m3u.toByteArray()) }
                scope.launch { snackbarHostState.showSnackbar("Playlist exported successfully") }
            } catch (e: Exception) {
                scope.launch { snackbarHostState.showSnackbar("Export failed: ${e.message}") }
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
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                onClick = { showOverflowMenu = false; showRenameDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Change Cover") },
                                leadingIcon = { Icon(Icons.Rounded.Image, null) },
                                onClick = { showOverflowMenu = false; imagePicker.launch("image/*") }
                            )
                            DropdownMenuItem(
                                text = { Text("Sort by…") },
                                leadingIcon = { Icon(Icons.Rounded.Sort, null) },
                                onClick = { showOverflowMenu = false; showSortDialog = true }
                            )
                            DropdownMenuItem(
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
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 80.dp),
            ) {
                item {
                    PlaylistHeader(
                        playlist = it,
                        songs = playlistSongs,
                        onChangeCover = { imagePicker.launch("image/*") },
                        onPlayClick = {
                            if (playlistSongs.isNotEmpty()) {
                                playerViewModel.play(playlistSongs, 0)
                                onNavigateToPlayer()
                            }
                        }
                    )
                }
                itemsIndexed(playlistSongs, key = { index, s -> "${index}_${s.id}" }) { index, song ->
                    SongCard(
                        song = song,
                        onClick = {
                            playerViewModel.play(playlistSongs, index)
                            onNavigateToPlayer()
                        },
                        trailingContent = {
                            IconButton(onClick = {
                                libraryViewModel.removeSongsFromPlaylist(playlistId, listOf(song.id))
                            }) {
                                Icon(Icons.Rounded.RemoveCircleOutline, "Remove")
                            }
                        }
                    )
                }
            }
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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(RoundedCornerShape(24.dp))
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
                    shape = RoundedCornerShape(24.dp),
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
        Button(
            onClick = onPlayClick,
            modifier = Modifier.fillMaxWidth(0.6f),
            shape = RoundedCornerShape(16.dp),
            enabled = songs.isNotEmpty()
        ) {
            Icon(Icons.Rounded.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("Play All")
        }
    }
}

@Composable
fun RenamePlaylistDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun QueueScreen(
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    val queue by playerViewModel.queue.collectAsState()
    val currentSongIndex by playerViewModel.currentQueueIndex.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playing Queue", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { playerViewModel.clearQueue() }) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        }
    ) { padding ->
        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Queue is empty", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
            ) {
                itemsIndexed(queue, key = { index, s -> "${index}_${s.id}" }) { index, song ->
                    val isCurrent = index == currentSongIndex
                    SongCard(
                        song = song,
                        isPlaying = isCurrent,
                        onClick = { playerViewModel.play(queue, index) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { playerViewModel.removeFromQueue(index) }) {
                                    Icon(Icons.Rounded.Close, "Remove")
                                }
                                Icon(
                                    Icons.Rounded.DragHandle,
                                    "Reorder",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    )
                }
            }
        }
    }
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
            LazyColumn {
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
    var name by remember { mutableStateOf("") }
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
    onEditTags: () -> Unit
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
                    items(playlists) { playlist ->
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LikedSongsScreen(
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel,
    onBack: () -> Unit,
    onNavigateToPlayer: () -> Unit,
) {
    val allSongs by libraryViewModel.allSongs.collectAsState()
    val likedIds by playerViewModel.likedSongIds.collectAsState(initial = emptyList())

    val likedSongs: List<Song> = remember(allSongs, likedIds) {
        val songMap = allSongs.associateBy { it.id }
        likedIds.mapNotNull { songMap[it] }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Liked Songs", fontWeight = FontWeight.Bold)
                        if (likedSongs.isNotEmpty()) {
                            Text(
                                "${likedSongs.size} songs",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (likedSongs.isNotEmpty()) {
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
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        if (likedSongs.isEmpty()) {
            Box(
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
                        animationSpec = infiniteRepeatable(tween(800), androidx.compose.animation.core.RepeatMode.Reverse),
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
                itemsIndexed(likedSongs, key = { _, s -> s.id }) { index, song ->
                    val animatedAlpha by animateFloatAsState(
                        targetValue = 1f,
                        animationSpec = tween(500, delayMillis = (index * 40).coerceAtMost(400), easing = EmphasizedDecelerate),
                        label = "item_alpha_$index",
                    )
                    val animatedScale by animateFloatAsState(
                        targetValue = 1f,
                        animationSpec = tween(500, delayMillis = (index * 40).coerceAtMost(400), easing = EmphasizedDecelerate),
                        label = "item_scale_$index",
                    )
                    SongCard(
                        song = song,
                        modifier = Modifier
                            .animateItem()
                            .graphicsLayer {
                                alpha = animatedAlpha
                                scaleX = animatedScale
                                scaleY = animatedScale
                            },
                        onClick = {
                            playerViewModel.play(likedSongs, index)
                            onNavigateToPlayer()
                        },
                    )
                }
            }
        }
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
    val allSongs by libraryViewModel.allSongs.collectAsState()
    val allAlbums by libraryViewModel.allAlbums.collectAsState()
    val allArtists by libraryViewModel.allArtists.collectAsState()

    var query by remember { mutableStateOf("") }
    val trimmed = query.trim()

    val filteredSongs = remember(trimmed, allSongs) {
        if (trimmed.isBlank()) emptyList()
        else allSongs.filter {
            it.title.contains(trimmed, ignoreCase = true) ||
                    it.artist.contains(trimmed, ignoreCase = true) ||
                    it.album.contains(trimmed, ignoreCase = true)
        }
    }
    val filteredAlbums = remember(trimmed, allAlbums) {
        if (trimmed.isBlank()) emptyList()
        else allAlbums.filter {
            it.title.contains(trimmed, ignoreCase = true) ||
                    it.artist.contains(trimmed, ignoreCase = true)
        }
    }
    val filteredArtists = remember(trimmed, allArtists) {
        if (trimmed.isBlank()) emptyList()
        else allArtists.filter { it.name.contains(trimmed, ignoreCase = true) }
    }

    val hasResults = filteredSongs.isNotEmpty() || filteredAlbums.isNotEmpty() || filteredArtists.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search songs, albums, artists…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
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
    ) { padding ->
        when {
            query.isBlank() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

            !hasResults -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                            Text(
                                "Artists",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(filteredArtists, key = { it.name }) { artist ->
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
                                modifier = Modifier.clickable { onArtistClick(artist) },
                            )
                        }
                    }

                    if (filteredAlbums.isNotEmpty()) {
                        item {
                            Text(
                                "Albums",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(filteredAlbums, key = { it.id }) { album ->
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
                                            .clip(RoundedCornerShape(6.dp)),
                                    )
                                },
                                modifier = Modifier.clickable { onAlbumClick(album) },
                            )
                        }
                    }

                    if (filteredSongs.isNotEmpty()) {
                        item {
                            Text(
                                "Songs",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        itemsIndexed(filteredSongs, key = { _, s -> s.id }) { index, song ->
                            SongCard(
                                song = song,
                                onClick = {
                                    playerViewModel.play(filteredSongs, index)
                                    onNavigateToPlayer()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagEditorScreen(
    songId: Long,
    libraryViewModel: LibraryViewModel,
    onBack: () -> Unit
) {
    val allSongs by libraryViewModel.allSongs.collectAsState()
    val song = remember(allSongs, songId) { allSongs.find { it.id == songId } }

    LaunchedEffect(song) {
        if (song == null && allSongs.isNotEmpty()) onBack()
    }

    if (song == null) return

    var title       by remember { mutableStateOf(song.title) }
    var artist      by remember { mutableStateOf(song.artist) }
    var albumArtist by remember { mutableStateOf(song.albumArtist) }
    var album       by remember { mutableStateOf(song.album) }
    var genre       by remember { mutableStateOf(song.genre) }
    var year        by remember { mutableStateOf(if (song.year > 0) song.year.toString() else "") }
    var track       by remember { mutableStateOf(if (song.trackNumber > 0) song.trackNumber.toString() else "") }
    var disc        by remember { mutableStateOf(if (song.discNumber > 0) song.discNumber.toString() else "") }

    var isSaving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Tags") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
                },
                actions = {
                    TextButton(
                        onClick = {
                            isSaving = true
                            libraryViewModel.updateSongTags(
                                songId      = songId,
                                title       = title,
                                artist      = artist,
                                albumArtist = albumArtist,
                                album       = album,
                                genre       = genre,
                                year        = year.toIntOrNull() ?: 0,
                                trackNumber = track.toIntOrNull() ?: 0,
                                discNumber  = disc.toIntOrNull() ?: 0,
                            )
                            onBack()
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
        }
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

            OutlinedTextField(value = title,       onValueChange = { title = it },       label = { Text("Title") },        modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = artist,      onValueChange = { artist = it },      label = { Text("Artist") },       modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = albumArtist, onValueChange = { albumArtist = it }, label = { Text("Album Artist") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = album,       onValueChange = { album = it },       label = { Text("Album") },        modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = genre,       onValueChange = { genre = it },       label = { Text("Genre") },        modifier = Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = year,  onValueChange = { year = it },  label = { Text("Year") },     modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = track, onValueChange = { track = it }, label = { Text("Track") },    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = disc,  onValueChange = { disc = it },  label = { Text("Disc") },     modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
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
                    TagInfoRow("Duration",    formatTagDuration(song.duration))
                    TagInfoRow("Bitrate",     if (song.bitrate > 0) "${song.bitrate} kbps" else "—")
                    TagInfoRow("Sample Rate", if (song.sampleRate > 0) "${song.sampleRate} Hz" else "—")
                    TagInfoRow("File Size",   formatTagFileSize(song.size))
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

private fun formatTagDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatTagFileSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024     -> "%.1f KB".format(bytes / 1_024.0)
    else               -> "$bytes B"
}