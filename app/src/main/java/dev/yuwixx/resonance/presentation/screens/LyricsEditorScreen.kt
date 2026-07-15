package dev.yuwixx.resonance.presentation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.yuwixx.resonance.data.repository.LyricsRepository
import dev.yuwixx.resonance.presentation.viewmodel.PlayerViewModel
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsEditorScreen(
    playerViewModel: PlayerViewModel,
    lyricsRepository: LyricsRepository,
    onBack: () -> Unit,
) {
    val currentSong by playerViewModel.currentSong.collectAsState()
    val song = currentSong ?: run { onBack(); return }

    var lrcText by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var savedSnackbar by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(song.id) {
        val raw = lyricsRepository.getRawLyrics(song.id)
        if (raw != null) lrcText = raw
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
            playerViewModel.reloadLyrics()
            isSaving = false
            savedSnackbar = true
        }
    }
}
