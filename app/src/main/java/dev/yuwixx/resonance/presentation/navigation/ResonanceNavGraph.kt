package dev.yuwixx.resonance.presentation.navigation

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import dev.yuwixx.resonance.data.model.MusicSource
import dev.yuwixx.resonance.presentation.components.AppearanceConfig
import dev.yuwixx.resonance.presentation.components.LocalAppearanceConfig
import dev.yuwixx.resonance.presentation.components.LocalPlaybackActive
import dev.yuwixx.resonance.presentation.components.PermissionWrapper
import dev.yuwixx.resonance.presentation.components.rememberHaptics
import dev.yuwixx.resonance.presentation.player.MiniPlayerBar
import dev.yuwixx.resonance.presentation.player.MiniPlayerMetrics
import dev.yuwixx.resonance.presentation.player.PlayerSheet
import dev.yuwixx.resonance.presentation.player.PlayerSheetValue
import dev.yuwixx.resonance.presentation.player.rememberPlayerSheetState
import dev.yuwixx.resonance.presentation.screens.*
import dev.yuwixx.resonance.presentation.screens.library.LibraryScreen
import dev.yuwixx.resonance.presentation.screens.library.LibrarySection
import dev.yuwixx.resonance.presentation.viewmodel.LibraryViewModel
import dev.yuwixx.resonance.presentation.viewmodel.PlayerViewModel
import dev.yuwixx.resonance.presentation.viewmodel.SettingsViewModel
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

sealed class Screen(val route: String) {
    data object Setup : Screen("setup")
    data object Home : Screen("home")
    data object Library : Screen("library")
    data object Playlists : Screen("playlists")
    data object Search : Screen("search")
    data object Settings : Screen("settings")
    data object Licenses : Screen("licenses")
    data object LyricsEditor : Screen("lyrics_editor")
    data object Statistics : Screen("statistics")
    data object Equalizer : Screen("equalizer")
    data object MetadataEnrichment : Screen("metadata_enrichment")

    data object TagEditor : Screen("tag_editor/{songId}") {
        fun createRoute(songId: Long) = "tag_editor/$songId"
    }

    data object AlbumDetail : Screen("album_detail/{albumId}") {
        fun createRoute(albumId: Long) = "album_detail/$albumId"
    }
    data object ArtistDetail : Screen("artist_detail/{artistName}") {
        fun createRoute(artistName: String) = "artist_detail/${URLEncoder.encode(artistName, "UTF-8")}"
    }
    data object PlaylistDetail : Screen("playlist_detail/{playlistId}") {
        fun createRoute(playlistId: Long) = "playlist_detail/$playlistId"
    }
    data object SongDetail : Screen("song_detail/{songId}") {
        fun createRoute(songId: Long) = "song_detail/$songId"
    }
}

data class NavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
)

val navItems = listOf(
    NavItem(Screen.Home,      "Home",      Icons.Outlined.Home,                  Icons.Rounded.Home),
    NavItem(Screen.Library,   "Library",   Icons.Outlined.LibraryMusic,          Icons.Rounded.LibraryMusic),
    NavItem(Screen.Playlists, "Playlists", Icons.AutoMirrored.Outlined.QueueMusic, Icons.AutoMirrored.Rounded.QueueMusic),
    NavItem(Screen.Search,    "Search",    Icons.Outlined.Search,                Icons.Rounded.Search),
)

private fun isTab(route: String?) = navItems.any { it.screen.route == route }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResonanceNavGraph(
    playerViewModel: PlayerViewModel,
    receiveUri: Uri? = null,
    onReceiveDismiss: () -> Unit = {},
) {
    val navController = rememberNavController()
    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val isFirstRun by libraryViewModel.prefs.isFirstRun.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val musicSource by settingsViewModel.musicSource.collectAsStateWithLifecycle()

    val currentSong by playerViewModel.currentSong.collectAsStateWithLifecycle()
    val isPlaying by playerViewModel.isPlaying.collectAsStateWithLifecycle()
    val allPlaylists by libraryViewModel.allPlaylists.collectAsStateWithLifecycle()

    val miniPlayerStyle by playerViewModel.miniPlayerStyle.collectAsStateWithLifecycle()

    val hiddenNavTabs by libraryViewModel.prefs.hiddenNavTabs.collectAsStateWithLifecycle(initialValue = emptySet())
    val visibleNavItems = remember(hiddenNavTabs) {
        navItems.filter { it.screen.route !in hiddenNavTabs }
    }

    val compactList by libraryViewModel.prefs.compactListMode.collectAsStateWithLifecycle(initialValue = false)
    val showDuration by libraryViewModel.prefs.showDurationInList.collectAsStateWithLifecycle(initialValue = false)
    val artworkShape by libraryViewModel.prefs.playerArtworkShape.collectAsStateWithLifecycle(initialValue = "ROUNDED")
    val lyricAlign by libraryViewModel.prefs.lyricAlignment.collectAsStateWithLifecycle(initialValue = "CENTER")
    val seekbarClr by libraryViewModel.prefs.seekbarColor.collectAsStateWithLifecycle(initialValue = "PRIMARY")
    val tintedNav by libraryViewModel.prefs.tintedNavBar.collectAsStateWithLifecycle(initialValue = false)
    val floatingNav by libraryViewModel.prefs.floatingNavBar.collectAsStateWithLifecycle(initialValue = false)
    val navLabelVis by libraryViewModel.prefs.navLabelVisibility.collectAsStateWithLifecycle(initialValue = "ALWAYS")
    val playlistCols by libraryViewModel.prefs.playlistGridColumns.collectAsStateWithLifecycle(initialValue = 2)
    val showAlbumInList by libraryViewModel.prefs.showAlbumInList.collectAsStateWithLifecycle(initialValue = false)
    val listArtworkSize by libraryViewModel.prefs.listArtworkSize.collectAsStateWithLifecycle(initialValue = "MEDIUM")
    val showRemainingTime by libraryViewModel.prefs.showRemainingTime.collectAsStateWithLifecycle(initialValue = false)
    val showNextSongInPlayer by libraryViewModel.prefs.showNextSongInPlayer.collectAsStateWithLifecycle(initialValue = false)
    val lyricsLineSpacing by libraryViewModel.prefs.lyricsLineSpacing.collectAsStateWithLifecycle(initialValue = "NORMAL")
    val miniPlayerShowProgress by libraryViewModel.prefs.miniPlayerShowProgress.collectAsStateWithLifecycle(initialValue = true)
    val miniPlayerShowSkipBtn by libraryViewModel.prefs.miniPlayerShowSkipBtn.collectAsStateWithLifecycle(initialValue = true)
    val showEqualizerInPlayer by libraryViewModel.prefs.showEqualizerInPlayer.collectAsStateWithLifecycle(initialValue = true)
    val hapticEnabled by libraryViewModel.prefs.hapticFeedback.collectAsStateWithLifecycle(initialValue = true)
    val showBitrateInfo by libraryViewModel.prefs.showBitrateInfo.collectAsStateWithLifecycle(initialValue = false)
    val lyricsFontFamily by libraryViewModel.prefs.lyricsFontFamily.collectAsStateWithLifecycle(initialValue = "DEFAULT")

    val appearanceConfig = remember(
        compactList, showDuration, artworkShape, lyricAlign, seekbarClr, tintedNav, floatingNav,
        navLabelVis, playlistCols, showAlbumInList, listArtworkSize, showRemainingTime,
        showNextSongInPlayer, lyricsLineSpacing, miniPlayerShowProgress, miniPlayerShowSkipBtn,
        showEqualizerInPlayer, hapticEnabled, showBitrateInfo, lyricsFontFamily,
    ) {
        AppearanceConfig(
            compactListMode = compactList,
            showDurationInList = showDuration,
            playerArtworkShape = artworkShape,
            lyricAlignment = lyricAlign,
            seekbarColor = seekbarClr,
            tintedNavBar = tintedNav,
            floatingNavBar = floatingNav,
            navLabelVisibility = navLabelVis,
            playlistGridColumns = playlistCols,
            showAlbumInList = showAlbumInList,
            listArtworkSize = listArtworkSize,
            showRemainingTime = showRemainingTime,
            showNextSongInPlayer = showNextSongInPlayer,
            lyricsLineSpacing = lyricsLineSpacing,
            miniPlayerShowProgress = miniPlayerShowProgress,
            miniPlayerShowSkipBtn = miniPlayerShowSkipBtn,
            showEqualizerInPlayer = showEqualizerInPlayer,
            hapticEnabled = hapticEnabled,
            showBitrateInfo = showBitrateInfo,
            lyricsFontFamily = lyricsFontFamily,
        )
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route
    // Hide the bottom bar and player sheet entirely while setup is open, and while the
    // keyboard is up (they'd otherwise sit on top of it).
    val isSetupRoute = currentRoute == Screen.Setup.route
    val imeVisible = WindowInsets.isImeVisible

    // "Open the player" expands the Now Playing sheet.
    val sheetState = rememberPlayerSheetState()
    // While the full player covers the screen, what's behind it must not be reachable with TalkBack.
    val playerCoversScreen by remember(sheetState) {
        derivedStateOf { sheetState.currentValue == PlayerSheetValue.Expanded }
    }
    val miniMetrics = remember(miniPlayerStyle, miniPlayerShowProgress) {
        MiniPlayerMetrics.forStyle(miniPlayerStyle, showProgress = miniPlayerShowProgress)
    }
    val showSheet = currentSong != null && !isSetupRoute && isFirstRun == false && !imeVisible
    val behindPlayer = if (playerCoversScreen && showSheet) Modifier.clearAndSetSemantics {} else Modifier
    val navigateToPlayer: () -> Unit = { scope.launch { sheetState.expand() } }
    // Screens reached from inside the player open beneath it, so collapse first.
    val navigateFromPlayer: (String) -> Unit = { route ->
        scope.launch { sheetState.collapse() }
        navController.navigate(route)
    }
    LaunchedEffect(showSheet) {
        if (!showSheet && sheetState.currentValue == PlayerSheetValue.Expanded) sheetState.collapse()
    }
    val miniPlayerPadding = if (showSheet) miniMetrics.totalHeight else 0.dp

    // Top-level destinations keep their own back stacks, like M3 navigation bars expect.
    val navigateToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(visibleNavItems, currentRoute) {
        // Search stays reachable from the search buttons even when its tab is hidden.
        val onHiddenTab = currentRoute != null
            && currentRoute != Screen.Search.route
            && navItems.any { it.screen.route == currentRoute }
            && visibleNavItems.none { it.screen.route == currentRoute }
        if (onHiddenTab) navigateToTab(Screen.Home.route)
    }

    val motion = MaterialTheme.motionScheme
    val density = LocalDensity.current
    val showBottomBar = !isSetupRoute && isFirstRun == false && !imeVisible
    // Height of the bottom bar including the system navigation inset, known up front so the
    // collapsed sheet and the content padding are right from the first frame.
    val navInsetBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarHeight = if (!showBottomBar) 0.dp
        else if (floatingNav) FloatingNavHeight + FloatingNavVerticalMargin * 2 + navInsetBottom
        else ShortNavHeight + navInsetBottom
    val bottomBarHeightPx = with(density) { bottomBarHeight.roundToPx() }
    // Effects spec, not spatial: padding must not overshoot (a bouncy spring went below
    // zero when the keyboard hid the bar, and negative padding crashes).
    val animatedBottomPadding by animateDpAsState(
        targetValue = bottomBarHeight + miniPlayerPadding,
        animationSpec = motion.defaultEffectsSpec(),
        label = "content_bottom_padding",
    )
    val contentBottomPadding = animatedBottomPadding.coerceAtLeast(0.dp)

    // Root content color: text/icons not inside a Surface would otherwise default to black.
    CompositionLocalProvider(
        LocalAppearanceConfig provides appearanceConfig,
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        LocalPlaybackActive provides isPlaying,
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            if (isFirstRun != null) {
                val padding = PaddingValues(bottom = contentBottomPadding)
                NavHost(
                    navController = navController,
                    startDestination = if (isFirstRun == true) Screen.Setup.route else Screen.Home.route,
                    modifier = behindPlayer
                        .fillMaxSize()
                        .padding(padding)
                        // Screens below must not add the navigation-bar inset again.
                        .consumeWindowInsets(padding),
                    // Tab ↔ tab: M3 fade-through. Drill-in/out: M3 shared axis X. Both use the
                    // theme's motion scheme so they match the rest of the app.
                    enterTransition = {
                        if (isTab(initialState.destination.route) && isTab(targetState.destination.route)) {
                            fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.96f)
                        } else {
                            slideInHorizontally(motion.defaultSpatialSpec()) { (it * 0.15f).toInt() } + fadeIn(motion.defaultEffectsSpec())
                        }
                    },
                    exitTransition = {
                        if (isTab(initialState.destination.route) && isTab(targetState.destination.route)) {
                            fadeOut(motion.fastEffectsSpec())
                        } else {
                            slideOutHorizontally(motion.defaultSpatialSpec()) { -(it * 0.15f).toInt() } + fadeOut(motion.fastEffectsSpec())
                        }
                    },
                    popEnterTransition = {
                        if (isTab(initialState.destination.route) && isTab(targetState.destination.route)) {
                            fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.96f)
                        } else {
                            slideInHorizontally(motion.defaultSpatialSpec()) { -(it * 0.15f).toInt() } + fadeIn(motion.defaultEffectsSpec())
                        }
                    },
                    popExitTransition = {
                        if (isTab(initialState.destination.route) && isTab(targetState.destination.route)) {
                            fadeOut(motion.fastEffectsSpec())
                        } else {
                            slideOutHorizontally(motion.defaultSpatialSpec()) { (it * 0.15f).toInt() } + fadeOut(motion.fastEffectsSpec())
                        }
                    },
                ) {
                    composable(Screen.Setup.route) {
                        SetupScreen(onComplete = {
                            scope.launch {
                                libraryViewModel.prefs.setFirstRunCompleted()
                                libraryViewModel.syncLibrary(force = true)
                                navController.navigate(Screen.Home.route) {
                                    popUpTo(Screen.Setup.route) { inclusive = true }
                                }
                            }
                        })
                    }

                    composable(Screen.Home.route) {
                        HomeScreen(
                            playerViewModel = playerViewModel,
                            libraryViewModel = libraryViewModel,
                            onNavigateTo = { navController.navigate(it.route) },
                            onOpenPlayer = navigateToPlayer,
                        )
                    }

                    composable(Screen.Library.route) {
                        val goToSearch: () -> Unit = { navigateToTab(Screen.Search.route) }
                        val libraryContent: @Composable () -> Unit = {
                            LibraryScreen(showFolders = musicSource == MusicSource.LOCAL) { section ->
                                when (section) {
                                    LibrarySection.Songs -> SongsScreen(
                                        libraryViewModel = libraryViewModel,
                                        playerViewModel = playerViewModel,
                                        onSearchClick = goToSearch,
                                        onNavigateToPlayer = navigateToPlayer,
                                        onNavigateToTagEditor = { id -> navController.navigate(Screen.TagEditor.createRoute(id)) },
                                    )
                                    LibrarySection.Albums -> AlbumsScreen(
                                        libraryViewModel = libraryViewModel,
                                        onAlbumClick = { album -> navController.navigate(Screen.AlbumDetail.createRoute(album.id)) },
                                        onSearchClick = goToSearch,
                                    )
                                    LibrarySection.Artists -> ArtistsScreen(
                                        libraryViewModel = libraryViewModel,
                                        onArtistClick = { artist -> navController.navigate(Screen.ArtistDetail.createRoute(artist.name)) },
                                        onSearchClick = goToSearch,
                                    )
                                    LibrarySection.Liked -> LikedSongsScreen(
                                        playerViewModel = playerViewModel,
                                        libraryViewModel = libraryViewModel,
                                        onBack = {},
                                        onNavigateToPlayer = navigateToPlayer,
                                    )
                                    LibrarySection.Folders -> FoldersScreen(
                                        libraryViewModel = libraryViewModel,
                                        playerViewModel = playerViewModel,
                                        onBack = {},
                                    )
                                }
                            }
                        }
                        if (musicSource == MusicSource.LOCAL) PermissionWrapper(libraryContent) else libraryContent()
                    }

                    composable(Screen.Playlists.route) {
                        PlaylistsScreen(
                            libraryViewModel = libraryViewModel,
                            onPlaylistClick = { playlist -> navController.navigate(Screen.PlaylistDetail.createRoute(playlist.id)) }
                        )
                    }

                    composable(Screen.Search.route) {
                        SearchScreen(
                            libraryViewModel = libraryViewModel,
                            playerViewModel = playerViewModel,
                            onAlbumClick = { album -> navController.navigate(Screen.AlbumDetail.createRoute(album.id)) },
                            onArtistClick = { artist -> navController.navigate(Screen.ArtistDetail.createRoute(artist.name)) },
                            onNavigateToPlayer = navigateToPlayer,
                        )
                    }

                    composable(
                        route = Screen.TagEditor.route,
                        arguments = listOf(navArgument("songId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val songId = backStackEntry.arguments?.getLong("songId") ?: return@composable
                        TagEditorScreen(
                            songId = songId,
                            libraryViewModel = libraryViewModel,
                            // The save result can arrive after the user already left; only pop this screen.
                            onBack = { if (navController.currentBackStackEntry == backStackEntry) navController.popBackStack() }
                        )
                    }

                    composable(
                        route = Screen.AlbumDetail.route,
                        arguments = listOf(navArgument("albumId") { type = NavType.LongType }),
                    ) { backStackEntry ->
                        val albumId = backStackEntry.arguments?.getLong("albumId") ?: return@composable
                        AlbumDetailScreen(
                            albumId = albumId,
                            libraryViewModel = libraryViewModel,
                            playerViewModel = playerViewModel,
                            onBack = { navController.popBackStack() },
                            onNavigateToPlayer = navigateToPlayer,
                            onNavigateToTagEditor = { id -> navController.navigate(Screen.TagEditor.createRoute(id)) },
                            onArtistClick = { name -> navController.navigate(Screen.ArtistDetail.createRoute(name)) },
                        )
                    }

                    composable(
                        route = Screen.ArtistDetail.route,
                        arguments = listOf(navArgument("artistName") { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val artistName = URLDecoder.decode(
                            backStackEntry.arguments?.getString("artistName") ?: return@composable,
                            "UTF-8"
                        )
                        ArtistDetailScreen(
                            artistName = artistName,
                            libraryViewModel = libraryViewModel,
                            playerViewModel = playerViewModel,
                            onAlbumClick = { album -> navController.navigate(Screen.AlbumDetail.createRoute(album.id)) },
                            onBack = { navController.popBackStack() },
                            onNavigateToPlayer = navigateToPlayer,
                            onNavigateToTagEditor = { id -> navController.navigate(Screen.TagEditor.createRoute(id)) },
                        )
                    }

                    composable(
                        route = Screen.SongDetail.route,
                        arguments = listOf(navArgument("songId") { type = NavType.LongType }),
                    ) { backStackEntry ->
                        val songId = backStackEntry.arguments?.getLong("songId") ?: return@composable
                        SongDetailScreen(
                            songId = songId,
                            onBack = { navController.popBackStack() },
                            onArtistClick = { name -> navController.navigate(Screen.ArtistDetail.createRoute(name)) },
                        )
                    }

                    composable(
                        route = Screen.PlaylistDetail.route,
                        arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
                    ) { backStackEntry ->
                        val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: return@composable
                        PlaylistDetailScreen(
                            playlistId = playlistId,
                            libraryViewModel = libraryViewModel,
                            playerViewModel = playerViewModel,
                            onBack = { navController.popBackStack() },
                            onNavigateToPlayer = navigateToPlayer,
                        )
                    }

                    composable(Screen.Settings.route) {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToLicenses = { navController.navigate(Screen.Licenses.route) },
                            onNavigateToEqualizer = { navController.navigate(Screen.Equalizer.route) },
                            onNavigateToMetadataEnrichment = { navController.navigate(Screen.MetadataEnrichment.route) },
                            libraryViewModel = libraryViewModel,
                            settingsViewModel = settingsViewModel,
                        )
                    }

                    composable(Screen.Statistics.route) {
                        StatisticsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Screen.Equalizer.route) {
                        EqualizerScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Screen.MetadataEnrichment.route) {
                        MetadataEnrichmentScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Screen.Licenses.route) {
                        LicensesScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Screen.LyricsEditor.route) {
                        LyricsEditorScreen(
                            playerViewModel = playerViewModel,
                            lyricsRepository = playerViewModel.lyricsRepository,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showSheet,
                enter = slideInVertically(motion.defaultSpatialSpec()) { it / 4 } + fadeIn(motion.defaultEffectsSpec()),
                exit = slideOutVertically(motion.fastSpatialSpec()) { it / 4 } + fadeOut(motion.fastEffectsSpec()),
            ) {

                    PlayerSheet(
                        state = sheetState,
                        metrics = miniMetrics,
                        bottomBarHeightPx = { bottomBarHeightPx },
                        collapsedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        expandedContainerColor = MaterialTheme.colorScheme.surface,
                        artworkUri = currentSong?.artworkUri,
                        mini = { onExpand ->
                            MiniPlayerBar(
                                playerViewModel = playerViewModel,
                                metrics = miniMetrics,
                                onExpand = onExpand,
                            )
                        },
                        full = { onCollapse ->
                            PlayerScreen(
                                playerViewModel = playerViewModel,
                                onBack = onCollapse,
                                onLyricsEdit = { navigateFromPlayer(Screen.LyricsEditor.route) },
                                onNavigateToEqualizer = { navigateFromPlayer(Screen.Equalizer.route) },
                                playlists = allPlaylists,
                                onAddToPlaylist = { playlist ->
                                    playerViewModel.currentSong.value?.let { song ->
                                        libraryViewModel.addSongsToPlaylist(playlist.id, listOf(song.id))
                                    }
                                },
                                onCreatePlaylist = { name ->
                                    val songIds = listOfNotNull(playerViewModel.currentSong.value?.id)
                                    libraryViewModel.createPlaylistWithSongs(name, songIds)
                                },
                                onArtistClick = { name -> navigateFromPlayer(Screen.ArtistDetail.createRoute(name)) },
                                onSongClick = { songId -> navigateFromPlayer(Screen.SongDetail.createRoute(songId)) },
                            )
                        },
                    )
            }

            // Drawn after the sheet so it stays on top of the collapsed mini player's container;
            // slides away as the sheet expands.
            AnimatedVisibility(
                visible = showBottomBar,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(motion.defaultSpatialSpec()) { it },
                exit = slideOutVertically(motion.fastSpatialSpec()) { it },
            ) {
                ResonanceBottomBar(
                    items = visibleNavItems,
                    currentDestination = currentDestination,
                    floating = floatingNav,
                    tinted = tintedNav,
                    labelVisibility = navLabelVis,
                    onSelect = { navigateToTab(it.screen.route) },
                    modifier = behindPlayer.graphicsLayer { translationY = size.height * sheetState.progress },
                )
            }

            // App-wide undo snackbar ("Play next", "Add to queue"…), just above the mini player.
            val undoSnackbar = remember { SnackbarHostState() }
            LaunchedEffect(Unit) {
                playerViewModel.undoEvents.collectLatest { event ->
                    undoSnackbar.currentSnackbarData?.dismiss()
                    val result = undoSnackbar.showSnackbar(event.message, actionLabel = "Undo", duration = SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) event.undo()
                }
            }
            SnackbarHost(
                undoSnackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = contentBottomPadding)
                    .graphicsLayer { alpha = 1f - sheetState.progress },
            )

            receiveUri?.let { uri ->
                ReceiveSheet(
                    uri = uri,
                    onDismiss = onReceiveDismiss,
                    onPlayNow = { songs ->
                        playerViewModel.play(songs, 0)
                        navigateToPlayer()
                    },
                )
            }
        }
    }
}

private val ShortNavHeight = 64.dp
private val FloatingNavHeight = 64.dp
private val FloatingNavVerticalMargin = 12.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ResonanceBottomBar(
    items: List<NavItem>,
    currentDestination: NavDestination?,
    floating: Boolean,
    tinted: Boolean,
    labelVisibility: String,
    onSelect: (NavItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = rememberHaptics()
    val content: @Composable () -> Unit = {
        items.forEach { item ->
            val selected = currentDestination?.hierarchy?.any { it.route == item.screen.route } == true
            ShortNavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) haptic(HapticFeedbackType.SegmentTick)
                    onSelect(item)
                },
                icon = {
                    Icon(if (selected) item.selectedIcon else item.icon, contentDescription = item.label)
                },
                label = when {
                    labelVisibility == "NEVER" -> null
                    labelVisibility == "SELECTED" && !selected -> null
                    else -> { { Text(item.label, maxLines = 1) } }
                },
            )
        }
    }
    if (floating) {
        Box(
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = FloatingNavVerticalMargin),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                ShortNavigationBar(
                    modifier = Modifier.height(FloatingNavHeight),
                    containerColor = Color.Transparent,
                    windowInsets = WindowInsets(0),
                    content = content,
                )
            }
        }
    } else {
        ShortNavigationBar(
            modifier = modifier,
            containerColor = if (tinted) MaterialTheme.colorScheme.surfaceContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
            windowInsets = WindowInsets.navigationBars,
            content = content,
        )
    }
}
