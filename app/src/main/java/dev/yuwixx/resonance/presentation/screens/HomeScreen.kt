package dev.yuwixx.resonance.presentation.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.yuwixx.resonance.data.model.MixType
import dev.yuwixx.resonance.data.model.Playlist
import dev.yuwixx.resonance.presentation.components.*
import dev.yuwixx.resonance.presentation.navigation.Screen
import dev.yuwixx.resonance.presentation.viewmodel.LibraryViewModel
import dev.yuwixx.resonance.presentation.viewmodel.PlayerViewModel

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel,
    onNavigateTo: (Screen) -> Unit,
) {
    val currentSong by playerViewModel.currentSong.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val mostPlayed by libraryViewModel.mostPlayed.collectAsState()
    val autoMixes by libraryViewModel.autoMixes.collectAsState()
    val allSongs by libraryViewModel.allSongs.collectAsState()
    val showMostPlayed by libraryViewModel.prefs.homeShowMostPlayed.collectAsState(initial = true)
    val showRecentlyAdded by libraryViewModel.prefs.homeShowRecentlyAdded.collectAsState(initial = true)
    val recentlyAddedCount by libraryViewModel.prefs.homeRecentlyAddedCount.collectAsState(initial = 20)
    val recentlyAdded = remember(allSongs, recentlyAddedCount) {
        allSongs.sortedByDescending { it.dateAdded }.take(recentlyAddedCount)
    }

    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Resonance",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    IconButton(onClick = { onNavigateTo(Screen.Statistics) }) {
                        Icon(Icons.AutoMirrored.Rounded.TrendingUp, "Listening Stats")
                    }
                    CastButton(modifier = Modifier.size(48.dp))
                    IconButton(onClick = { onNavigateTo(Screen.Search) }) {
                        Icon(Icons.Rounded.Search, "Search")
                    }
                    IconButton(onClick = { onNavigateTo(Screen.Settings) }) {
                        Icon(Icons.Rounded.Settings, "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                AnimatedVisibility(
                    visible = currentSong != null,
                    enter = fadeIn(tween(400, easing = EmphasizedDecelerate)) +
                            expandVertically(tween(400, easing = EmphasizedDecelerate)),
                    exit = fadeOut(tween(300, easing = EmphasizedAccelerate)) +
                           shrinkVertically(tween(300, easing = EmphasizedAccelerate)),
                ) {
                    currentSong?.let { song ->
                        ResumeCard(
                            song = song,
                            isPlaying = isPlaying,
                            onClick = { onNavigateTo(Screen.Player) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // Your Mixes — weekly auto-generated playlists (empty when location is "PLAYLISTS")
            if (autoMixes.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Your Mixes",
                        icon = Icons.Rounded.AutoAwesome,
                        onRefresh = { libraryViewModel.refreshMixes() },
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(autoMixes, key = { it.id }) { mix ->
                            MixCard(
                                mix = mix,
                                onClick = { playerViewModel.play(mix.songs, 0) },
                            )
                        }
                    }
                }
            }

            // Most Played
            if (showMostPlayed && mostPlayed.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Most Played",
                        icon = Icons.AutoMirrored.Rounded.TrendingUp,
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(mostPlayed.take(10), key = { it.id }) { song ->
                            CompactArtworkCard(
                                title = song.title,
                                subtitle = song.displayArtist,
                                artworkUri = song.artworkUri,
                                onClick = { playerViewModel.play(mostPlayed, mostPlayed.indexOf(song)) },
                            )
                        }
                    }
                }
            }

            // Recently Added
            if (showRecentlyAdded && recentlyAdded.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Recently Added",
                        icon = Icons.Rounded.FiberNew,
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(recentlyAdded, key = { it.id }) { song ->
                            CompactArtworkCard(
                                title = song.title,
                                subtitle = song.displayArtist,
                                artworkUri = song.artworkUri,
                                onClick = { playerViewModel.play(recentlyAdded, recentlyAdded.indexOf(song)) },
                            )
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun ResumeCard(
    song: dev.yuwixx.resonance.data.model.Song,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .preferredFrameRateSafe(120f),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(
                uri = song.artworkUri,
                contentDescription = song.album,
                modifier = Modifier.size(64.dp),
                cornerRadius = 16.dp,
                isAnimating = isPlaying,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Now Playing",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.displayArtist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isPlaying) {
                PlayingBarsIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .padding(end = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(Icons.Rounded.ChevronRight, null)
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector,
    isLoading: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else if (onRefresh != null) {
            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "Refresh mixes",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MixCard(
    mix: Playlist,
    onClick: () -> Unit,
) {
    val description = when (mix.mixType) {
        MixType.TOP_ARTIST     -> "Your top artist this week"
        MixType.TOP_GENRE      -> "Your most-played genre"
        MixType.ERA            -> "Songs from your favorite decade"
        MixType.FAVORITES      -> "Your most played tracks of all time"
        MixType.RECENTLY_LOVED -> "What you've been playing this week"
        null                   -> ""
    }
    Card(
        onClick = onClick,
        modifier = Modifier.width(200.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            if (mix.mixType != null) {
                MixArtwork(
                    mixType = mix.mixType,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    cornerRadius = 12.dp,
                )
            }
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    mix.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${mix.songCount} songs",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun CompactArtworkCard(
    title: String,
    subtitle: String,
    artworkUri: Any?,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(160.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column {
            ArtworkImage(
                uri = artworkUri,
                contentDescription = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(
                        RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                    ),
                cornerRadius = 0.dp,
            )
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
