// Multi-level settings UI: a top-level menu of categories that each drill into their own
// page (Appearance, Playback, Audio, Library, Navidrome, Last.fm, History, Data, About, …).
package dev.yuwixx.resonance.presentation.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.yuwixx.resonance.APP_VERSION
import dev.yuwixx.resonance.data.model.MusicSource
import dev.yuwixx.resonance.data.repository.LastFmAuthState
import dev.yuwixx.resonance.data.repository.NavidromeConnectionState
import dev.yuwixx.resonance.data.repository.NavidromeSyncState
import dev.yuwixx.resonance.presentation.components.BigScreenTitle
import dev.yuwixx.resonance.presentation.components.SectionCard
import dev.yuwixx.resonance.presentation.components.SectionDivider
import dev.yuwixx.resonance.presentation.components.SectionSubHeader
import dev.yuwixx.resonance.presentation.navigation.navItems
import dev.yuwixx.resonance.presentation.viewmodel.BackupUiState
import dev.yuwixx.resonance.presentation.viewmodel.BackupViewModel
import dev.yuwixx.resonance.presentation.viewmodel.LibraryViewModel
import dev.yuwixx.resonance.presentation.viewmodel.SettingsViewModel
import dev.yuwixx.resonance.presentation.viewmodel.ShareViewModel
import dev.yuwixx.resonance.ui.theme.PresetColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class SettingsCategory(val title: String, val icon: ImageVector) {
    Main("Settings", Icons.Rounded.Settings),
    Updates("Updates", Icons.Rounded.SystemUpdate),
    Appearance("Appearance", Icons.Rounded.Palette),
    Home("Home Screen", Icons.Rounded.Home),
    Player("Player", Icons.Rounded.MusicNote),
    Playback("Playback", Icons.Rounded.PlayCircle),
    Audio("Audio", Icons.Rounded.GraphicEq),
    Library("Library", Icons.Rounded.LibraryMusic),
    Navidrome("Navidrome", Icons.Rounded.Cloud),
    OnlineServices("Online Services", Icons.Rounded.Hub),
    Notification("Notification", Icons.Rounded.Notifications),
    History("Play History", Icons.Rounded.History),
    Data("Data", Icons.Rounded.Storage),
    About("About", Icons.Rounded.Info)
}

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

// Groups of categories shown in the main settings list with section headers.
private data class SettingsGroup(val label: String, val items: List<SettingsCategory>)
private val settingsGroups = listOf(
    SettingsGroup("Looks & Feel",      listOf(SettingsCategory.Appearance, SettingsCategory.Home, SettingsCategory.Player)),
    SettingsGroup("Audio & Playback",  listOf(SettingsCategory.Playback, SettingsCategory.Audio)),
    SettingsGroup("Library",           listOf(SettingsCategory.Library, SettingsCategory.Navidrome)),
    SettingsGroup("Online Services",   listOf(SettingsCategory.OnlineServices)),
    SettingsGroup("System",            listOf(SettingsCategory.Notification, SettingsCategory.History, SettingsCategory.Updates, SettingsCategory.Data)),
    SettingsGroup("Info",              listOf(SettingsCategory.About)),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToLicenses: () -> Unit,
    onNavigateToEqualizer: () -> Unit,
    libraryViewModel: LibraryViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val scope = rememberCoroutineScope()
    val prefs = libraryViewModel.prefs
    val snackbarHostState = remember { SnackbarHostState() }

    var currentCategory by rememberSaveable { mutableStateOf(SettingsCategory.Main) }

    BackHandler(enabled = currentCategory != SettingsCategory.Main) {
        currentCategory = SettingsCategory.Main
    }

    val updateFreq by settingsViewModel.updateFrequency.collectAsState()

    val dynamicColor by prefs.dynamicColorEnabled.collectAsState(initial = true)
    val presetColorInt by prefs.presetColor.collectAsState(initial = null)
    val darkTheme by prefs.darkTheme.collectAsState(initial = "SYSTEM")
    val cornerRadius by prefs.cornerRadius.collectAsState(initial = 28)
    val seekbarStyle by prefs.seekbarStyle.collectAsState(initial = "WAVEFORM")
    val blurBackground by prefs.blurArtworkBackground.collectAsState(initial = true)
    val blurStrength by prefs.blurStrength.collectAsState(initial = 0.3f)
    val artworkAnimation by prefs.artworkAnimation.collectAsState(initial = true)
    val hapticFeedback by prefs.hapticFeedback.collectAsState(initial = true)
    val showBitrateInfo by prefs.showBitrateInfo.collectAsState(initial = false)
    val albumGridCols by prefs.albumGridColumns.collectAsState(initial = 2)
    val hiddenNavTabs by prefs.hiddenNavTabs.collectAsState(initial = emptySet())
    val miniPlayerStyle by prefs.miniPlayerStyle.collectAsState(initial = "CARD")
    val playerLayout by prefs.playerLayout.collectAsState(initial = "STANDARD")
    val showLyricsBtn by prefs.showLyricsButton.collectAsState(initial = true)
    val lyricsFontScale by prefs.lyricsFontScale.collectAsState(initial = 1.0f)

    val amoledMode by prefs.amoledBlackTheme.collectAsState(initial = false)
    val compactList by prefs.compactListMode.collectAsState(initial = false)
    val showDurationList by prefs.showDurationInList.collectAsState(initial = false)
    val playerArtworkShape by prefs.playerArtworkShape.collectAsState(initial = "ROUNDED")
    val lyricAlignment by prefs.lyricAlignment.collectAsState(initial = "CENTER")
    val seekbarColor by prefs.seekbarColor.collectAsState(initial = "PRIMARY")
    val tintedNavBar by prefs.tintedNavBar.collectAsState(initial = false)
    val floatingNavBar by prefs.floatingNavBar.collectAsState(initial = false)
    val navLabelVisibility by prefs.navLabelVisibility.collectAsState(initial = "ALWAYS")
    val playlistGridCols by prefs.playlistGridColumns.collectAsState(initial = 2)
    val homeShowMostPlayed by prefs.homeShowMostPlayed.collectAsState(initial = true)

    val homeShowRecentlyAdded by prefs.homeShowRecentlyAdded.collectAsState(initial = true)
    val homeRecentlyAddedCount by prefs.homeRecentlyAddedCount.collectAsState(initial = 20)
    val mixesLocation by prefs.mixesLocation.collectAsState(initial = "BOTH")

    val showAlbumInList by prefs.showAlbumInList.collectAsState(initial = false)
    val listArtworkSize by prefs.listArtworkSize.collectAsState(initial = "MEDIUM")
    val defaultSongsSort by prefs.defaultSongsSort.collectAsState(initial = "TITLE")
    val showRemainingTime by prefs.showRemainingTime.collectAsState(initial = false)
    val showNextSongInPlayer by prefs.showNextSongInPlayer.collectAsState(initial = false)
    val lyricsLineSpacing by prefs.lyricsLineSpacing.collectAsState(initial = "NORMAL")
    val miniPlayerShowProgress by prefs.miniPlayerShowProgress.collectAsState(initial = true)
    val miniPlayerShowSkipBtn by prefs.miniPlayerShowSkipBtn.collectAsState(initial = true)
    val showEqualizerInPlayer by prefs.showEqualizerInPlayer.collectAsState(initial = true)

    val gapless by prefs.gaplessEnabled.collectAsState(initial = true)
    val skipSilence by prefs.skipSilence.collectAsState(initial = false)
    val crossfadeMs by prefs.crossfadeDurationMs.collectAsState(initial = 0)
    val playbackSpeed by prefs.playbackSpeed.collectAsState(initial = 1.0f)
    val playbackPitch by prefs.playbackPitch.collectAsState(initial = 1.0f)
    val resumeOnHeadphones by prefs.resumeOnHeadphones.collectAsState(initial = true)
    val pauseOnHeadphonesOut by prefs.pauseOnHeadphonesOut.collectAsState(initial = true)
    val duckAudio by prefs.duckAudioOnFocusLoss.collectAsState(initial = true)
    val smartShuffle by prefs.smartShuffleEnabled.collectAsState(initial = false)
    val volumeNorm by prefs.volumeNormalization.collectAsState(initial = false)

    val replayGainMode by prefs.replayGainMode.collectAsState(initial = "TRACK")
    val replayGainPreamp by prefs.replayGainPreampDb.collectAsState(initial = 0f)

    val isSyncing by libraryViewModel.isSyncing.collectAsState()
    val minDurationMs by prefs.minTrackDurationMs.collectAsState(initial = 30_000L)
    val artistDelimiter by prefs.artistDelimiter.collectAsState(initial = ",;/&")
    val showArtworkList by prefs.showArtworkInList.collectAsState(initial = true)
    val groupByAlbumArtist by prefs.groupByAlbumArtist.collectAsState(initial = true)
    val showFilenameTitle by prefs.showFilenameAsTitle.collectAsState(initial = false)
    val ignoreArticles by prefs.ignoreArticles.collectAsState(initial = true)
    val autoScanHours by prefs.autoScanIntervalHours.collectAsState(initial = 0)
    val fetchArtistImages by prefs.fetchArtistImages.collectAsState(initial = false)
    val fetchLyrics by prefs.fetchLyrics.collectAsState(initial = false)
    val fetchAlbumArt by prefs.fetchAlbumArt.collectAsState(initial = false)
    val allFolders by libraryViewModel.allFolders.collectAsState()
    val excludedFolders by prefs.excludedFolders.collectAsState(initial = emptySet())
    val includedFolders by prefs.includedFolders.collectAsState(initial = emptySet())

    val lockscreenArtwork by prefs.lockscreenArtwork.collectAsState(initial = true)
    val showSkipButtons by prefs.showSkipButtons.collectAsState(initial = true)

    val historyEnabled by prefs.historyEnabled.collectAsState(initial = true)
    val minListenSecs by prefs.minListenSeconds.collectAsState(initial = 30)
    val minListenPct by prefs.minListenPercentage.collectAsState(initial = 0.5f)
    val maxHistory by prefs.maxHistoryItems.collectAsState(initial = 1000)

    val lastFmEnabled by settingsViewModel.lastFmEnabled.collectAsState()
    val lastFmNowPlaying by settingsViewModel.lastFmNowPlaying.collectAsState()
    val lastFmOnlyWifi by settingsViewModel.lastFmOnlyWifi.collectAsState()
    val lastFmScrobblePct by settingsViewModel.lastFmScrobblePct.collectAsState()
    val lastFmScrobbleMinSecs by settingsViewModel.lastFmScrobbleMinSecs.collectAsState()
    val lastFmOfflineQueue by settingsViewModel.lastFmOfflineQueue.collectAsState()
    val lastFmAuthState by settingsViewModel.lastFmAuthState.collectAsState()
    val lastFmPending by settingsViewModel.pendingScrobbles.collectAsState()
    val malojaEnabled by settingsViewModel.malojaEnabled.collectAsState()
    val malojaServerUrl by settingsViewModel.malojaServerUrl.collectAsState()
    val malojaTestState by settingsViewModel.malojaTestState.collectAsState()
    val malojaPending by settingsViewModel.malojaPending.collectAsState()
    val remoteShareServerUrl by settingsViewModel.remoteShareServerUrl.collectAsState()
    val remoteShareUploadToken by settingsViewModel.remoteShareUploadToken.collectAsState()
    val partyMode by settingsViewModel.partyMode.collectAsState()
    val currentMusicSource by settingsViewModel.musicSource.collectAsState()
    val navidromeServerUrl by settingsViewModel.navidromeServerUrl.collectAsState()
    val navidromeUsername by settingsViewModel.navidromeUsername.collectAsState()
    val navidromeConnectionState by settingsViewModel.navidromeConnectionState.collectAsState()
    val navidromeSyncState by settingsViewModel.navidromeSyncState.collectAsState()
    val downloadWifiOnly by settingsViewModel.downloadWifiOnly.collectAsState()
    val downloadsStorageUsed by settingsViewModel.downloadsStorageUsed.collectAsState()
    val downloadedSongCount by settingsViewModel.downloadedSongCount.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentCategory == SettingsCategory.Main) onBack()
                        else currentCategory = SettingsCategory.Main
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        AnimatedContent(
            targetState = currentCategory,
            transitionSpec = {
                if (targetState != SettingsCategory.Main) {
                    // Drill into sub-page: shared axis X forward
                    (slideInHorizontally(tween(500, easing = EmphasizedDecelerate)) { (it * 0.15f).toInt() } +
                        fadeIn(tween(500, delayMillis = 50, easing = EmphasizedDecelerate)))
                        .togetherWith(
                            slideOutHorizontally(tween(400, easing = EmphasizedAccelerate)) { -(it * 0.15f).toInt() } +
                                fadeOut(tween(200, easing = EmphasizedAccelerate))
                        )
                } else {
                    // Back to main: shared axis X reverse
                    (slideInHorizontally(tween(500, easing = EmphasizedDecelerate)) { -(it * 0.15f).toInt() } +
                        fadeIn(tween(500, delayMillis = 50, easing = EmphasizedDecelerate)))
                        .togetherWith(
                            slideOutHorizontally(tween(400, easing = EmphasizedAccelerate)) { (it * 0.15f).toInt() } +
                                fadeOut(tween(200, easing = EmphasizedAccelerate))
                        )
                }
            },
            label = "settings_content"
        ) { category ->
            LazyColumn(
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 32.dp,
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                item { BigScreenTitle(category.title) }
                when (category) {
                    SettingsCategory.Main -> {
                        // App header card
                        item {
                            var easterEggTaps by remember { mutableIntStateOf(0) }
                            var showEasterEgg by remember { mutableStateOf(false) }
                            var showPartyDisable by remember { mutableStateOf(false) }
                            val bounceScale = remember { Animatable(1f) }

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .graphicsLayer { scaleX = bounceScale.value; scaleY = bounceScale.value }
                                    .clickable {
                                        scope.launch {
                                            bounceScale.animateTo(0.93f, spring(stiffness = Spring.StiffnessHigh))
                                            bounceScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
                                        }
                                        if (partyMode) {
                                            showPartyDisable = true
                                        } else {
                                            easterEggTaps++
                                            if (easterEggTaps >= 5) {
                                                showEasterEgg = true
                                                easterEggTaps = 0
                                            }
                                        }
                                    },
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Surface(
                                        shape = MaterialTheme.shapes.medium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(52.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Rounded.MusicNote,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(28.dp),
                                            )
                                        }
                                    }
                                    Column {
                                        Text(
                                            if (partyMode) "🎉 Resonance" else "Resonance",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                        Text(
                                            when {
                                                partyMode -> "Party Mode is ON"
                                                easterEggTaps in 1..4 -> "${5 - easterEggTaps} more…"
                                                else -> "v$APP_VERSION"
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                            }

                            if (showEasterEgg) {
                                AlertDialog(
                                    onDismissRequest = { showEasterEgg = false },
                                    icon = { Text("🎵", fontSize = 40.sp) },
                                    title = { Text("You found it!") },
                                    text = {
                                        Text(
                                            "Resonance was built with love for music.\n\nThanks for using the app! ♪\n\nPsst — want to unlock something special?",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    },
                                    confirmButton = {
                                        Button(onClick = {
                                            settingsViewModel.setPartyMode(true)
                                            showEasterEgg = false
                                        }) { Text("🎉 Party Mode!") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showEasterEgg = false }) { Text("♫ Nice") }
                                    },
                                )
                            }

                            if (showPartyDisable) {
                                AlertDialog(
                                    onDismissRequest = { showPartyDisable = false },
                                    icon = { Text("🎉", fontSize = 40.sp) },
                                    title = { Text("Party Mode is ON") },
                                    text = {
                                        Text(
                                            "The party is still going! Want to turn it off?",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    },
                                    confirmButton = {
                                        Button(onClick = {
                                            settingsViewModel.setPartyMode(false)
                                            showPartyDisable = false
                                        }) { Text("End the party") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showPartyDisable = false }) { Text("Keep it going!") }
                                    },
                                )
                            }
                        }

                        // Grouped category rows
                        settingsGroups.forEach { group ->
                            item {
                                Text(
                                    text = group.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                                )
                            }
                            item {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .padding(bottom = 4.dp),
                                    shape = MaterialTheme.shapes.large,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Column {
                                        group.items.forEachIndexed { idx, cat ->
                                            if (idx > 0) HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                            )
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { currentCategory = cat }
                                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                            ) {
                                                Surface(
                                                    shape = MaterialTheme.shapes.small,
                                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                                    modifier = Modifier.size(38.dp),
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            cat.icon,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                            modifier = Modifier.size(20.dp),
                                                        )
                                                    }
                                                }
                                                Text(
                                                    cat.title,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.Normal,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                Icon(
                                                    Icons.Rounded.ChevronRight,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item { Spacer(Modifier.height(16.dp)) }
                    }

                    SettingsCategory.Updates -> {
                        item {
                            var showDisableWarning by remember { mutableStateOf(false) }

                            SectionCard(icon = Icons.Rounded.SystemUpdate, title = "Updates", modifier = Modifier.padding(horizontal = 8.dp)) {
                                SegmentedSettingsItem(
                                    title = "Check for Updates",
                                    options = listOf("Launch" to "LAUNCH", "Daily" to "DAILY", "Weekly" to "WEEKLY", "Off" to "DISABLED"),
                                    selected = updateFreq,
                                    onSelect = {
                                        if (it == "DISABLED") showDisableWarning = true
                                        else settingsViewModel.setUpdateFrequency(it)
                                    },
                                )
                                SettingsTextItem(
                                    title = "Check Now",
                                    subtitle = "Current version: v$APP_VERSION",
                                    icon = Icons.Rounded.Update,
                                    onClick = { settingsViewModel.checkForUpdates(APP_VERSION, isManual = true) }
                                )
                            }

                            if (showDisableWarning) {
                                AlertDialog(
                                    onDismissRequest = { showDisableWarning = false },
                                    icon = { Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error) },
                                    title = { Text("Disable Updates?") },
                                    text = { Text("It is highly recommended to keep update checks enabled so you don't miss bug fixes and new features.\n\nAre you sure you want to disable automatic checks?") },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                settingsViewModel.setUpdateFrequency("DISABLED")
                                                showDisableWarning = false
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) { Text("Disable") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showDisableWarning = false }) { Text("Keep Enabled") }
                                    }
                                )
                            }
                        }
                    }

                    SettingsCategory.Appearance -> {
                        item {
                            var showNavTabsDialog by remember { mutableStateOf(false) }
                            val visibleCount = navItems.count { it.screen.route !in hiddenNavTabs }

                            SectionCard(icon = Icons.Rounded.Palette, title = "Appearance", modifier = Modifier.padding(horizontal = 8.dp)) {
                                SectionSubHeader("Color & Theme")
                                SettingsToggleRow("System Dynamic Color", "Use Android 12+ wallpaper colours", dynamicColor) {
                                    scope.launch { prefs.setDynamicColorEnabled(it) }
                                }
                                if (!dynamicColor) {
                                    ThemeColorPicker(
                                        current = presetColorInt,
                                        onPick = { scope.launch { prefs.setPresetColor(it) } }
                                    )
                                }
                                SegmentedSettingsItem(
                                    title = "Dark Theme",
                                    options = listOf("System" to "SYSTEM", "Light" to "LIGHT", "Dark" to "DARK"),
                                    selected = darkTheme,
                                    onSelect = { scope.launch { prefs.setDarkTheme(it) } },
                                )
                                SegmentedSettingsItem(
                                    title = "Seekbar Style",
                                    options = listOf("Standard" to "STANDARD", "Waveform" to "WAVEFORM", "Material You 3" to "MATERIAL_YOU_3", "Cava" to "CAVA"),
                                    selected = seekbarStyle,
                                    onSelect = { scope.launch { prefs.setSeekbarStyle(it) } },
                                )
                                SettingsToggleRow("Blur Background", "Artwork-tinted blurred backdrop in player", blurBackground) {
                                    scope.launch { prefs.setBlurArtworkBackground(it) }
                                }
                                if (blurBackground) {
                                    SettingsSliderItem(
                                        title = "Blur Intensity",
                                        value = blurStrength,
                                        range = 0.1f..0.7f,
                                        label = "${(blurStrength * 100).roundToInt()}%",
                                        onValueChange = { scope.launch { prefs.setBlurStrength(it) } },
                                    )
                                }
                                SettingsToggleRow("Artwork Animation", "Scale artwork on play/pause", artworkAnimation) {
                                    scope.launch { prefs.setArtworkAnimation(it) }
                                }
                                SettingsToggleRow("Haptic Feedback", "Vibrate on seek and long-press", hapticFeedback) {
                                    scope.launch { prefs.setHapticFeedback(it) }
                                }
                                SettingsToggleRow("Show Bitrate & Format", "Display audio quality badge in player", showBitrateInfo) {
                                    scope.launch { prefs.setShowBitrateInfo(it) }
                                }
                                SettingsSliderItem(
                                    title = "Corner Radius",
                                    value = cornerRadius.toFloat(),
                                    range = 0f..40f,
                                    label = "${cornerRadius}dp",
                                    steps = 40,
                                    onValueChange = { scope.launch { prefs.setCornerRadius(it.toInt()) } },
                                )
                                SettingsSliderItem(
                                    title = "Album Grid Columns",
                                    value = albumGridCols.toFloat(),
                                    range = 2f..4f,
                                    label = "$albumGridCols columns",
                                    steps = 1,
                                    onValueChange = { scope.launch { prefs.setAlbumGridColumns(it.toInt()) } },
                                )
                                SettingsToggleRow("AMOLED Black Mode", "True black backgrounds for OLED displays", amoledMode) {
                                    scope.launch { prefs.setAmoledBlackTheme(it) }
                                }
                                SegmentedSettingsItem(
                                    title = "Player Artwork Shape",
                                    options = listOf("Rounded" to "ROUNDED", "Square" to "SQUARE", "Circle" to "CIRCLE"),
                                    selected = playerArtworkShape,
                                    onSelect = { scope.launch { prefs.setPlayerArtworkShape(it) } },
                                )
                                SegmentedSettingsItem(
                                    title = "Seekbar Colour",
                                    options = listOf("Primary" to "PRIMARY", "Secondary" to "SECONDARY", "Tertiary" to "TERTIARY"),
                                    selected = seekbarColor,
                                    onSelect = { scope.launch { prefs.setSeekbarColor(it) } },
                                )

                                SectionDivider()
                                SectionSubHeader("Song Lists")
                                SettingsToggleRow("Compact Mode", "Reduce row height for a denser list view", compactList) {
                                    scope.launch { prefs.setCompactListMode(it) }
                                }
                                SettingsToggleRow("Show Duration in List", "Display track length next to each song", showDurationList) {
                                    scope.launch { prefs.setShowDurationInList(it) }
                                }
                                SettingsToggleRow("Show Album in List", "Display album name below artist in song rows", showAlbumInList) {
                                    scope.launch { prefs.setShowAlbumInList(it) }
                                }
                                SegmentedSettingsItem(
                                    title = "List Artwork Size",
                                    options = listOf("Small" to "SMALL", "Medium" to "MEDIUM", "Large" to "LARGE"),
                                    selected = listArtworkSize,
                                    onSelect = { scope.launch { prefs.setListArtworkSize(it) } },
                                )
                                SegmentedSettingsItem(
                                    title = "Default Songs Sort",
                                    options = listOf("Title" to "TITLE", "Artist" to "ARTIST", "Album" to "ALBUM", "Added" to "ADDED"),
                                    selected = defaultSongsSort,
                                    onSelect = { scope.launch { prefs.setDefaultSongsSort(it) } },
                                )
                                SettingsSliderItem(
                                    title = "Playlist Grid Columns",
                                    value = playlistGridCols.toFloat(),
                                    range = 1f..4f,
                                    label = "$playlistGridCols ${if (playlistGridCols == 1) "column" else "columns"}",
                                    steps = 2,
                                    onValueChange = { scope.launch { prefs.setPlaylistGridColumns(it.toInt()) } },
                                )

                                SectionDivider()
                                SectionSubHeader("Navigation Bar")
                                SettingsToggleRow("Floating Navigation Bar", "Pill-shaped bar that floats above the screen edge", floatingNavBar) {
                                    scope.launch { prefs.setFloatingNavBar(it) }
                                }
                                SettingsToggleRow("Tinted Navigation Bar", "Apply surface tint to the bottom navigation bar", tintedNavBar) {
                                    scope.launch { prefs.setTintedNavBar(it) }
                                }
                                SegmentedSettingsItem(
                                    title = "Tab Labels",
                                    options = listOf("Always" to "ALWAYS", "Selected" to "SELECTED", "Never" to "NEVER"),
                                    selected = navLabelVisibility,
                                    onSelect = { scope.launch { prefs.setNavLabelVisibility(it) } },
                                )
                                SettingsTextItem(
                                    title = "Navigation Bar Tabs",
                                    subtitle = "$visibleCount of ${navItems.size} tabs shown",
                                    icon = Icons.Rounded.Tab,
                                    onClick = { showNavTabsDialog = true },
                                )
                            }

                            if (showNavTabsDialog) {
                                NavTabsDialog(
                                    hiddenNavTabs = hiddenNavTabs,
                                    onToggle = { route, shouldHide ->
                                        val updated = if (shouldHide) hiddenNavTabs + route
                                                      else             hiddenNavTabs - route
                                        scope.launch { prefs.setHiddenNavTabs(updated) }
                                    },
                                    onDismiss = { showNavTabsDialog = false },
                                )
                            }
                        }
                    }

                    SettingsCategory.Home -> {
                        item {
                            SectionCard(icon = Icons.Rounded.Home, title = "Home Screen Sections", modifier = Modifier.padding(horizontal = 8.dp)) {
                                SegmentedSettingsItem(
                                    title = "Your Mixes",
                                    options = listOf("Home only" to "HOME", "Both" to "BOTH", "Playlists only" to "PLAYLISTS"),
                                    selected = mixesLocation,
                                    onSelect = { scope.launch { prefs.setMixesLocation(it) } },
                                )
                                SettingsToggleRow("Most Played", "Show your top tracks as a horizontal scroll row", homeShowMostPlayed) {
                                    scope.launch { prefs.setHomeShowMostPlayed(it) }
                                }
                                SettingsToggleRow("Recently Added", "Show recently added songs as a horizontal scroll row", homeShowRecentlyAdded) {
                                    scope.launch { prefs.setHomeShowRecentlyAdded(it) }
                                }
                                if (homeShowRecentlyAdded) {
                                    SegmentedSettingsItem(
                                        title = "Recently Added Count",
                                        options = listOf("10" to "10", "20" to "20", "30" to "30", "50" to "50"),
                                        selected = homeRecentlyAddedCount.toString(),
                                        onSelect = { scope.launch { prefs.setHomeRecentlyAddedCount(it.toInt()) } },
                                    )
                                }
                            }
                        }
                    }

                    SettingsCategory.Player -> {
                        item {
                            SectionCard(icon = Icons.Rounded.MusicNote, title = "Player", modifier = Modifier.padding(horizontal = 8.dp)) {
                                SegmentedSettingsItem(
                                    title = "Player Layout",
                                    options = listOf("Standard" to "STANDARD", "Big Artwork" to "ARTWORK_BIG", "Lyrics Focus" to "LYRICS_FOCUS"),
                                    selected = playerLayout,
                                    onSelect = { scope.launch { prefs.setPlayerLayout(it) } },
                                )
                                SegmentedSettingsItem(
                                    title = "Mini Player Style",
                                    options = listOf("Compact" to "COMPACT", "Card" to "CARD", "Floating" to "FLOATING"),
                                    selected = miniPlayerStyle,
                                    onSelect = { scope.launch { prefs.setMiniPlayerStyle(it) } },
                                )
                                SettingsToggleRow("Show Lyrics Button", "Display lyrics shortcut in player footer", showLyricsBtn) {
                                    scope.launch { prefs.setShowLyricsButton(it) }
                                }
                                SettingsSliderItem(
                                    title = "Lyrics Font Size",
                                    value = lyricsFontScale,
                                    range = 0.50f..1.75f,
                                    label = "${(lyricsFontScale * 100).roundToInt()}%",
                                    steps = 4,
                                    onValueChange = { newValue ->
                                        val step = 0.25f
                                        val rounded = (newValue / step).roundToInt() * step
                                        scope.launch { prefs.setLyricsFontScale(rounded.coerceIn(0.50f, 1.75f)) }
                                    },
                                )
                                SegmentedSettingsItem(
                                    title = "Lyrics Alignment",
                                    options = listOf("Centered" to "CENTER", "Left" to "START"),
                                    selected = lyricAlignment,
                                    onSelect = { scope.launch { prefs.setLyricAlignment(it) } },
                                )
                                SegmentedSettingsItem(
                                    title = "Lyrics Line Spacing",
                                    options = listOf("Compact" to "COMPACT", "Normal" to "NORMAL", "Spacious" to "SPACIOUS"),
                                    selected = lyricsLineSpacing,
                                    onSelect = { scope.launch { prefs.setLyricsLineSpacing(it) } },
                                )
                                SettingsToggleRow("Show Remaining Time", "Display remaining duration instead of total on the right", showRemainingTime) {
                                    scope.launch { prefs.setShowRemainingTime(it) }
                                }
                                SettingsToggleRow("Show Next Song", "Show upcoming track name below the seekbar", showNextSongInPlayer) {
                                    scope.launch { prefs.setShowNextSongInPlayer(it) }
                                }
                                SettingsToggleRow("Show Equalizer Button", "Display equalizer shortcut in player footer", showEqualizerInPlayer) {
                                    scope.launch { prefs.setShowEqualizerInPlayer(it) }
                                }

                                SectionDivider()
                                SectionSubHeader("Mini Player")
                                SettingsToggleRow("Show Progress Bar", "Display a thin progress line at the bottom of the mini player", miniPlayerShowProgress) {
                                    scope.launch { prefs.setMiniPlayerShowProgress(it) }
                                }
                                SettingsToggleRow("Show Skip Button", "Show skip-next button in the mini player", miniPlayerShowSkipBtn) {
                                    scope.launch { prefs.setMiniPlayerShowSkipBtn(it) }
                                }
                            }
                        }
                    }

                    SettingsCategory.Playback -> {
                        item {
                            SectionCard(icon = Icons.Rounded.PlayCircle, title = "Playback", modifier = Modifier.padding(horizontal = 8.dp)) {
                                SettingsToggleRow("Gapless Playback", "Smooth transition between tracks", gapless) {
                                    scope.launch { prefs.setGaplessEnabled(it) }
                                }
                                SettingsToggleRow("Skip Silence", "Automatically skip silent parts of tracks", skipSilence) {
                                    scope.launch { prefs.setSkipSilence(it) }
                                }
                                SettingsSliderItem(
                                    title = "Crossfade Duration",
                                    value = crossfadeMs.toFloat(),
                                    range = 0f..10000f,
                                    label = if (crossfadeMs == 0) "Disabled" else "${crossfadeMs / 1000}s",
                                    steps = 10,
                                    onValueChange = { newValue ->
                                        val step = 1000f
                                        val rounded = (newValue / step).roundToInt() * step
                                        scope.launch { prefs.setCrossfadeDuration(rounded.toInt()) }
                                    },
                                )
                                SettingsSliderItem(
                                    title = "Playback Speed",
                                    value = playbackSpeed,
                                    range = 0.5f..2.0f,
                                    label = "${playbackSpeed}x",
                                    steps = 6,
                                    onValueChange = { newValue ->
                                        val step = 0.25f
                                        val rounded = (newValue / step).roundToInt() * step
                                        scope.launch { prefs.setPlaybackSpeed(rounded.coerceIn(0.5f, 2.0f)) }
                                    },
                                )
                                SettingsSliderItem(
                                    title = "Playback Pitch",
                                    value = playbackPitch,
                                    range = 0.5f..2.0f,
                                    label = "${playbackPitch}x",
                                    steps = 6,
                                    onValueChange = { newValue ->
                                        val step = 0.25f
                                        val rounded = (newValue / step).roundToInt() * step
                                        scope.launch { prefs.setPlaybackPitch(rounded.coerceIn(0.5f, 2.0f)) }
                                    },
                                )
                                SettingsToggleRow("Resume on Headphones", "Continue playback when headphones are connected", resumeOnHeadphones) {
                                    scope.launch { prefs.setResumeOnHeadphones(it) }
                                }
                                SettingsToggleRow("Pause on Disconnect", "Stop playback when headphones are removed", pauseOnHeadphonesOut) {
                                    scope.launch { prefs.setPauseOnHeadphonesOut(it) }
                                }
                                SettingsToggleRow("Audio Ducking", "Lower volume when other apps play sound", duckAudio) {
                                    scope.launch { prefs.setDuckAudioOnFocusLoss(it) }
                                }
                                SettingsToggleRow("Smart Shuffle", "Prioritise higher rated and recent tracks", smartShuffle) {
                                    scope.launch { prefs.setSmartShuffleEnabled(it) }
                                }
                            }
                        }
                    }

                    SettingsCategory.Audio -> {
                        item {
                            SectionCard(icon = Icons.Rounded.GraphicEq, title = "Audio", modifier = Modifier.padding(horizontal = 8.dp)) {
                                SegmentedSettingsItem(
                                    title = "ReplayGain Mode",
                                    options = listOf("Off" to "OFF", "Track" to "TRACK", "Album" to "ALBUM"),
                                    selected = replayGainMode,
                                    onSelect = { scope.launch { prefs.setReplayGainMode(it) } },
                                )
                                SettingsSliderItem(
                                    title = "ReplayGain Preamp",
                                    value = replayGainPreamp,
                                    range = -15f..15f,
                                    label = when (val db = replayGainPreamp.roundToInt()) {
                                        0 -> "0 dB"
                                        else -> if (db > 0) "+$db dB" else "$db dB"
                                    },
                                    steps = 30,
                                    onValueChange = { scope.launch { prefs.setReplayGainPreamp(it) } },
                                )
                                SettingsToggleRow("Volume Normalisation", "Equalise loudness across all tracks", volumeNorm) {
                                    scope.launch { prefs.setVolumeNormalization(it) }
                                }
                                SettingsTextItem(
                                    title = "Equalizer",
                                    subtitle = "Adjust frequency bands",
                                    icon = Icons.Rounded.GraphicEq,
                                    onClick = onNavigateToEqualizer,
                                )
                            }
                        }
                    }

                    SettingsCategory.Library -> {
                        item {
                            var showDelimiterDialog by remember { mutableStateOf(false) }
                            var showIncludedDialog by remember { mutableStateOf(false) }
                            var showExcludedDialog by remember { mutableStateOf(false) }

                            SectionCard(icon = Icons.Rounded.LibraryMusic, title = "Library", modifier = Modifier.padding(horizontal = 8.dp)) {
                                ScanLibraryItem(isSyncing) {
                                    libraryViewModel.syncLibrary()
                                }
                                SettingsSliderItem(
                                    title = "Minimum Track Duration",
                                    value = (minDurationMs / 1000f),
                                    range = 0f..300f,
                                    label = if (minDurationMs == 0L) "No filter" else "${minDurationMs / 1000}s",
                                    steps = 30,
                                    onValueChange = { newValue ->
                                        val step = 10f
                                        val rounded = (newValue / step).roundToInt() * step
                                        scope.launch { prefs.setMinTrackDuration((rounded.toLong() * 1000).coerceAtLeast(0)) }
                                    },
                                )
                                SettingsToggleRow("Show Artwork in Lists", "Display album art thumbnails in song lists", showArtworkList) {
                                    scope.launch { prefs.setShowArtworkInList(it) }
                                }
                                SettingsToggleRow("Group by Album Artist", "Use album artist for grouping (not track artist)", groupByAlbumArtist) {
                                    scope.launch { prefs.setGroupByAlbumArtist(it) }
                                }
                                SettingsToggleRow("Show Filename as Title", "Fall back to filename when title tag is missing", showFilenameTitle) {
                                    scope.launch { prefs.setShowFilenameAsTitle(it) }
                                }
                                SettingsToggleRow("Ignore Articles in Sort", "Sort \"The Beatles\" as \"Beatles\"", ignoreArticles) {
                                    scope.launch { prefs.setIgnoreArticles(it) }
                                }
                                SettingsTextItem(
                                    title = "Artist Delimiter",
                                    subtitle = "Characters that split multi-artist tags: $artistDelimiter",
                                    icon = Icons.Rounded.People,
                                    onClick = { showDelimiterDialog = true },
                                )
                                SegmentedSettingsItem(
                                    title = "Auto Scan Interval",
                                    options = listOf("Off" to "0", "1 hr" to "1", "6 hrs" to "6", "24 hrs" to "24"),
                                    selected = autoScanHours.toString(),
                                    onSelect = { scope.launch { prefs.setAutoScanIntervalHours(it.toInt()) } },
                                )
                                SettingsTextItem(
                                    title = "Included Folders",
                                    subtitle = if (includedFolders.isEmpty()) "All folders (scan everything)"
                                               else "${includedFolders.size} folder${if (includedFolders.size != 1) "s" else ""} included",
                                    icon = Icons.Rounded.FolderOpen,
                                    onClick = { showIncludedDialog = true },
                                )
                                SettingsTextItem(
                                    title = "Excluded Folders",
                                    subtitle = if (excludedFolders.isEmpty()) "No folders excluded"
                                               else "${excludedFolders.size} folder${if (excludedFolders.size != 1) "s" else ""} excluded",
                                    icon = Icons.Rounded.FolderOff,
                                    onClick = { showExcludedDialog = true },
                                )
                            }

                            if (showDelimiterDialog) {
                                var delimiterInput by remember { mutableStateOf(artistDelimiter) }
                                AlertDialog(
                                    onDismissRequest = { showDelimiterDialog = false },
                                    title = { Text("Artist Delimiter") },
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                "Characters used to split multi-artist tags (e.g. \"Artist1, Artist2\"). " +
                                                        "Enter all split characters with no spaces between them.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            OutlinedTextField(
                                                value = delimiterInput,
                                                onValueChange = { delimiterInput = it },
                                                label = { Text("Delimiter characters") },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                scope.launch { prefs.setArtistDelimiter(delimiterInput) }
                                                showDelimiterDialog = false
                                            },
                                            enabled = delimiterInput.isNotBlank(),
                                        ) { Text("Save") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showDelimiterDialog = false }) { Text("Cancel") }
                                    },
                                )
                            }
                            if (showIncludedDialog) {
                                IncludedFoldersDialog(
                                    allFolders = allFolders,
                                    includedFolders = includedFolders,
                                    onSave = { folders ->
                                        scope.launch { prefs.setIncludedFolders(folders) }
                                        showIncludedDialog = false
                                    },
                                    onDismiss = { showIncludedDialog = false },
                                )
                            }
                            if (showExcludedDialog) {
                                ExcludedFoldersDialog(
                                    allFolders = allFolders,
                                    excludedFolders = excludedFolders,
                                    onSave = { folders ->
                                        scope.launch { prefs.setExcludedFolders(folders) }
                                        showExcludedDialog = false
                                    },
                                    onDismiss = { showExcludedDialog = false },
                                )
                            }
                        }
                    }

                    SettingsCategory.Navidrome -> {
                        item {
                            NavidromeSection(
                                currentSource = currentMusicSource,
                                savedServerUrl = navidromeServerUrl,
                                savedUsername = navidromeUsername,
                                connectionState = navidromeConnectionState,
                                syncState = navidromeSyncState,
                                onTest = { url, user, pass ->
                                    settingsViewModel.testNavidromeConnection(url, user, pass)
                                },
                                onSave = { url, user, pass ->
                                    settingsViewModel.saveNavidromeAndSwitch(url, user, pass)
                                },
                                onSwitchToLocal = { settingsViewModel.switchToLocal() },
                                onResetConnection = { settingsViewModel.resetNavidromeConnectionState() },
                                onResetSync = { settingsViewModel.resetNavidromeSyncState() },
                            )
                        }
                        if (currentMusicSource == MusicSource.NAVIDROME) {
                            item {
                                DownloadsSection(
                                    wifiOnly = downloadWifiOnly,
                                    onWifiOnlyChange = { settingsViewModel.setDownloadWifiOnly(it) },
                                    storageUsedBytes = downloadsStorageUsed,
                                    downloadedSongCount = downloadedSongCount,
                                    onDeleteAll = { settingsViewModel.removeAllDownloads() },
                                )
                            }
                        }
                    }

                    SettingsCategory.OnlineServices -> {
                        item {
                            SectionCard(icon = Icons.Rounded.Lyrics, title = "Lyrics & Artwork", modifier = Modifier.padding(horizontal = 8.dp)) {
                                SettingsToggleRow(
                                    title = "Fetch Lyrics",
                                    subtitle = "Auto-download time-synced lyrics from LRC Library",
                                    checked = fetchLyrics,
                                ) { scope.launch { prefs.setFetchLyrics(it) } }
                                SettingsToggleRow(
                                    title = "Fetch Artist Images",
                                    subtitle = "Load artist photos from Last.fm in the Artists list",
                                    checked = fetchArtistImages,
                                ) { scope.launch { prefs.setFetchArtistImages(it) } }
                                SettingsToggleRow(
                                    title = "Fetch Album Art",
                                    subtitle = "Search Spotify for a song's cover art when it's missing",
                                    checked = fetchAlbumArt,
                                ) { scope.launch { prefs.setFetchAlbumArt(it) } }
                            }
                        }

                        // No outer group label here (unlike the Main screen's category groups) —
                        // LastFmSection/MalojaSection each already self-identify via their own
                        // branded pill badge, so a floating "Last.fm Scrobbling" text above would
                        // just duplicate that, and every other sub-section on this screen already
                        // carries its own title inside a SectionCard instead of a separate label.
                        item { Spacer(Modifier.height(8.dp)) }
                        item {
                            LastFmSection(
                                authState = lastFmAuthState,
                                enabled = lastFmEnabled,
                                nowPlayingEnabled = lastFmNowPlaying,
                                scrobblePct = lastFmScrobblePct,
                                scrobbleMinSecs = lastFmScrobbleMinSecs,
                                onlyOnWifi = lastFmOnlyWifi,
                                offlineQueue = lastFmOfflineQueue,
                                pendingScrobbles = lastFmPending,
                                onEnabledChange = { settingsViewModel.setLastFmEnabled(it) },
                                onLogin = { u, p -> settingsViewModel.lastFmLogin(u, p) },
                                onLogout = { settingsViewModel.lastFmLogout() },
                                onNowPlayingChange = { settingsViewModel.setLastFmNowPlaying(it) },
                                onScrobblePctChange = { settingsViewModel.setLastFmScrobblePct(it) },
                                onScrobbleMinSecsChange = { settingsViewModel.setLastFmScrobbleMinSecs(it) },
                                onOnlyOnWifiChange = { settingsViewModel.setLastFmOnlyWifi(it) },
                                onOfflineQueueChange = { settingsViewModel.setLastFmOfflineQueue(it) },
                                onSnackbar = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                            )
                        }

                        item { Spacer(Modifier.height(8.dp)) }
                        item {
                            MalojaSection(
                                isConfigured = malojaServerUrl.isNotBlank(),
                                serverUrl = malojaServerUrl,
                                enabled = malojaEnabled,
                                testState = malojaTestState,
                                pendingScrobbles = malojaPending,
                                onSave = { url, key -> settingsViewModel.saveMalojaConfig(url, key) },
                                onClear = { settingsViewModel.clearMaloja() },
                                onEnabledChange = { settingsViewModel.setMalojaEnabled(it) },
                                onTest = { url, key -> settingsViewModel.testMalojaConnection(url, key) },
                                onResetTestState = { settingsViewModel.resetMalojaTestState() },
                                onSnackbar = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                            )
                        }

                        item {
                            SectionCard(icon = Icons.Rounded.Public, title = "Internet Share", modifier = Modifier.padding(horizontal = 8.dp)) {
                                RemoteShareSection(
                                    serverUrl = remoteShareServerUrl,
                                    uploadToken = remoteShareUploadToken,
                                    onSave = { url, token -> settingsViewModel.saveRemoteShareConfig(url, token) },
                                )
                            }
                        }
                    }

                    SettingsCategory.Notification -> {
                        item {
                            SectionCard(icon = Icons.Rounded.Notifications, title = "Notification & Lock Screen", modifier = Modifier.padding(horizontal = 8.dp)) {
                                SettingsToggleRow("Show Artwork on Lock Screen", "Display album art on lock screen controls", lockscreenArtwork) {
                                    scope.launch { prefs.setLockscreenArtwork(it) }
                                }
                                SettingsToggleRow("Show Skip Buttons", "Include previous/next in notification shade", showSkipButtons) {
                                    scope.launch { prefs.setShowSkipButtons(it) }
                                }
                            }
                        }
                    }

                    SettingsCategory.History -> {
                        item {
                            SectionCard(icon = Icons.Rounded.History, title = "Listening History", modifier = Modifier.padding(horizontal = 8.dp)) {
                                SettingsToggleRow("Track Play History", "Record which tracks you've listened to", historyEnabled) {
                                    scope.launch { prefs.setHistoryEnabled(it) }
                                }
                                if (historyEnabled) {
                                    SettingsSliderItem(
                                        title = "Minimum Listen Duration",
                                        value = minListenSecs.toFloat(),
                                        range = 10f..120f,
                                        label = "${minListenSecs}s",
                                        steps = 11,
                                        onValueChange = { newValue ->
                                            val step = 10f
                                            val rounded = (newValue / step).roundToInt() * step
                                            val finalSecs = rounded.toInt().coerceIn(10, 120)
                                            scope.launch { prefs.setListenThresholds(finalSecs, minListenPct) }
                                        },
                                    )
                                    SettingsSliderItem(
                                        title = "Minimum Listen Percentage",
                                        value = minListenPct,
                                        range = 0.1f..1.0f,
                                        label = "${(minListenPct * 100).roundToInt()}%",
                                        steps = 9,
                                        onValueChange = { newValue ->
                                            val step = 0.1f
                                            val rounded = (newValue / step).roundToInt() * step
                                            val finalPct = rounded.coerceIn(0.1f, 1.0f)
                                            scope.launch { prefs.setListenThresholds(minListenSecs, finalPct) }
                                        },
                                    )
                                    SettingsSliderItem(
                                        title = "Max History Items",
                                        value = maxHistory.toFloat(),
                                        range = 100f..5000f,
                                        label = "$maxHistory items",
                                        steps = 49,
                                        onValueChange = { newValue ->
                                            val step = 100f
                                            val rounded = (newValue / step).roundToInt() * step
                                            val final = rounded.toInt().coerceIn(100, 5000)
                                            scope.launch { prefs.setMaxHistoryItems(final) }
                                        },
                                    )
                                }
                            }
                        }
                    }

                    SettingsCategory.Data -> {
                        item {
                            val backupViewModel: BackupViewModel = hiltViewModel()
                            val backupState by backupViewModel.state.collectAsState()
                            val shareViewModel: ShareViewModel = hiltViewModel()
                            val diagState by shareViewModel.diagnosticState.collectAsState()
                            var showClearHistoryDialog by remember { mutableStateOf(false) }
                            val isClearingHistory by libraryViewModel.isClearingHistory.collectAsState()

                            val exportLauncher = rememberLauncherForActivityResult(
                                ActivityResultContracts.CreateDocument("application/json")
                            ) { uri -> uri?.let { backupViewModel.exportBackup(it) } }

                            val importLauncher = rememberLauncherForActivityResult(
                                ActivityResultContracts.OpenDocument()
                            ) { uri -> uri?.let { backupViewModel.importBackup(it) } }

                            SectionCard(icon = Icons.Rounded.Storage, title = "Data & Backup", modifier = Modifier.padding(horizontal = 8.dp)) {
                                SettingsTextItem(
                                    title = "Export Backup",
                                    subtitle = "Save liked songs and playlists to a file",
                                    icon = Icons.Rounded.FileUpload,
                                    onClick = {
                                        exportLauncher.launch("resonance_backup.json")
                                    }
                                )
                                SettingsTextItem(
                                    title = "Import Backup",
                                    subtitle = "Restore liked songs and playlists from a file",
                                    icon = Icons.Rounded.FileDownload,
                                    onClick = {
                                        importLauncher.launch(arrayOf("application/json", "*/*"))
                                    }
                                )

                                SectionDivider()
                                SectionSubHeader("Diagnostics")
                                SettingsTextItem(
                                    title = "Resonance Share Test",
                                    subtitle = "Verify Nearby and QR transfer systems",
                                    icon = Icons.Rounded.WifiTethering,
                                    onClick = { shareViewModel.runDiagnostics() }
                                )

                                SectionDivider()
                                SectionSubHeader("Danger Zone")
                                SettingsTextItem(
                                    title = "Clear Playback History",
                                    subtitle = if (isClearingHistory) "Clearing…" else "Permanently delete all history records",
                                    icon = Icons.Rounded.DeleteForever,
                                    tint = MaterialTheme.colorScheme.error,
                                    onClick = { if (!isClearingHistory) showClearHistoryDialog = true },
                                )
                            }

                            when (val s = backupState) {
                                is BackupUiState.Working -> {
                                    AlertDialog(
                                        onDismissRequest = {},
                                        title = { Text("Working…") },
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                                Spacer(Modifier.width(16.dp))
                                                Text("Please wait…")
                                            }
                                        },
                                        confirmButton = {},
                                    )
                                }
                                is BackupUiState.ExportSuccess, is BackupUiState.ImportSuccess -> {
                                    val msg = when (s) {
                                        is BackupUiState.ExportSuccess -> s.message
                                        is BackupUiState.ImportSuccess -> s.message
                                        else -> ""
                                    }
                                    AlertDialog(
                                        onDismissRequest = backupViewModel::dismiss,
                                        icon = { Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                                        title = { Text("Done") },
                                        text = { Text(msg) },
                                        confirmButton = {
                                            TextButton(onClick = backupViewModel::dismiss) { Text("OK") }
                                        },
                                    )
                                }
                                is BackupUiState.Error -> {
                                    AlertDialog(
                                        onDismissRequest = backupViewModel::dismiss,
                                        icon = { Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error) },
                                        title = { Text("Error") },
                                        text = { Text(s.message) },
                                        confirmButton = {
                                            TextButton(onClick = backupViewModel::dismiss) { Text("OK") }
                                        },
                                    )
                                }
                                else -> {}
                            }

                            if (diagState !is ShareViewModel.DiagnosticState.Idle) {
                                AlertDialog(
                                    onDismissRequest = { shareViewModel.clearDiagnostics() },
                                    title = { Text("Share Diagnostics") },
                                    text = {
                                        Column {
                                            when (val s = diagState) {
                                                is ShareViewModel.DiagnosticState.Running -> {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                                        Spacer(Modifier.width(16.dp))
                                                        Text("Testing connection...")
                                                    }
                                                }
                                                is ShareViewModel.DiagnosticState.Success -> {
                                                    Text(s.message, color = MaterialTheme.colorScheme.primary)
                                                }
                                                is ShareViewModel.DiagnosticState.Failure -> {
                                                    Text(s.message, color = MaterialTheme.colorScheme.error)
                                                }
                                                else -> {}
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = { shareViewModel.clearDiagnostics() }) {
                                            Text("Close")
                                        }
                                    }
                                )
                            }

                            if (showClearHistoryDialog) {
                                AlertDialog(
                                    onDismissRequest = { showClearHistoryDialog = false },
                                    icon = {
                                        Icon(
                                            Icons.Rounded.DeleteForever,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    title = { Text("Clear Playback History?") },
                                    text = {
                                        Text(
                                            "This will permanently delete all play counts and listen records. " +
                                                    "Smart Queue, Most Played, and Lost Memories will be reset. " +
                                                    "This cannot be undone.",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                showClearHistoryDialog = false
                                                libraryViewModel.clearHistory()
                                                scope.launch {
                                                    snackbarHostState.showSnackbar("Playback history cleared")
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error,
                                                contentColor = MaterialTheme.colorScheme.onError,
                                            ),
                                        ) {
                                            Text("Clear History")
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showClearHistoryDialog = false }) {
                                            Text("Cancel")
                                        }
                                    },
                                )
                            }
                        }
                    }

                    SettingsCategory.About -> {
                        item {
                            val uriHandler = LocalUriHandler.current
                            SectionCard(icon = Icons.Rounded.Info, title = "About", modifier = Modifier.padding(horizontal = 8.dp)) {
                                SettingsTextItem(
                                    title = "Resonance",
                                    subtitle = "v$APP_VERSION",
                                    icon = Icons.Rounded.MusicNote,
                                )
                                SettingsTextItem(
                                    title = "GitHub Repository",
                                    subtitle = "View source code and report issues",
                                    icon = Icons.Rounded.Code,
                                    trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                                    onClick = { uriHandler.openUri("https://github.com/yuw1xx/resonance-app") },
                                )
                                SettingsTextItem(
                                    title = "Resonance Website",
                                    subtitle = "Try the web client at resonance.yuwixx.dev",
                                    icon = Icons.Rounded.Language,
                                    trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                                    onClick = { uriHandler.openUri("https://resonance.yuwixx.dev") },
                                )
                                SettingsTextItem(
                                    title = "Join the Discord",
                                    subtitle = "Chat, get help, or become a tester",
                                    icon = Icons.Rounded.Forum,
                                    trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                                    onClick = { uriHandler.openUri("https://discord.gg/SftqvvveMj") },
                                )
                                SettingsTextItem(
                                    title = "Support on Ko-fi",
                                    subtitle = "Buy me a coffee if Resonance's been useful",
                                    icon = Icons.Rounded.Coffee,
                                    trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                                    onClick = { uriHandler.openUri("https://ko-fi.com/yuwixx") },
                                )
                                SettingsTextItem(
                                    title = "App License",
                                    subtitle = "View Resonance's open source license",
                                    icon = Icons.Rounded.Gavel,
                                    trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                                    onClick = { uriHandler.openUri("https://github.com/yuw1xx/resonance-app/blob/main/LICENSE") },
                                )
                                SettingsTextItem(
                                    title = "Third-Party Licenses",
                                    subtitle = "Open source libraries used in this project",
                                    icon = Icons.Rounded.Description,
                                    onClick = onNavigateToLicenses,
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
private fun LastFmSection(
    authState: LastFmAuthState,
    enabled: Boolean,
    nowPlayingEnabled: Boolean,
    scrobblePct: Float,
    scrobbleMinSecs: Int,
    onlyOnWifi: Boolean,
    offlineQueue: Boolean,
    pendingScrobbles: Int,
    onEnabledChange: (Boolean) -> Unit,
    onLogin: (String, String) -> Unit,
    onLogout: () -> Unit,
    onNowPlayingChange: (Boolean) -> Unit,
    onScrobblePctChange: (Float) -> Unit,
    onScrobbleMinSecsChange: (Int) -> Unit,
    onOnlyOnWifiChange: (Boolean) -> Unit,
    onOfflineQueueChange: (Boolean) -> Unit,
    onSnackbar: (String) -> Unit,
) {
    var showLoginDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = when (authState) {
                    is LastFmAuthState.Authenticated -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    is LastFmAuthState.Error         -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    else                             -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFD51007),
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Text(
                            "Last.fm",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }

                    when (authState) {
                        is LastFmAuthState.Authenticated -> {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    authState.username,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${authState.playCount} scrobbles",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = onEnabledChange,
                            )
                        }
                        is LastFmAuthState.Loading -> {
                            Text("Signing in…", modifier = Modifier.weight(1f))
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                        is LastFmAuthState.Error -> {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sign-in failed", style = MaterialTheme.typography.titleSmall)
                                Text(authState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        LastFmAuthState.Idle -> {
                            Text(
                                "Not signed in",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                when (authState) {
                    is LastFmAuthState.Authenticated -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (pendingScrobbles > 0) {
                                AssistChip(
                                    onClick = { onSnackbar("$pendingScrobbles scrobbles pending sync") },
                                    label = { Text("$pendingScrobbles pending") },
                                    leadingIcon = { Icon(Icons.Rounded.Sync, null, modifier = Modifier.size(16.dp)) },
                                )
                            }
                            OutlinedButton(onClick = onLogout, modifier = Modifier.weight(1f)) {
                                Text("Sign Out")
                            }
                        }
                    }
                    is LastFmAuthState.Error -> {
                        Button(onClick = { showLoginDialog = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Try Again")
                        }
                    }
                    else -> {
                        Button(onClick = { showLoginDialog = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.AutoMirrored.Rounded.Login, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Sign In to Last.fm")
                        }
                    }
                }
            }
        }

        if (authState is LastFmAuthState.Authenticated && enabled) {
            Spacer(Modifier.height(12.dp))

            SettingsToggleCompact("Now Playing", "Send 'Now Playing' status when a track starts", nowPlayingEnabled, onNowPlayingChange)
            SettingsToggleCompact("Scrobble Only on Wi-Fi", "Save mobile data by queuing scrobbles until on Wi-Fi", onlyOnWifi, onOnlyOnWifiChange)
            SettingsToggleCompact("Offline Scrobble Queue", "Buffer scrobbles when offline and submit later", offlineQueue, onOfflineQueueChange)

            Spacer(Modifier.height(4.dp))

            SettingsSliderItem(
                title = "Scrobble After",
                value = scrobbleMinSecs.toFloat(),
                range = 10f..120f,
                label = "${scrobbleMinSecs}s",
                steps = 11,
                onValueChange = { onScrobbleMinSecsChange(it.toInt()) },
                compact = true,
            )
            SettingsSliderItem(
                title = "Scrobble at % of track",
                value = scrobblePct,
                range = 0.25f..1.0f,
                label = "${(scrobblePct * 100).roundToInt()}%",
                steps = 3,
                onValueChange = onScrobblePctChange,
                compact = true,
            )
        }
    }

    if (showLoginDialog) {
        LastFmLoginDialog(
            onDismiss = { showLoginDialog = false },
            onLogin = { u, p ->
                onLogin(u, p)
                showLoginDialog = false
            },
        )
    }
}

@Composable
private fun LastFmLoginDialog(
    onDismiss: () -> Unit,
    onLogin: (String, String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFD51007)) {
                Text("Last.fm", fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        },
        title = { Text("Sign In to Last.fm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Your password is sent securely to Last.fm and never stored by Resonance. " +
                            "Only the session token is saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onLogin(username.trim(), password) },
                enabled = username.isNotBlank() && password.isNotBlank(),
            ) {
                Text("Sign In")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RemoteShareSection(
    serverUrl: String,
    uploadToken: String,
    onSave: (String, String) -> Unit,
) {
    var url by remember(serverUrl) { mutableStateOf(serverUrl) }
    var token by remember(uploadToken) { mutableStateOf(uploadToken) }
    var tokenVisible by remember { mutableStateOf(false) }
    val isConfigured = serverUrl.isNotBlank() && uploadToken.isNotBlank()
    val isDirty = url.trim() != serverUrl || token != uploadToken

    Column {
        Text(
            "\"Share over the Internet\" works out of the box using Resonance's default relay server — " +
                "no setup needed. If you'd rather use your own self-hosted server (e.g. behind a Cloudflare " +
                "Tunnel), enter its details below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Custom server URL (optional)") },
            placeholder = { Text("https://share.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Upload token (optional)") },
            singleLine = true,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                    Icon(if (tokenVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(url.trim(), token) },
                enabled = isDirty,
                shape = MaterialTheme.shapes.medium,
            ) { Text("Save") }
            Text(
                if (isConfigured) "Using your custom server" else "Using default server",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterVertically).padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun MalojaSection(
    isConfigured: Boolean,
    serverUrl: String,
    enabled: Boolean,
    testState: SettingsViewModel.MalojaTestState,
    pendingScrobbles: Int,
    onSave: (String, String) -> Unit,
    onClear: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onTest: (String, String) -> Unit,
    onResetTestState: () -> Unit,
    onSnackbar: (String) -> Unit,
) {
    var showSetupDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isConfigured && enabled -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1A8C4E),
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Text(
                            "Maloja",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }

                    if (isConfigured) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                serverUrl,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "Self-hosted scrobbling",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = onEnabledChange)
                    } else {
                        Text(
                            "Not configured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (isConfigured) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (pendingScrobbles > 0) {
                            AssistChip(
                                onClick = { onSnackbar("$pendingScrobbles scrobbles pending sync") },
                                label = { Text("$pendingScrobbles pending") },
                                leadingIcon = { Icon(Icons.Rounded.Sync, null, modifier = Modifier.size(16.dp)) },
                            )
                        }
                        OutlinedButton(
                            onClick = { showSetupDialog = true },
                            modifier = Modifier.weight(1f),
                        ) { Text("Edit") }
                        OutlinedButton(onClick = onClear) {
                            Icon(Icons.Rounded.LinkOff, null, modifier = Modifier.size(18.dp))
                        }
                    }
                } else {
                    Button(onClick = { showSetupDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Link, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Connect to Maloja")
                    }
                }
            }
        }
    }

    if (showSetupDialog) {
        MalojaSetupDialog(
            initialUrl = serverUrl,
            testState = testState,
            onDismiss = {
                showSetupDialog = false
                onResetTestState()
            },
            onSave = { url, key ->
                onSave(url, key)
                showSetupDialog = false
                onResetTestState()
            },
            onTest = onTest,
        )
    }
}

@Composable
private fun MalojaSetupDialog(
    initialUrl: String,
    testState: SettingsViewModel.MalojaTestState,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onTest: (String, String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF1A8C4E)) {
                Text("Maloja", fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        },
        title = { Text("Connect to Maloja") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter your Maloja server URL and an API key from your server's settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://192.168.1.100:42010") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showKey) "Hide key" else "Show key",
                            )
                        }
                    },
                )
                when (testState) {
                    is SettingsViewModel.MalojaTestState.Loading ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Testing…", style = MaterialTheme.typography.bodySmall)
                        }
                    is SettingsViewModel.MalojaTestState.Success ->
                        Text("Connected: ${testState.desc}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    is SettingsViewModel.MalojaTestState.Error ->
                        Text("Error: ${testState.message}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    else -> {}
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(url.trim(), apiKey.trim()) },
                enabled = url.isNotBlank() && apiKey.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onTest(url.trim(), apiKey.trim()) },
                    enabled = url.isNotBlank() && apiKey.isNotBlank() &&
                            testState !is SettingsViewModel.MalojaTestState.Loading,
                ) { Text("Test") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ThemeColorPicker(
    current: Int?,
    onPick: (Int) -> Unit,
) {
    val colors = PresetColors
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "Accent Colour",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            colors.forEach { color ->
                val selected = current == color.toArgb()
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable { onPick(color.toArgb()) },
                ) {
                    if (selected) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentedSettingsItem(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (label, value) ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    onClick = { onSelect(value) },
                    selected = selected == value,
                    label = { Text(label, fontSize = 13.sp) },
                )
            }
        }
    }
}

@Composable
private fun SettingsSliderItem(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
    compact: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (compact) 2.dp else 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.padding(top = 0.dp),
        )
    }
}

@Composable
private fun SettingsToggleCompact(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) },
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onToggle)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun SettingsTextItem(
    title: String,
    subtitle: String = "",
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit = {},
) {
    ListItem(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        leadingContent = icon?.let {
            { Icon(it, null, tint = tint, modifier = Modifier.size(24.dp)) }
        },
        headlineContent = {
            Text(
                title,
                fontWeight = FontWeight.Medium,
                color = if (tint != MaterialTheme.colorScheme.onSurfaceVariant) tint else Color.Unspecified,
            )
        },
        supportingContent = if (subtitle.isNotEmpty()) {
            { Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null,
        trailingContent = trailingIcon?.let {
            { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun ScanLibraryItem(isSyncing: Boolean, onScan: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onScan),
        headlineContent = { Text("Scan for Music") },
        supportingContent = {
            AnimatedContent(
                targetState = isSyncing,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "sync_text"
            ) { syncing ->
                Text(
                    if (syncing) "Scanning your device…" else "Search device for new audio files",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            AnimatedContent(
                targetState = isSyncing,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "sync_icon"
            ) { syncing ->
                if (syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Rounded.Refresh, null)
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private fun LazyListScope.settingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    item { SettingsToggleRow(title, subtitle, checked, onToggle) }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onToggle(!checked) },
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onToggle)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun NavidromeSection(
    currentSource: MusicSource,
    savedServerUrl: String?,
    savedUsername: String?,
    connectionState: NavidromeConnectionState,
    syncState: NavidromeSyncState,
    onTest: (String, String, String) -> Unit,
    onSave: (String, String, String) -> Unit,
    onSwitchToLocal: () -> Unit,
    onResetConnection: () -> Unit,
    onResetSync: () -> Unit,
) {
    var showSetupScreen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "Active Source",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        SourceRow(
            title = "Local Library",
            subtitle = "Music stored on this device",
            icon = Icons.Rounded.PhoneAndroid,
            selected = currentSource == MusicSource.LOCAL,
            onClick = { if (currentSource != MusicSource.LOCAL) onSwitchToLocal() }
        )

        Spacer(Modifier.height(8.dp))

        SourceRow(
            title = "Navidrome",
            subtitle = if (currentSource == MusicSource.NAVIDROME && savedServerUrl != null)
                savedServerUrl else "Stream from your self-hosted server",
            icon = Icons.Rounded.Cloud,
            selected = currentSource == MusicSource.NAVIDROME,
            onClick = { showSetupScreen = true }
        )

        if (currentSource == MusicSource.NAVIDROME) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { showSetupScreen = true }) {
                Icon(Icons.Rounded.Edit, null, modifier = Modifier.padding(end = 6.dp).size(16.dp))
                Text("Configure Server")
            }
        }
    }

    if (showSetupScreen) {
        NavidromeSetupScreen(
            savedServerUrl = savedServerUrl,
            savedUsername = savedUsername,
            connectionState = connectionState,
            syncState = syncState,
            onTest = onTest,
            onSave = onSave,
            onDismiss = {
                showSetupScreen = false
                onResetConnection()
                onResetSync()
            },
        )
    }
}

@Composable
private fun DownloadsSection(
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    storageUsedBytes: Long,
    downloadedSongCount: Int,
    onDeleteAll: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    SectionCard(icon = Icons.Rounded.Download, title = "Offline Downloads", modifier = Modifier.padding(horizontal = 8.dp)) {
        SettingsToggleRow(
            title = "Wi-Fi Only",
            subtitle = "Only download songs while connected to Wi-Fi",
            checked = wifiOnly,
            onToggle = onWifiOnlyChange,
        )
        ListItem(
            modifier = Modifier.padding(horizontal = 8.dp),
            headlineContent = { Text("Storage Used", fontWeight = FontWeight.Medium) },
            supportingContent = {
                Text(
                    "$downloadedSongCount song${if (downloadedSongCount == 1) "" else "s"} · ${formatDownloadSize(storageUsedBytes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        if (downloadedSongCount > 0) {
            TextButton(
                onClick = { showDeleteConfirm = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Icon(Icons.Rounded.DeleteSweep, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Delete All Downloads")
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete all downloads?") },
            text = { Text("This removes every downloaded song from local storage. You can re-download them any time while connected.") },
            confirmButton = {
                Button(
                    onClick = { onDeleteAll(); showDeleteConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete All") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

private fun formatDownloadSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L         -> "%.0f KB".format(bytes / 1_024.0)
    else                    -> "$bytes B"
}

@Composable
private fun SourceRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    icon, null,
                    modifier = Modifier.padding(10.dp),
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (selected) {
                Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun NavidromeSetupScreen(
    savedServerUrl: String?,
    savedUsername: String?,
    connectionState: NavidromeConnectionState,
    syncState: NavidromeSyncState,
    onTest: (String, String, String) -> Unit,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Flip immediately when user confirms — don't derive from syncState (which resets to Idle
    // momentarily before the new sync starts, causing the page to flash back to config).
    var showSyncPage by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { if (syncState !is NavidromeSyncState.Syncing) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .imePadding(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            AnimatedContent(
                targetState = showSyncPage,
                transitionSpec = {
                    (slideInHorizontally(tween(500, easing = EmphasizedDecelerate)) { it } +
                        fadeIn(tween(500, delayMillis = 50, easing = EmphasizedDecelerate)))
                        .togetherWith(
                            slideOutHorizontally(tween(400, easing = EmphasizedAccelerate)) { -it } +
                                fadeOut(tween(200, easing = EmphasizedAccelerate))
                        )
                },
                label = "navidrome-setup-step",
            ) { isSyncPage ->
                if (isSyncPage) {
                    NavidromeSyncPage(syncState = syncState, onDone = onDismiss)
                } else {
                    NavidromeConfigPage(
                        savedServerUrl = savedServerUrl,
                        savedUsername = savedUsername,
                        connectionState = connectionState,
                        onTest = onTest,
                        onSave = { url, user, pass ->
                            showSyncPage = true
                            onSave(url, user, pass)
                        },
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun NavidromeConfigPage(
    savedServerUrl: String?,
    savedUsername: String?,
    connectionState: NavidromeConnectionState,
    onTest: (String, String, String) -> Unit,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var serverUrl by remember { mutableStateOf(savedServerUrl ?: "") }
    var username by remember { mutableStateOf(savedUsername ?: "") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val isEditing = savedServerUrl != null

    Column(
        modifier = Modifier
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            Icons.Rounded.Cloud, null,
            modifier = Modifier.align(Alignment.CenterHorizontally).size(40.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            if (isEditing) "Configure Navidrome" else "Set Up Navidrome",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            if (isEditing) "Update your server connection settings."
            else "Connect to your self-hosted Navidrome server to stream your music library.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(4.dp))

        OutlinedTextField(
            value = serverUrl, onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            placeholder = { Text("https://music.example.com") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            leadingIcon = { Icon(Icons.Rounded.Language, null) },
        )
        OutlinedTextField(
            value = username, onValueChange = { username = it },
            label = { Text("Username") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Rounded.Person, null) },
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") },
            placeholder = { Text(if (isEditing) "Leave blank to keep existing" else "") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Rounded.Lock, null) },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
                }
            },
        )

        when (connectionState) {
            is NavidromeConnectionState.Connecting -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Connecting…", style = MaterialTheme.typography.bodySmall)
            }
            is NavidromeConnectionState.Connected -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text("Connected!", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            is NavidromeConnectionState.Error -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Text(connectionState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            else -> Unit
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            // For existing connections with blank password: skip test — ViewModel uses stored password.
            val canSaveDirect = isEditing && password.isBlank() && serverUrl.isNotBlank() && username.isNotBlank()
            if (connectionState is NavidromeConnectionState.Connected || canSaveDirect) {
                Button(onClick = { onSave(serverUrl, username, password) }) {
                    Icon(Icons.Rounded.Sync, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Connect & Sync")
                }
            } else {
                Button(
                    onClick = { onTest(serverUrl, username, password) },
                    enabled = serverUrl.isNotBlank() && username.isNotBlank() &&
                            password.isNotBlank() &&
                            connectionState !is NavidromeConnectionState.Connecting,
                ) {
                    Text("Test Connection")
                }
            }
        }
        Spacer(Modifier.height(0.dp))
    }
}

// Not private: reused by SetupScreen.kt's onboarding NavidromeStep as well.
@Composable
fun NavidromeSyncPage(
    syncState: NavidromeSyncState,
    onDone: () -> Unit,
) {
    val isDone = syncState is NavidromeSyncState.Done || syncState is NavidromeSyncState.Error

    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(syncState is NavidromeSyncState.Syncing) {
        if (syncState is NavidromeSyncState.Syncing) {
            elapsedSeconds = 0L
            while (true) {
                delay(1_000)
                elapsedSeconds++
            }
        }
    }

    val songsDoneNow  = (syncState as? NavidromeSyncState.Syncing)?.songsDone      ?: 0
    val estimatedTotal = (syncState as? NavidromeSyncState.Syncing)?.estimatedTotal ?: 0

    val syncProgress = if (estimatedTotal > 0 && songsDoneNow > 0)
        (songsDoneNow.toFloat() / estimatedTotal).coerceIn(0f, 0.99f) else null

    val etaText: String? = if (elapsedSeconds > 5 && songsDoneNow > 0 && estimatedTotal > songsDoneNow) {
        val etaSecs = (elapsedSeconds * (estimatedTotal - songsDoneNow).toFloat() / songsDoneNow).toLong()
        when {
            etaSecs < 60   -> "Less than a minute remaining"
            etaSecs < 3600 -> "~${etaSecs / 60} min remaining"
            else           -> "~${etaSecs / 3600}h ${(etaSecs % 3600) / 60}m remaining"
        }
    } else null

    val syncRotation by rememberInfiniteTransition(label = "sync-rotate")
        .animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
            label = "rotation",
        )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        when (syncState) {
            is NavidromeSyncState.Done -> Icon(
                Icons.Rounded.CheckCircle, null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            is NavidromeSyncState.Error -> Icon(
                Icons.Rounded.Error, null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            else -> Icon(
                Icons.Rounded.Sync, null,
                modifier = Modifier.size(64.dp).graphicsLayer { rotationZ = syncRotation },
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Text(
            text = when (syncState) {
                is NavidromeSyncState.Done  -> "Library Ready!"
                is NavidromeSyncState.Error -> "Sync Failed"
                else -> "Syncing Library…"
            },
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        if (!isDone) {
            if (syncProgress != null) {
                LinearProgressIndicator(progress = { syncProgress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        when (syncState) {
            is NavidromeSyncState.Syncing -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val countText = if (syncState.albumsDone > 0 || syncState.songsDone > 0)
                    "${syncState.albumsDone} albums · ${syncState.songsDone} songs found"
                else "Starting…"
                Text(countText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (etaText != null) {
                    Text(
                        etaText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Info, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(
                            "Keep Resonance open while syncing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            is NavidromeSyncState.Done -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "${syncState.albumCount} albums · ${syncState.songCount} songs",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Your library is ready to play!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is NavidromeSyncState.Error -> Text(
                syncState.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            NavidromeSyncState.Idle -> Text(
                "Preparing…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (isDone) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun IncludedFoldersDialog(
    allFolders: List<String>,
    includedFolders: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(includedFolders) { mutableStateOf(includedFolders.toMutableSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.FolderOpen, null) },
        title = { Text("Included Folders") },
        text = {
            if (allFolders.isEmpty()) {
                Text(
                    "No folders found. Scan your library first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Only songs from checked folders will appear in your library. Leave all unchecked to include everything.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    allFolders.forEach { folder ->
                        val isIncluded = selected.contains(folder)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable {
                                    selected = if (isIncluded) {
                                        (selected - folder).toMutableSet()
                                    } else {
                                        (selected + folder).toMutableSet()
                                    }
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Checkbox(checked = isIncluded, onCheckedChange = null)
                            Text(
                                text = folder.substringAfterLast('/').ifBlank { folder },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(selected) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ExcludedFoldersDialog(
    allFolders: List<String>,
    excludedFolders: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(excludedFolders) { mutableStateOf(excludedFolders.toMutableSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.FolderOff, null) },
        title = { Text("Excluded Folders") },
        text = {
            if (allFolders.isEmpty()) {
                Text(
                    "No folders found. Scan your library first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Songs in excluded folders will be hidden from your library.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    allFolders.forEach { folder ->
                        val isExcluded = selected.contains(folder)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable {
                                    selected = if (isExcluded) {
                                        (selected - folder).toMutableSet()
                                    } else {
                                        (selected + folder).toMutableSet()
                                    }
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Checkbox(
                                checked = isExcluded,
                                onCheckedChange = null,
                            )
                            Text(
                                text = folder.substringAfterLast('/').ifBlank { folder },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(selected) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun NavTabsDialog(
    hiddenNavTabs: Set<String>,
    onToggle: (route: String, hide: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val visibleNonPinnedCount = navItems.count { item ->
        item != navItems.first() && item.screen.route !in hiddenNavTabs
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Tab, null) },
        title = { Text("Navigation Bar Tabs") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Choose which tabs appear at the bottom. Home is always shown and at least one other tab must remain visible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                navItems.forEach { item ->
                    val isPinned  = item == navItems.first()
                    val isVisible = item.screen.route !in hiddenNavTabs
                    val canHide   = !isPinned && isVisible && visibleNonPinnedCount > 1

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .then(
                                if (!isPinned && (!isVisible || canHide))
                                    Modifier.clickable { onToggle(item.screen.route, isVisible) }
                                else Modifier
                            )
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment   = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = if (isPinned || isVisible) MaterialTheme.colorScheme.onSurface
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            item.label,
                            style    = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            color    = if (isPinned || isVisible) Color.Unspecified
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                        if (isPinned) {
                            Text(
                                "Always shown",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Switch(
                                checked         = isVisible,
                                onCheckedChange = if (!isVisible || canHide) {
                                    { checked -> onToggle(item.screen.route, !checked) }
                                } else null,
                                enabled = !isVisible || canHide,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
