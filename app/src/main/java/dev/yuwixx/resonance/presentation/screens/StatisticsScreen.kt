package dev.yuwixx.resonance.presentation.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.yuwixx.resonance.data.database.dao.HourPlayCount
import dev.yuwixx.resonance.presentation.components.AppSectionHeader
import dev.yuwixx.resonance.presentation.viewmodel.StatsPeriod
import dev.yuwixx.resonance.presentation.viewmodel.StatisticsData
import dev.yuwixx.resonance.presentation.viewmodel.StatisticsViewModel
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onBack: () -> Unit,
    viewModel: StatisticsViewModel = hiltViewModel(),
) {
    val period by viewModel.period.collectAsState()
    val stats by viewModel.stats.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Listening Stats") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                PeriodSelector(period = period, onSelect = viewModel::setPeriod)
            }

            if (stats == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                val data = stats!!

                item { SummaryCards(data) }

                if (data.hourlyActivity.isNotEmpty()) {
                    item {
                        AppSectionHeader("Activity by Hour", Icons.Rounded.AccessTime)
                        HourlyChart(
                            data = data.hourlyActivity,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .height(140.dp),
                        )
                    }
                }

                if (data.topSongs.isNotEmpty()) {
                    item { AppSectionHeader("Top Songs", Icons.Rounded.MusicNote) }
                    items(data.topSongs) { (song, count) ->
                        ListItem(
                            headlineContent = {
                                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.MusicNote,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                Text(
                                    "$count plays",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }

                if (data.topArtists.isNotEmpty()) {
                    item { AppSectionHeader("Top Artists", Icons.Rounded.Person) }
                    items(data.topArtists) { (artist, count) ->
                        ListItem(
                            headlineContent = {
                                Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.Person,
                                    null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                            },
                            trailingContent = {
                                Text(
                                    "$count plays",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }

                if (data.topSongs.isEmpty() && data.topArtists.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "No listening history for this period",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeriodSelector(period: StatsPeriod, onSelect: (StatsPeriod) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        items(StatsPeriod.entries) { p ->
            FilterChip(
                selected = p == period,
                onClick = { onSelect(p) },
                label = { Text(p.label) },
            )
        }
    }
}

@Composable
private fun SummaryCards(data: StatisticsData) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Listening Time", formatDuration(data.totalListenTimeMs), Modifier.weight(1f))
            StatCard("Tracks Played", data.playCount.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Unique Tracks", data.uniqueTrackCount.toString(), Modifier.weight(1f))
            StatCard(
                label = "Listen Streak",
                value = if (data.listenStreak > 0) "${data.listenStreak} days" else "–",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HourlyChart(data: List<HourPlayCount>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val hourMap = data.associate { it.hour to it.playCount }
    val maxCount = (data.maxOfOrNull { it.playCount } ?: 1).coerceAtLeast(1)

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val barWidth = size.width / 24f
            val maxBarH = size.height

            for (hour in 0..23) {
                val count = hourMap[hour] ?: 0
                val barH = (count.toFloat() / maxCount) * maxBarH
                val x = hour * barWidth

                drawRect(
                    color = surfaceVariant,
                    topLeft = Offset(x + 2f, 0f),
                    size = Size(barWidth - 4f, maxBarH),
                )
                if (barH > 0f) {
                    drawRect(
                        color = primary,
                        topLeft = Offset(x + 2f, maxBarH - barH),
                        size = Size(barWidth - 4f, barH),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("0", "6", "12", "18", "23").forEach { label ->
                Text(label, style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant)
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0m"
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
