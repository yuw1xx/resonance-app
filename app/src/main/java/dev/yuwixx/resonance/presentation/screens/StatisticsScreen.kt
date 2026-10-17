@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.yuwixx.resonance.presentation.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.yuwixx.resonance.presentation.components.AppSectionHeader
import dev.yuwixx.resonance.presentation.components.ArtworkImage
import dev.yuwixx.resonance.presentation.components.BarChart
import dev.yuwixx.resonance.presentation.components.DonutChart
import dev.yuwixx.resonance.presentation.components.EmphasizedAccelerate
import dev.yuwixx.resonance.presentation.components.EmphasizedDecelerate
import dev.yuwixx.resonance.presentation.components.LineChart
import dev.yuwixx.resonance.presentation.components.formatListenDuration
import dev.yuwixx.resonance.presentation.components.staggeredEntrance
import dev.yuwixx.resonance.data.repository.toDomain
import dev.yuwixx.resonance.presentation.viewmodel.StatsPeriod
import dev.yuwixx.resonance.presentation.viewmodel.StatisticsData
import dev.yuwixx.resonance.presentation.viewmodel.StatisticsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onBack: () -> Unit,
    viewModel: StatisticsViewModel = hiltViewModel(),
) {
    val period by viewModel.period.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()

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
        // A plain Column (the content is small) so the dashboard crossfades as one on period change.
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            PeriodSelector(period = period, onSelect = viewModel::setPeriod)

            AnimatedContent(
                targetState = stats,
                transitionSpec = {
                    fadeIn(tween(340, easing = EmphasizedDecelerate)) togetherWith
                        fadeOut(tween(210, easing = EmphasizedAccelerate))
                },
                label = "stats_dashboard",
            ) { currentStats ->
                if (currentStats == null) {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                } else {
                    val data = currentStats
                    Column(modifier = Modifier.padding(bottom = 24.dp)) {
                        SummaryCards(data)

                        if (data.dailyTrend.size > 1) {
                            AppSectionHeader("Listening Trend", Icons.Rounded.ShowChart)
                            LineChart(
                                points = data.dailyTrend.map { it.totalMs.toFloat() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .height(120.dp),
                            )
                        }

                        if (data.hourlyActivity.isNotEmpty()) {
                            AppSectionHeader("Activity by Hour", Icons.Rounded.AccessTime)
                            val hourMap = data.hourlyActivity.associate { it.hour to it.playCount }
                            BarChart(
                                values = (0..23).map { (hourMap[it] ?: 0).toFloat() },
                                labels = listOf("0", "6", "12", "18", "23"),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .height(140.dp),
                            )
                        }

                        if (data.dayOfWeekActivity.isNotEmpty()) {
                            AppSectionHeader("Activity by Day", Icons.Rounded.CalendarViewWeek)
                            val dowMap = data.dayOfWeekActivity.associate { it.dayOfWeek to it.playCount }
                            BarChart(
                                values = (0..6).map { (dowMap[it] ?: 0).toFloat() },
                                labels = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .height(140.dp),
                            )
                        }

                        if (data.genreBreakdown.isNotEmpty()) {
                            AppSectionHeader("Genres", Icons.Rounded.PieChart)
                            DonutChart(
                                entries = data.genreBreakdown,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }

                        if (data.formatBreakdown.isNotEmpty()) {
                            AppSectionHeader("Formats", Icons.Rounded.GraphicEq)
                            BarChart(
                                values = data.formatBreakdown.map { it.second.toFloat() },
                                labels = data.formatBreakdown.map { it.first },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .height(120.dp),
                            )
                        }

                        if (data.topSongs.isNotEmpty()) {
                            AppSectionHeader("Top Songs", Icons.Rounded.MusicNote)
                            data.topSongs.forEachIndexed { index, (song, count) ->
                                ListItem(
                                    modifier = Modifier.staggeredEntrance(index),
                                    headlineContent = {
                                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    supportingContent = {
                                        Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    leadingContent = {
                                        ArtworkImage(
                                            uri = song.toDomain().artworkUri,
                                            contentDescription = song.album,
                                            modifier = Modifier.size(44.dp),
                                            cornerRadius = 8.dp,
                                            onArtworkMissing = { viewModel.getSongArtworkUrl(song) },
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
                            AppSectionHeader("Top Artists", Icons.Rounded.Person)
                            data.topArtists.forEachIndexed { index, (artist, count) ->
                                ListItem(
                                    modifier = Modifier.staggeredEntrance(index),
                                    headlineContent = {
                                        Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    leadingContent = {
                                        ArtworkImage(
                                            uri = null,
                                            contentDescription = artist,
                                            modifier = Modifier.size(44.dp),
                                            cornerRadius = 22.dp,
                                            onArtworkMissing = { viewModel.getArtistArtworkUrl(artist) },
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

                        if (data.topAlbums.isNotEmpty()) {
                            AppSectionHeader("Top Albums", Icons.Rounded.Album)
                            data.topAlbums.forEachIndexed { index, (song, count) ->
                                ListItem(
                                    modifier = Modifier.staggeredEntrance(index),
                                    headlineContent = {
                                        Text(song.album.ifBlank { "Unknown Album" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    supportingContent = {
                                        Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    leadingContent = {
                                        ArtworkImage(
                                            uri = song.toDomain().artworkUri,
                                            contentDescription = song.album,
                                            modifier = Modifier.size(44.dp),
                                            cornerRadius = 8.dp,
                                            onArtworkMissing = { viewModel.getSongArtworkUrl(song) },
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
            StatCard("Listening Time", data.totalListenTimeMs, Modifier.weight(1f)) { formatListenDuration(it) }
            StatCard("Tracks Played", data.playCount.toLong(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Unique Tracks", data.uniqueTrackCount.toLong(), Modifier.weight(1f))
            StatCard(
                label = "Listen Streak",
                rawValue = data.listenStreak.toLong(),
                modifier = Modifier.weight(1f),
            ) { if (it > 0) "$it days" else "–" }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = "Longest Streak",
                rawValue = data.longestStreak.toLong(),
                modifier = Modifier.weight(1f),
            ) { if (it > 0) "$it days" else "–" }
            StatCard("Avg Session", data.avgSessionMs, Modifier.weight(1f)) { formatListenDuration(it) }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    rawValue: Long,
    modifier: Modifier = Modifier,
    format: (Long) -> String = { it.toString() },
) {
    // Counts toward the new value. Long, since listening time in ms can overflow an Int.
    val animatedValue = remember { Animatable(0f) }
    LaunchedEffect(rawValue) {
        animatedValue.animateTo(rawValue.toFloat(), tween(600, easing = EmphasizedDecelerate))
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(format(animatedValue.value.toLong()), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
