// Song detail page reached by tapping a track's title in the player: artwork/title/artist
// header, a metadata info card, a per-song listening-stats card, and a static lyrics section.
package dev.yuwixx.resonance.presentation.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import android.text.format.Formatter
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.repository.LyricsResult
import dev.yuwixx.resonance.presentation.components.AnimatedStateSwap
import dev.yuwixx.resonance.presentation.components.ArtworkImage
import dev.yuwixx.resonance.presentation.components.EmphasizedDecelerate
import dev.yuwixx.resonance.presentation.components.MultiArtistText
import dev.yuwixx.resonance.presentation.components.SectionCard
import dev.yuwixx.resonance.presentation.components.audioFormatLabel
import dev.yuwixx.resonance.presentation.components.formatDuration
import dev.yuwixx.resonance.presentation.components.formatListenDuration
import dev.yuwixx.resonance.presentation.components.staggeredEntrance
import dev.yuwixx.resonance.presentation.viewmodel.SongDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongDetailScreen(
    songId: Long,
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit = {},
    viewModel: SongDetailViewModel = hiltViewModel(),
) {
    val song by viewModel.song.collectAsStateWithLifecycle()
    val lyricsResult by viewModel.lyricsResult.collectAsStateWithLifecycle()
    val playCount by viewModel.playCount.collectAsStateWithLifecycle()
    val totalListenedMs by viewModel.totalListenedMs.collectAsStateWithLifecycle()

    LaunchedEffect(songId) { viewModel.load(songId) }

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
        Crossfade(
            targetState = song,
            animationSpec = tween(340, easing = EmphasizedDecelerate),
            label = "song_detail_loading",
        ) { currentSong ->
            if (currentSong == null) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding(),
                        bottom = 32.dp,
                        start = 20.dp,
                        end = 20.dp,
                    ),
                ) {
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).staggeredEntrance(0),
                        ) {
                            ArtworkImage(
                                uri = currentSong.artworkUri,
                                contentDescription = currentSong.album,
                                modifier = Modifier.size(200.dp),
                                cornerRadius = 20.dp,
                                onArtworkMissing = { viewModel.getSongArtworkUrl(currentSong) },
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                currentSong.title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            MultiArtistText(
                                artists = currentSong.artists,
                                onArtistClick = onArtistClick,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    item {
                        Column(modifier = Modifier.fillMaxWidth().staggeredEntrance(1)) {
                            SongInfoCard(currentSong)
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                    item {
                        Column(modifier = Modifier.fillMaxWidth().staggeredEntrance(2)) {
                            ListeningStatsCard(playCount = playCount, totalListenedMs = totalListenedMs)
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                    item {
                        Column(modifier = Modifier.fillMaxWidth().staggeredEntrance(3)) {
                            LyricsCard(lyricsResult)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongInfoCard(song: Song) {
    val context = LocalContext.current
    SectionCard(icon = Icons.Rounded.Info, title = "Info") {
        InfoRow("Album", song.album.ifBlank { "Unknown" })
        InfoRow("Genre", song.genre.ifBlank { "Unknown" })
        if (song.year > 0) InfoRow("Year", song.year.toString())
        InfoRow("Duration", formatDuration(song.duration))
        val fmt = audioFormatLabel(song.mimeType)
        InfoRow("Format", if (song.bitrate > 0) "$fmt · ${song.bitrate}kbps" else fmt)
        if (song.size > 0) InfoRow("File Size", Formatter.formatShortFileSize(context, song.size))
    }
}

@Composable
private fun ListeningStatsCard(playCount: Int, totalListenedMs: Long) {
    SectionCard(icon = Icons.Rounded.QueryStats, title = "Listening Stats") {
        InfoRow("Plays", playCount.toString())
        InfoRow("Time Listened", formatListenDuration(totalListenedMs))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LyricsCard(lyricsResult: LyricsResult) {
    SectionCard(icon = Icons.Rounded.MusicNote, title = "Lyrics") {
        AnimatedStateSwap(
            targetState = lyricsResult,
            modifier = Modifier.fillMaxWidth(),
            label = "song_detail_lyrics",
        ) { result ->
            when (result) {
                is LyricsResult.Loading -> {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is LyricsResult.Synced -> {
                    Column {
                        result.lines.forEach { line ->
                            if (line.text.isNotBlank()) {
                                Text(
                                    line.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
                is LyricsResult.Plain -> {
                    Column {
                        result.lines.forEach { line ->
                            if (line.isNotBlank()) {
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
                is LyricsResult.Instrumental -> {
                    Text(
                        "Instrumental",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                is LyricsResult.NotFound -> {
                    Text(
                        "No lyrics found for this song.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is LyricsResult.Error -> {
                    Text(
                        "Couldn't load lyrics: ${result.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
