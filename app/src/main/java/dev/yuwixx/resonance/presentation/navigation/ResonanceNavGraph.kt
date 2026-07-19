// Defines all navigation routes, the bottom navigation bar (with mini-player above it),
// and wires every composable destination to its screen composable.
package dev.yuwixx.resonance.presentation.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import dev.yuwixx.resonance.presentation.screens.*
import dev.yuwixx.resonance.presentation.viewmodel.LibraryViewModel
import dev.yuwixx.resonance.presentation.viewmodel.PlayerViewModel
import dev.yuwixx.resonance.presentation.viewmodel.SettingsViewModel
import dev.yuwixx.resonance.presentation.components.AppearanceConfig
import dev.yuwixx.resonance.presentation.components.LocalAppearanceConfig
import dev.yuwixx.resonance.presentation.components.MiniPlayer
import dev.yuwixx.resonance.presentation.components.PermissionWrapper
import dev.yuwixx.resonance.data.model.MusicSource
import kotlinx.coroutines.launch

sealed class Screen(val route: String) {
    data object Setup         : Screen("setup")
    data object Home          : Screen("home")
    data object Songs         : Screen("songs")
    data object Albums        : Screen("albums")
    data object Artists       : Screen("artists")
    data object Folders       : Screen("folders")
    data object Playlists     : Screen("playlists")
    data object LikedSongs    : Screen("liked_songs")
    data object Player        : Screen("player")
    data object Search        : Screen("search")
    data object Settings      : Screen("settings")
    data object Licenses      : Screen("licenses")
    data object NowPlayingQueue : Screen("now_playing_queue")
    data object LyricsEditor  : Screen("lyrics_editor")
    data object Statistics    : Screen("statistics")
    data object Equalizer     : Screen("equalizer")

    data object TagEditor : Screen("tag_editor/{songId}") {
        fun createRoute(songId: Long) = "tag_editor/$songId"
    }

    data object AlbumDetail   : Screen("album_detail/{albumId}") {
        fun createRoute(albumId: Long) = "album_detail/$albumId"
    }
    data object ArtistDetail  : Screen("artist_detail/{artistName}") {
        fun createRoute(artistName: String) = "artist_detail/${java.net.URLEncoder.encode(artistName, "UTF-8")}"
    }
    data object PlaylistDetail : Screen("playlist_detail/{playlistId}") {
        fun createRoute(playlistId: Long) = "playlist_detail/$playlistId"
    }
}

data class NavItem(
    val screen: Screen,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector = icon,
)

val navItems = listOf(
    NavItem(Screen.Home,       "Home",     Icons.Rounded.Home,                         Icons.Rounded.Home),
    NavItem(Screen.Songs,      "Songs",    Icons.Rounded.MusicNote,                    Icons.Rounded.MusicNote),
    NavItem(Screen.Albums,     "Albums",   Icons.Rounded.Album,                        Icons.Rounded.Album),
    NavItem(Screen.Artists,    "Artists",  Icons.Rounded.Person,                       Icons.Rounded.Person),
    NavItem(Screen.LikedSongs, "Liked",    Icons.Rounded.FavoriteBorder,               Icons.Rounded.Favorite),
    NavItem(Screen.Playlists,  "Playlists", Icons.AutoMirrored.Rounded.QueueMusic,     Icons.AutoMirrored.Rounded.QueueMusic),
)

// Material Motion easing curves used throughout nav transitions.
private val Emphasized = CubicBezierEasing(0.2f, 0.0f, 0f, 1.0f)
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalAnimationApi::class)
@Composable
fun ResonanceNavGraph(
    playerViewModel: PlayerViewModel,
    receiveUri: android.net.Uri? = null,
    onReceiveDismiss: () -> Unit = {},
) {
    val navController = rememberNavController()
    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val isFirstRun by libraryViewModel.prefs.isFirstRun.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val musicSource by settingsViewModel.musicSource.collectAsState()

    val currentSong by playerViewModel.currentSong.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val allPlaylists by libraryViewModel.allPlaylists.collectAsState()

    val miniPlayerStyle by playerViewModel.miniPlayerStyle.collectAsState()

    val hiddenNavTabs by libraryViewModel.prefs.hiddenNavTabs.collectAsState(initial = emptySet())
    val visibleNavItems = remember(hiddenNavTabs) {
        navItems.filter { it.screen.route !in hiddenNavTabs }
    }

    val compactList by libraryViewModel.prefs.compactListMode.collectAsState(initial = false)
    val showDuration by libraryViewModel.prefs.showDurationInList.collectAsState(initial = false)
    val artworkShape by libraryViewModel.prefs.playerArtworkShape.collectAsState(initial = "ROUNDED")
    val lyricAlign by libraryViewModel.prefs.lyricAlignment.collectAsState(initial = "CENTER")
    val seekbarClr by libraryViewModel.prefs.seekbarColor.collectAsState(initial = "PRIMARY")
    val tintedNav by libraryViewModel.prefs.tintedNavBar.collectAsState(initial = false)
    val floatingNav by libraryViewModel.prefs.floatingNavBar.collectAsState(initial = false)
    val navLabelVis by libraryViewModel.prefs.navLabelVisibility.collectAsState(initial = "ALWAYS")
    val playlistCols by libraryViewModel.prefs.playlistGridColumns.collectAsState(initial = 2)
    val showAlbumInList by libraryViewModel.prefs.showAlbumInList.collectAsState(initial = false)
    val listArtworkSize by libraryViewModel.prefs.listArtworkSize.collectAsState(initial = "MEDIUM")
    val showRemainingTime by libraryViewModel.prefs.showRemainingTime.collectAsState(initial = false)
    val showNextSongInPlayer by libraryViewModel.prefs.showNextSongInPlayer.collectAsState(initial = false)
    val lyricsLineSpacing by libraryViewModel.prefs.lyricsLineSpacing.collectAsState(initial = "NORMAL")
    val miniPlayerShowProgress by libraryViewModel.prefs.miniPlayerShowProgress.collectAsState(initial = true)
    val miniPlayerShowSkipBtn by libraryViewModel.prefs.miniPlayerShowSkipBtn.collectAsState(initial = true)
    val showEqualizerInPlayer by libraryViewModel.prefs.showEqualizerInPlayer.collectAsState(initial = true)
    val hapticEnabled by libraryViewModel.prefs.hapticFeedback.collectAsState(initial = true)
    val showBitrateInfo by libraryViewModel.prefs.showBitrateInfo.collectAsState(initial = false)

    val appearanceConfig = remember(compactList, showDuration, artworkShape, lyricAlign, seekbarClr, tintedNav, floatingNav, navLabelVis, playlistCols, showAlbumInList, listArtworkSize, showRemainingTime, showNextSongInPlayer, lyricsLineSpacing, miniPlayerShowProgress, miniPlayerShowSkipBtn, showEqualizerInPlayer, hapticEnabled, showBitrateInfo) {
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
        )
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route
    // Hide the bottom bar and mini-player entirely when the full player or setup is open.
    val isPlayerRoute = currentRoute == Screen.Player.route
    val isSetupRoute = currentRoute == Screen.Setup.route

    val navigateToPlayer = { navController.navigate(Screen.Player.route) }

    LaunchedEffect(isFirstRun) {
        if (isFirstRun == true && currentRoute != Screen.Setup.route) {
            navController.navigate(Screen.Setup.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    LaunchedEffect(visibleNavItems, currentRoute) {
        val onHiddenTab = currentRoute != null
            && navItems.any { it.screen.route == currentRoute }
            && visibleNavItems.none { it.screen.route == currentRoute }
        if (onHiddenTab) {
            navController.navigate(Screen.Home.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState    = true
            }
        }
    }

    CompositionLocalProvider(LocalAppearanceConfig provides appearanceConfig) {
    SharedTransitionLayout {
        Scaffold(
            bottomBar = {
                if (!isSetupRoute) {
                    Column {
                        AnimatedVisibility(
                            visible = currentSong != null && !isPlayerRoute,
                            enter = slideInVertically(tween(380, easing = EmphasizedDecelerate)) { it } +
                                fadeIn(tween(300, easing = EmphasizedDecelerate)),
                            exit = slideOutVertically(tween(300, easing = EmphasizedAccelerate)) { it } +
                                fadeOut(tween(220, easing = EmphasizedAccelerate)),
                        ) {
                            MiniPlayer(
                                playerViewModel = playerViewModel,
                                style = miniPlayerStyle,
                                onClick = navigateToPlayer,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = this@AnimatedVisibility,
                            )
                        }

                        if (!isPlayerRoute) {
                            val navBarItems: @Composable RowScope.() -> Unit = {
                                visibleNavItems.forEach { item ->
                                    val selected = currentDestination?.hierarchy
                                        ?.any { it.route == item.screen.route } == true
                                    NavigationBarItem(
                                        icon = {
                                            val iconScale by animateFloatAsState(
                                                targetValue = if (selected) 1.14f else 1f,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMedium,
                                                ),
                                                label = "${item.screen.route}_icon_scale",
                                            )
                                            Icon(
                                                if (selected) item.selectedIcon else item.icon,
                                                contentDescription = item.label,
                                                modifier = Modifier.scale(iconScale),
                                            )
                                        },
                                        label = when (navLabelVis) {
                                            "NEVER" -> null
                                            else -> { { Text(item.label) } }
                                        },
                                        alwaysShowLabel = navLabelVis == "ALWAYS",
                                        selected = selected,
                                        onClick = {
                                            navController.navigate(item.screen.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                    )
                                }
                            }

                            if (floatingNav) {
                                Column(
                                    modifier = Modifier
                                        .navigationBarsPadding()
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(28.dp),
                                        shadowElevation = 8.dp,
                                        tonalElevation = 4.dp,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        NavigationBar(
                                            windowInsets = WindowInsets(0),
                                            containerColor = Color.Transparent,
                                            tonalElevation = 0.dp,
                                            content = navBarItems,
                                        )
                                    }
                                }
                            } else {
                                NavigationBar(
                                    tonalElevation = if (tintedNav) 3.dp else 0.dp,
                                    windowInsets = WindowInsets.navigationBars,
                                    containerColor = if (tintedNav) MaterialTheme.colorScheme.surfaceContainer else NavigationBarDefaults.containerColor,
                                    content = navBarItems,
                                )
                            }
                        }
                    }
                }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                // Tabs use M3 Fade Through (unrelated content).
                // Push/pop uses M3 Shared Axis X (subtle slide + fade, related content).
                enterTransition = {
                    val fromRoute = initialState.destination.route
                    val toRoute   = targetState.destination.route
                    val fromIndex = navItems.indexOfFirst { it.screen.route == fromRoute }
                    val toIndex   = navItems.indexOfFirst { it.screen.route == toRoute }

                    if (fromIndex != -1 && toIndex != -1) {
                        // Fade Through: crossfade with a subtle scale-up so the swap
                        // reads as motion even between screens sharing the same background.
                        fadeIn(tween(220, easing = EmphasizedDecelerate)) +
                            scaleIn(tween(220, easing = EmphasizedDecelerate), initialScale = 0.94f)
                    } else {
                        // Shared Axis X: slide from right + fade in
                        slideInHorizontally(tween(480, easing = EmphasizedDecelerate)) { (it * 0.2f).toInt() } +
                            fadeIn(tween(480, delayMillis = 40, easing = EmphasizedDecelerate))
                    }
                },
                exitTransition = {
                    val fromRoute = initialState.destination.route
                    val toRoute   = targetState.destination.route
                    val fromIndex = navItems.indexOfFirst { it.screen.route == fromRoute }
                    val toIndex   = navItems.indexOfFirst { it.screen.route == toRoute }

                    if (fromIndex != -1 && toIndex != -1) {
                        // Fade Through: fade out slightly before new screen fades in,
                        // scaling down a touch to sell the "through" depth motion.
                        fadeOut(tween(160, easing = EmphasizedAccelerate)) +
                            scaleOut(tween(160, easing = EmphasizedAccelerate), targetScale = 1.04f)
                    } else {
                        // Shared Axis X: slide to left + fade out
                        slideOutHorizontally(tween(360, easing = EmphasizedAccelerate)) { -(it * 0.2f).toInt() } +
                            fadeOut(tween(180, easing = EmphasizedAccelerate))
                    }
                },
                popEnterTransition = {
                    // Shared Axis X reversed: slide in from left + fade in
                    slideInHorizontally(tween(480, easing = EmphasizedDecelerate)) { -(it * 0.2f).toInt() } +
                        fadeIn(tween(480, delayMillis = 40, easing = EmphasizedDecelerate))
                },
                popExitTransition = {
                    // Shared Axis X reversed: slide out to right + fade out
                    slideOutHorizontally(tween(360, easing = EmphasizedAccelerate)) { (it * 0.2f).toInt() } +
                        fadeOut(tween(180, easing = EmphasizedAccelerate))
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
                    )
                }

                composable(Screen.Songs.route) {
                    if (musicSource == MusicSource.LOCAL) {
                        PermissionWrapper {
                            SongsScreen(
                                libraryViewModel = libraryViewModel,
                                playerViewModel = playerViewModel,
                                onSearchClick = { navController.navigate(Screen.Search.route) },
                                onNavigateToPlayer = navigateToPlayer,
                                onNavigateToTagEditor = { id -> navController.navigate(Screen.TagEditor.createRoute(id)) }
                            )
                        }
                    } else {
                        SongsScreen(
                            libraryViewModel = libraryViewModel,
                            playerViewModel = playerViewModel,
                            onSearchClick = { navController.navigate(Screen.Search.route) },
                            onNavigateToPlayer = navigateToPlayer,
                            onNavigateToTagEditor = { id -> navController.navigate(Screen.TagEditor.createRoute(id)) }
                        )
                    }
                }

                composable(Screen.Albums.route) {
                    if (musicSource == MusicSource.LOCAL) {
                        PermissionWrapper {
                            AlbumsScreen(
                                libraryViewModel = libraryViewModel,
                                onAlbumClick = { album -> navController.navigate(Screen.AlbumDetail.createRoute(album.id)) },
                                onSearchClick = { navController.navigate(Screen.Search.route) }
                            )
                        }
                    } else {
                        AlbumsScreen(
                            libraryViewModel = libraryViewModel,
                            onAlbumClick = { album -> navController.navigate(Screen.AlbumDetail.createRoute(album.id)) },
                            onSearchClick = { navController.navigate(Screen.Search.route) }
                        )
                    }
                }

                composable(Screen.Artists.route) {
                    if (musicSource == MusicSource.LOCAL) {
                        PermissionWrapper {
                            ArtistsScreen(
                                libraryViewModel = libraryViewModel,
                                onArtistClick = { artist -> navController.navigate(Screen.ArtistDetail.createRoute(artist.name)) },
                                onSearchClick = { navController.navigate(Screen.Search.route) }
                            )
                        }
                    } else {
                        ArtistsScreen(
                            libraryViewModel = libraryViewModel,
                            onArtistClick = { artist -> navController.navigate(Screen.ArtistDetail.createRoute(artist.name)) },
                            onSearchClick = { navController.navigate(Screen.Search.route) }
                        )
                    }
                }

                composable(Screen.Folders.route) {
                    if (musicSource == MusicSource.LOCAL) {
                        PermissionWrapper {
                            FoldersScreen(
                                libraryViewModel = libraryViewModel,
                                playerViewModel = playerViewModel,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    } else {
                        FoldersScreen(
                            libraryViewModel = libraryViewModel,
                            playerViewModel = playerViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }

                composable(Screen.Playlists.route) {
                    PlaylistsScreen(
                        libraryViewModel = libraryViewModel,
                        onPlaylistClick = { playlist -> navController.navigate(Screen.PlaylistDetail.createRoute(playlist.id)) }
                    )
                }

                composable(Screen.LikedSongs.route) {
                    LikedSongsScreen(
                        playerViewModel = playerViewModel,
                        libraryViewModel = libraryViewModel,
                        onBack = { navController.popBackStack() },
                        onNavigateToPlayer = navigateToPlayer,
                    )
                }

                composable(
                    route = Screen.Player.route,
                    // Shared Axis Y: player rises up from below
                    enterTransition = {
                        slideInVertically(tween(480, easing = EmphasizedDecelerate)) { (it * 0.38f).toInt() } +
                            fadeIn(tween(360, easing = EmphasizedDecelerate))
                    },
                    exitTransition = {
                        fadeOut(tween(180, easing = EmphasizedAccelerate))
                    },
                    popEnterTransition = {
                        fadeIn(tween(240, easing = EmphasizedDecelerate)) +
                            scaleIn(tween(240, easing = EmphasizedDecelerate), initialScale = 0.98f)
                    },
                    popExitTransition = {
                        slideOutVertically(tween(420, easing = EmphasizedAccelerate)) { (it * 0.32f).toInt() } +
                            fadeOut(tween(260, easing = EmphasizedAccelerate))
                    },
                ) {
                    PlayerScreen(
                        playerViewModel = playerViewModel,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable,
                        onBack = { navController.popBackStack() },
                        onQueueClick = { navController.navigate(Screen.NowPlayingQueue.route) },
                        onLyricsEdit = { navController.navigate(Screen.LyricsEditor.route) },
                        onNavigateToEqualizer = { navController.navigate(Screen.Equalizer.route) },
                        playlists = allPlaylists,
                        onAddToPlaylist = { playlist ->
                            playerViewModel.currentSong.value?.let { song ->
                                libraryViewModel.addSongsToPlaylist(playlist.id, listOf(song.id))
                            }
                        },
                        onCreatePlaylist = { name ->
                            libraryViewModel.createPlaylist(name)
                        },
                        onArtistClick = { name -> navController.navigate(Screen.ArtistDetail.createRoute(name)) },
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
                        onBack = { navController.popBackStack() }
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
                    val artistName = java.net.URLDecoder.decode(
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

                composable(Screen.NowPlayingQueue.route) {
                    QueueScreen(
                        playerViewModel = playerViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Screen.Settings.route) {
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToLicenses = { navController.navigate(Screen.Licenses.route) },
                        onNavigateToEqualizer = { navController.navigate(Screen.Equalizer.route) },
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

            receiveUri?.let { uri ->
                ReceiveSheet(
                    uri       = uri,
                    onDismiss = onReceiveDismiss,
                    onPlayNow = { songs ->
                        playerViewModel.play(songs, 0)
                        navController.navigate(Screen.Player.route)
                    },
                )
            }
        }
    }
    }
}
