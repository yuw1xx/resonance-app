@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.yuwixx.resonance.presentation.screens

import androidx.compose.runtime.saveable.rememberSaveable
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.yuwixx.resonance.data.network.LrclibResponse
import dev.yuwixx.resonance.data.repository.LyricsRepository
import dev.yuwixx.resonance.presentation.components.formatDuration
import dev.yuwixx.resonance.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsEditorScreen(
    playerViewModel: PlayerViewModel,
    lyricsRepository: LyricsRepository,
    onBack: () -> Unit,
) {
    // Pinned to the song the editor was opened for: if playback moves on while editing, Save
    // must still write to this song, not to whatever is playing now.
    val song = remember { playerViewModel.currentSong.value }
    if (song == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val currentSong by playerViewModel.currentSong.collectAsStateWithLifecycle()
    val isCurrent = currentSong?.id == song.id

    var lrcText by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var savedSnackbar by remember { mutableStateOf(false) }
    var showSearchSheet by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Loaded once; after a rotation the (possibly edited) text is kept.
    LaunchedEffect(song.id) {
        if (loaded) return@LaunchedEffect
        lyricsRepository.getRawLyrics(song.id)?.let { lrcText = it }
        loaded = true
    }

    LaunchedEffect(savedSnackbar) {
        if (savedSnackbar) {
            snackbarHostState.showSnackbar("Lyrics saved!")
            savedSnackbar = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Edit Lyrics", style = MaterialTheme.typography.titleMedium)
                        Text(
                            song.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Tap-to-sync: while this song plays, each tap stamps the current time on
                    // the next line that doesn't have a timestamp yet.
                    if (isCurrent && lrcText.isNotBlank()) {
                        IconButton(onClick = {
                            lrcText = stampNextLine(lrcText, playerViewModel.currentPositionMs())
                        }) {
                            Icon(Icons.Rounded.Timer, "Stamp next line with the current time")
                        }
                    }
                    IconButton(onClick = { showSearchSheet = true }) {
                        Icon(Icons.Rounded.Search, "Find lyrics on LRCLIB")
                    }
                    TextButton(
                        onClick = {
                            isSaving = true
                        },
                        enabled = lrcText.isNotBlank() && !isSaving,
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        } else {
                            Text("Save")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Info,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "LRC Format",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "[01:23.45] Lyric line here\n[01:25.00] Next line",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Plain text also supported (no timestamps).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = lrcText,
                onValueChange = { lrcText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = {
                    Text(
                        "[00:00.00] Paste your LRC lyrics here...\n[00:03.50] Or plain text without timestamps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                shape = MaterialTheme.shapes.medium,
                label = { Text("Lyrics") },
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { lrcText = "" },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Clear, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clear")
                }
                Button(
                    onClick = {
                        isSaving = true
                    },
                    modifier = Modifier.weight(1f),
                    enabled = lrcText.isNotBlank() && !isSaving,
                ) {
                    Icon(Icons.Rounded.Save, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save")
                }
            }
        }
    }

    LaunchedEffect(isSaving) {
        if (isSaving && lrcText.isNotBlank()) {
            lyricsRepository.saveManualLyrics(song.id, lrcText)
            if (playerViewModel.currentSong.value?.id == song.id) playerViewModel.reloadLyrics()
            isSaving = false
            savedSnackbar = true
        }
    }

    if (showSearchSheet) {
        LyricsSearchSheet(
            initialTrackName = song.title,
            initialArtistName = song.artist,
            lyricsRepository = lyricsRepository,
            onSelect = { candidate ->
                lrcText = candidate.syncedLyrics?.takeIf { it.isNotBlank() }
                    ?: candidate.plainLyrics.orEmpty()
                showSearchSheet = false
            },
            onDismiss = { showSearchSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsSearchSheet(
    initialTrackName: String,
    initialArtistName: String,
    lyricsRepository: LyricsRepository,
    onSelect: (LrclibResponse) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var trackName by remember { mutableStateOf(initialTrackName) }
    var artistName by remember { mutableStateOf(initialArtistName) }
    var results by remember { mutableStateOf<List<LrclibResponse>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }

    fun runSearch() {
        if (trackName.isBlank()) return
        isSearching = true
        scope.launch {
            results = lyricsRepository.searchLyrics(trackName, artistName)
            isSearching = false
            hasSearched = true
        }
    }

    LaunchedEffect(Unit) { runSearch() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 20.dp),
        ) {
            Text(
                "Find Lyrics on LRCLIB",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "If the auto-fetched lyrics are wrong or out of sync, search for a different submission below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            OutlinedTextField(
                value = trackName,
                onValueChange = { trackName = it },
                label = { Text("Track") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = artistName,
                onValueChange = { artistName = it },
                label = { Text("Artist") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { runSearch() },
                enabled = trackName.isNotBlank() && !isSearching,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Rounded.Search, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Search")
                }
            }
            Spacer(Modifier.height(8.dp))

            when {
                isSearching -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator()
                    }
                }
                hasSearched && results.isEmpty() -> {
                    Text(
                        "No results found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(results, key = { it.id }) { candidate ->
                            LyricsCandidateRow(candidate = candidate, onClick = { onSelect(candidate) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsCandidateRow(candidate: LrclibResponse, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                candidate.trackName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(candidate.artistName)
                    if (!candidate.albumName.isNullOrBlank()) append(" • ${candidate.albumName}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                candidate.duration?.let { LyricsMetaBadge(formatDuration((it * 1000).toLong())) }
                when {
                    candidate.instrumental -> LyricsMetaBadge("Instrumental")
                    !candidate.syncedLyrics.isNullOrBlank() -> LyricsMetaBadge("Synced")
                    !candidate.plainLyrics.isNullOrBlank() -> LyricsMetaBadge("Plain")
                    else -> LyricsMetaBadge("No lyrics")
                }
            }
        }
    }
}

@Composable
private fun LyricsMetaBadge(text: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private val leadingStamp = Regex("""^\s*\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]""")

/** Prefixes the first non-blank, unstamped, non-tag line with [mm:ss.xx] for [positionMs]. */
internal fun stampNextLine(text: String, positionMs: Long): String {
    val lines = text.lines().toMutableList()
    val index = lines.indexOfFirst { line ->
        line.isNotBlank() && !leadingStamp.containsMatchIn(line) && !line.trimStart().startsWith("[")
    }
    if (index < 0) return text
    val stamp = String.format(Locale.ROOT, "[%02d:%02d.%02d]", positionMs / 60_000, (positionMs / 1000) % 60, (positionMs / 10) % 100)
    lines[index] = stamp + lines[index].trimStart()
    return lines.joinToString("\n")
}
