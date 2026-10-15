// Review-before-write UI for the metadata enrichment scan: Idle -> Scanning -> Reviewing ->
// Done. TagEditor.writeTags is only ever reached from the "Apply Selected" button in Reviewing.
package dev.yuwixx.resonance.presentation.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.yuwixx.resonance.domain.usecase.MetadataEnrichmentScanner
import dev.yuwixx.resonance.presentation.components.EmphasizedAccelerate
import dev.yuwixx.resonance.presentation.components.EmphasizedDecelerate
import dev.yuwixx.resonance.presentation.components.formatListenDuration
import dev.yuwixx.resonance.presentation.components.staggeredEntrance
import dev.yuwixx.resonance.presentation.viewmodel.EnrichmentUiState
import dev.yuwixx.resonance.presentation.viewmodel.MetadataEnrichmentViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataEnrichmentScreen(
    onBack: () -> Unit,
    viewModel: MetadataEnrichmentViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val checkedIndices by viewModel.checkedIndices.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Metadata Enrichment") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Keyed on the state's KClass, not the state instance itself — Scanning's index/
            // total/foundSoFar tick many times per second while remaining "the same" state, and
            // keying on the full instance would retrigger this enter/exit transition on every
            // tick instead of just on genuine Idle/Scanning/Reviewing/Applying/Done transitions.
            // The content lambda still reads the live `uiState` (not a stale snapshot), so it
            // recomposes normally in between.
            AnimatedContent(
                targetState = uiState::class,
                transitionSpec = {
                    (fadeIn(tween(340, easing = EmphasizedDecelerate)) +
                        scaleIn(tween(340, easing = EmphasizedDecelerate), initialScale = 0.96f))
                        .togetherWith(fadeOut(tween(220, easing = EmphasizedAccelerate)))
                },
                label = "enrichment_state",
            ) {
                when (val state = uiState) {
                    is EnrichmentUiState.Idle -> IdleContent(onScan = viewModel::startScan)
                    is EnrichmentUiState.Scanning -> ScanningContent(state, onCancel = viewModel::cancelScan)
                    is EnrichmentUiState.Reviewing -> ReviewingContent(
                        changes = state.changes,
                        checkedIndices = checkedIndices,
                        onToggle = viewModel::toggleChange,
                        onSelectAllHighConfidence = viewModel::selectAllHighConfidence,
                        onApply = viewModel::applySelected,
                    )
                    is EnrichmentUiState.Applying -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is EnrichmentUiState.Done -> DoneContent(state, onScanAgain = viewModel::reset)
                }
            }
        }
    }
}

@Composable
private fun IdleContent(onScan: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.AutoFixHigh, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Scan Library for Metadata Fixes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Checks each local song against MusicBrainz and Last.fm for a corrected release year " +
                "and a missing genre. This is rate-limited to be respectful of those free services, so " +
                "a large library can take a while — you'll see every proposed change before anything " +
                "is written to your files.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onScan) { Text("Scan Library") }
    }
}

@Composable
private fun ScanningContent(state: EnrichmentUiState.Scanning, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val progress = if (state.total > 0) state.index / state.total.toFloat() else 0f
        val animatedProgress by animateFloatAsState(
            targetValue = progress,
            animationSpec = tween(300, easing = EmphasizedDecelerate),
            label = "enrichment_scan_progress",
        )
        LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Text("${state.index} of ${state.total} songs checked", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "${state.foundSoFar} change${if (state.foundSoFar == 1) "" else "s"} found so far",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.estimatedRemainingMs > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "About ${formatListenDuration(state.estimatedRemainingMs)} remaining",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun ReviewingContent(
    changes: List<MetadataEnrichmentScanner.MetadataChange>,
    checkedIndices: Set<Int>,
    onToggle: (Int) -> Unit,
    onSelectAllHighConfidence: () -> Unit,
    onApply: () -> Unit,
) {
    if (changes.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "No metadata issues found — everything already looks correct.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${changes.size} proposed changes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = onSelectAllHighConfidence) { Text("Select High-Confidence") }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            itemsIndexed(changes) { index, change ->
                ChangeRow(
                    change = change,
                    checked = index in checkedIndices,
                    onToggle = { onToggle(index) },
                    modifier = Modifier.animateItem().staggeredEntrance(index),
                )
            }
        }
        Surface(shadowElevation = 8.dp) {
            Button(
                onClick = onApply,
                enabled = checkedIndices.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text("Apply Selected (${checkedIndices.size})")
            }
        }
    }
}

@Composable
private fun ChangeRow(
    change: MetadataEnrichmentScanner.MetadataChange,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(change.song.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(change.song.displayArtist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${if (change.field == MetadataEnrichmentScanner.Field.YEAR) "Year" else "Genre"}: ${change.oldValue} → ${change.newValue}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    change.sourceNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.width(8.dp))
            AssistChip(onClick = {}, label = { Text(change.confidence.name) })
        }
    }
}

@Composable
private fun DoneContent(state: EnrichmentUiState.Done, onScanAgain: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        var iconVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { iconVisible = true }
        AnimatedVisibility(
            visible = iconVisible,
            enter = scaleIn(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)) +
                fadeIn(tween(280, easing = EmphasizedDecelerate)),
        ) {
            Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        Text("${state.updated} updated", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        if (state.failed > 0) {
            Text("${state.failed} failed to write", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (state.skipped > 0) {
            Text("${state.skipped} skipped (not selected)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onScanAgain) { Text("Scan Again") }
    }
}
