package dev.yuwixx.resonance.presentation.screens

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.RepeatMode as AnimationRepeatMode
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.graphics.shapes.Morph
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.yuwixx.resonance.data.model.LyricLine
import dev.yuwixx.resonance.data.model.Playlist
import dev.yuwixx.resonance.data.model.RepeatMode
import dev.yuwixx.resonance.data.model.SleepTimer
import dev.yuwixx.resonance.data.model.SmartQueueReason
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.model.WaveformData
import dev.yuwixx.resonance.data.repository.ArtworkComposeColors
import dev.yuwixx.resonance.data.repository.LyricsResult
import dev.yuwixx.resonance.presentation.components.*
import dev.yuwixx.resonance.presentation.components.EaseInOutSine
import dev.yuwixx.resonance.presentation.player.LocalArtworkMorph
import dev.yuwixx.resonance.presentation.player.QueueSheet
import dev.yuwixx.resonance.presentation.viewmodel.PlayerViewModel
import dev.yuwixx.resonance.presentation.viewmodel.ShareViewModel
import dev.yuwixx.resonance.ui.theme.BrandColors
import dev.yuwixx.resonance.ui.theme.DmSans
import java.text.DecimalFormat
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onLyricsEdit: () -> Unit,
    onNavigateToEqualizer: () -> Unit = {},
    playlists: List<Playlist> = emptyList(),
    onAddToPlaylist: (Playlist) -> Unit = {},
    onCreatePlaylist: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onSongClick: (Long) -> Unit = {},
) {
    val currentSong by playerViewModel.currentSong.collectAsStateWithLifecycle()
    val isPlaying by playerViewModel.isPlaying.collectAsStateWithLifecycle()

    // Kept as a State and read only in SeekBarAndTimeLabels, so position ticks don't recompose
    // the whole screen.
    val positionMsState = playerViewModel.positionMs.collectAsStateWithLifecycle()

    val durationMs by playerViewModel.durationMs.collectAsStateWithLifecycle()
    val repeatMode by playerViewModel.repeatMode.collectAsStateWithLifecycle()
    val shuffleEnabled by playerViewModel.shuffleEnabled.collectAsStateWithLifecycle()
    val waveformData by playerViewModel.waveformData.collectAsStateWithLifecycle()
    val seekbarStyle by playerViewModel.seekbarStyle.collectAsStateWithLifecycle()
    val blurBackground by playerViewModel.blurBackground.collectAsStateWithLifecycle()
    val blurStrength by playerViewModel.blurStrength.collectAsStateWithLifecycle()
    val artworkAnimation by playerViewModel.artworkAnimation.collectAsStateWithLifecycle()
    val sleepTimer by playerViewModel.sleepTimer.collectAsStateWithLifecycle()
    val lyricsResult by playerViewModel.lyricsResult.collectAsStateWithLifecycle()
    val isTrackLoved by playerViewModel.isCurrentSongLiked.collectAsStateWithLifecycle()
    val artworkColors by playerViewModel.artworkColors.collectAsStateWithLifecycle()
    val nextSong by playerViewModel.nextSong.collectAsStateWithLifecycle()
    val isLoadingSmartQueue by playerViewModel.isLoadingSmartQueue.collectAsStateWithLifecycle()
    val smartQueueError by playerViewModel.smartQueueError.collectAsStateWithLifecycle()

    val playerLayout by playerViewModel.playerLayout.collectAsStateWithLifecycle()
    val showLyricsButton by playerViewModel.showLyricsButton.collectAsStateWithLifecycle()
    val lyricsFontScale by playerViewModel.lyricsFontScale.collectAsStateWithLifecycle()

    val song = currentSong ?: return
    val shareViewModel: ShareViewModel = hiltViewModel()

    var showLyricsOverlay by rememberSaveable { mutableStateOf(false) }
    var showQueueSheet by rememberSaveable { mutableStateOf(false) }
    var showSleepTimerDialog by rememberSaveable { mutableStateOf(false) }
    var showOptionsSheet by rememberSaveable { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(smartQueueError) {
        smartQueueError?.let {
            snackbarHostState.showSnackbar(it)
            playerViewModel.clearSmartQueueError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        DynamicBackground(
            artworkUri = song.artworkUri,
            blurBackground = blurBackground,
            blurStrength = blurStrength
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            PlayerHeader(
                onBack = onBack,
                onQueueClick = { showQueueSheet = true },
                onSleepTimer = { showSleepTimerDialog = true },
                sleepTimer = sleepTimer,
            )

            Spacer(modifier = Modifier.weight(1f))

            val artworkPadding = when (playerLayout) {
                "ARTWORK_BIG" -> 12.dp
                "LYRICS_FOCUS" -> 64.dp
                else -> 28.dp
            }

            ArtworkPanel(
                song = song,
                isPlaying = isPlaying,
                artworkAnimation = artworkAnimation,
                onSwipeNext = { playerViewModel.skipNext() },
                onSwipePrevious = { playerViewModel.skipPrevious() },
                modifier = Modifier
                    .padding(horizontal = artworkPadding)
                    .aspectRatio(1f),
                onArtworkMissing = { playerViewModel.getSongArtworkUrl(song) },
            )

            Spacer(modifier = Modifier.weight(1f))

            PlaybackControls(
                song = song,
                isPlaying = isPlaying,
                position = positionMsState,
                durationMs = durationMs,
                repeatMode = repeatMode,
                shuffleEnabled = shuffleEnabled,
                waveformData = waveformData,
                seekbarStyle = seekbarStyle,
                isTrackLoved = isTrackLoved,
                nextSong = nextSong,
                artworkColors = artworkColors,
                onPlayPause = { playerViewModel.playPause() },
                onNext = { playerViewModel.skipNext() },
                onPrevious = { playerViewModel.skipPrevious() },
                onSeek = { playerViewModel.seekTo(it) },
                onRepeatModeChange = { playerViewModel.toggleRepeat() },
                onShuffleToggle = { playerViewModel.toggleShuffle() },
                onLoveTrack = { playerViewModel.toggleLike() },
                onArtistClick = onArtistClick,
                onSongClick = { onSongClick(song.id) },
            )

            PlayerFooter(
                lyricsResult = lyricsResult,
                showLyricsButton = showLyricsButton,
                onLyricsClick = { showLyricsOverlay = true },
                onLyricsEdit = onLyricsEdit,
                onMoreOptions = { showOptionsSheet = true },
                onEqualizerClick = onNavigateToEqualizer,
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Back closes the overlay first instead of collapsing the whole player.
        BackHandler(enabled = showLyricsOverlay) { showLyricsOverlay = false }

        val motion = MaterialTheme.motionScheme
        AnimatedVisibility(
            visible = showLyricsOverlay,
            enter = fadeIn(motion.defaultEffectsSpec()) +
                scaleIn(motion.defaultSpatialSpec(), initialScale = 0.95f),
            exit = fadeOut(motion.fastEffectsSpec()) +
                scaleOut(motion.fastSpatialSpec(), targetScale = 0.98f),
        ) {
            LyricsOverlay(
                result = lyricsResult,
                activeLine = playerViewModel.activeLyricIndex,
                position = playerViewModel.positionMs,
                fontScale = lyricsFontScale,
                onSeek = { playerViewModel.seekTo(it) },
                onClose = { showLyricsOverlay = false },
                onRetry = { playerViewModel.reloadLyrics() },
                onSearch = onLyricsEdit,
            )
        }

        if (showSleepTimerDialog) {
            SleepTimerDialog(
                currentTimer = sleepTimer,
                onDismiss = { showSleepTimerDialog = false },
                onSetTimer = { timer ->
                    playerViewModel.setSleepTimer(timer)
                    showSleepTimerDialog = false
                },
                onSetAfterTracks = { n: Int, fade: Boolean ->
                    playerViewModel.setSleepAfterTracks(n, fade)
                    showSleepTimerDialog = false
                },
            )
        }

        if (showQueueSheet) {
            QueueSheet(
                playerViewModel = playerViewModel,
                onDismiss = { showQueueSheet = false },
            )
        }

        if (showOptionsSheet) {
            PlayerOptionsSheet(
                song = song,
                playlists = playlists,
                onDismiss = { showOptionsSheet = false },
                onAddToQueue = {
                    playerViewModel.addToQueueEnd(song)
                    showOptionsSheet = false
                },
                onAddToPlaylist = onAddToPlaylist,
                onCreatePlaylist = onCreatePlaylist,
                onShare = {
                    showOptionsSheet = false
                    showShareSheet = true
                },
                onArtworkMissing = { playerViewModel.getSongArtworkUrl(song) },
                isLoadingSmartQueue = isLoadingSmartQueue,
                onSelectSmartQueueReason = { reason ->
                    playerViewModel.loadSmartQueue(reason)
                    showOptionsSheet = false
                },
            )
        }

        if (showShareSheet) {
            ShareSheet(
                viewModel = shareViewModel,
                currentSongs = listOf(song),
                onDismiss = { showShareSheet = false },
                onPlayNow = { receivedSong ->
                    playerViewModel.play(listOf(receivedSong), 0)
                    showShareSheet = false
                },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}

@Composable
private fun BouncyIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pressScale: Float = 0.84f,
    hapticType: HapticFeedbackType = HapticFeedbackType.ContextClick,
    content: @Composable () -> Unit,
) {
    val haptic = rememberHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressScale else 1f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "bouncy_press",
    )
    IconButton(
        onClick = {
            haptic(hapticType)
            onClick()
        },
        interactionSource = interactionSource,
        modifier = modifier.scale(scale),
        content = content,
    )
}

@Composable
private fun PlayerHeader(
    onBack: () -> Unit,
    onQueueClick: () -> Unit,
    onSleepTimer: () -> Unit,
    sleepTimer: SleepTimer,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BouncyIconButton(onClick = onBack) {
            Icon(
                Icons.Rounded.KeyboardArrowDown, "Close",
                modifier = Modifier.size(32.dp),
            )
        }
        Text(
            text = "Now Playing",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        val sleepActive = sleepTimer !is SleepTimer.Off
        val sleepPulse by animateFloatAsState(
            targetValue = if (sleepActive) 1.10f else 1f,
            animationSpec = if (sleepActive)
                infiniteRepeatable(tween(1540, easing = EaseInOutSine), AnimationRepeatMode.Reverse)
            else MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "sleep_pulse",
        )
        BouncyIconButton(onClick = onSleepTimer, modifier = Modifier.scale(sleepPulse)) {
            Icon(
                Icons.Rounded.Bedtime, "Sleep Timer",
                modifier = Modifier.size(24.dp),
                tint = if (sleepActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
        }
        CastButton(modifier = Modifier.size(48.dp))
        BouncyIconButton(onClick = onQueueClick) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ArtworkPanel(
    song: Song,
    isPlaying: Boolean,
    artworkAnimation: Boolean,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    modifier: Modifier = Modifier,
    onArtworkMissing: (suspend () -> String?)? = null,
) {
    val config = LocalAppearanceConfig.current
    val isCircle = config.playerArtworkShape == "CIRCLE"
    val artworkCorner = when (config.playerArtworkShape) {
        "SQUARE" -> 4.dp
        else -> 28.dp
    }
    val artworkShape = if (isCircle) CircleShape else RoundedCornerShape(artworkCorner)
    val motion = MaterialTheme.motionScheme

    val scale = animateFloatAsState(
        targetValue = if (isPlaying || !artworkAnimation) 1f else 0.9f,
        animationSpec = motion.slowSpatialSpec(),
        label = "artwork_scale",
    )
    val elevation by animateDpAsState(
        targetValue = if (isPlaying) 20.dp else 4.dp,
        animationSpec = motion.slowEffectsSpec(),
        label = "artwork_elevation",
    )

    // Register with the sheet's artwork morph so the mini-player artwork can fly into place.
    val morph = LocalArtworkMorph.current
    SideEffect {
        morph?.apply {
            fullScale = { scale.value }
            fullCorner = artworkCorner
            fullIsCircle = isCircle
        }
    }

    // Horizontal swipe on the artwork skips tracks; vertical drags still reach the sheet.
    val swipe = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableFloatStateOf(1f) }
    val haptics = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        scope.launch { swipe.snapTo(swipe.value + delta) }
                    },
                    onDragEnd = {
                        scope.launch {
                            val o = swipe.value
                            if (abs(o) > widthPx * 0.25f) {
                                if (config.hapticEnabled) haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                swipe.animateTo(if (o < 0) -widthPx else widthPx, motion.fastSpatialSpec())
                                if (o < 0) onSwipeNext() else onSwipePrevious()
                                swipe.snapTo(if (o < 0) widthPx * 0.5f else -widthPx * 0.5f)
                            }
                            swipe.animateTo(0f, motion.defaultSpatialSpec())
                        }
                    },
                    onDragCancel = { scope.launch { swipe.animateTo(0f, motion.defaultSpatialSpec()) } },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .then(morph?.fullModifier() ?: Modifier)
                .graphicsLayer {
                    val s = scale.value
                    scaleX = s
                    scaleY = s
                    translationX = swipe.value
                    alpha = 1f - (abs(swipe.value) / widthPx).coerceIn(0f, 0.6f)
                    rotationZ = swipe.value / widthPx * 6f
                },
            shape = artworkShape,
            tonalElevation = elevation,
            shadowElevation = elevation,
        ) {
            ArtworkImage(
                uri = song.artworkUri,
                contentDescription = song.album,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 0.dp,
                onArtworkMissing = onArtworkMissing,
            )
        }
    }
}

@Composable
private fun PlaybackControls(
    song: Song,
    isPlaying: Boolean,
    position: State<Long>,
    durationMs: Long,
    repeatMode: RepeatMode,
    shuffleEnabled: Boolean,
    waveformData: WaveformData?,
    seekbarStyle: String,
    isTrackLoved: Boolean,
    nextSong: Song? = null,
    artworkColors: ArtworkComposeColors? = null,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onRepeatModeChange: () -> Unit,
    onShuffleToggle: () -> Unit,
    onLoveTrack: () -> Unit,
    onArtistClick: (String) -> Unit = {},
    onSongClick: () -> Unit = {},
) {
    // Doesn't read position.value; only SeekBarAndTimeLabels does.
    val config = LocalAppearanceConfig.current
    // The Seekbar Colour setting picks the artwork palette channel; without artwork the
    // matching theme color is used.
    val seekbarPlayedColor by animateColorAsState(
        targetValue = when (config.seekbarColor) {
            "SECONDARY" -> artworkColors?.muted ?: MaterialTheme.colorScheme.secondary
            "TERTIARY" -> artworkColors?.darkVibrant ?: MaterialTheme.colorScheme.tertiary
            else -> artworkColors?.vibrant ?: MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(560),
        label = "seekbar_accent",
    )
    val playButtonColor by animateColorAsState(
        targetValue = artworkColors?.vibrant ?: MaterialTheme.colorScheme.primary,
        animationSpec = tween(560),
        label = "play_button_accent",
    )
    val playButtonContentColor by animateColorAsState(
        targetValue = artworkColors?.onVibrant ?: MaterialTheme.colorScheme.onPrimary,
        animationSpec = tween(560),
        label = "play_button_content_accent",
    )
    val nextSongAccent by animateColorAsState(
        targetValue = artworkColors?.vibrant?.copy(alpha = 0.75f) ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        animationSpec = tween(560),
        label = "next_song_accent",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = song.title to song.displayArtist,
                transitionSpec = {
                    (fadeIn(tween(390, delayMillis = 110, easing = EmphasizedDecelerate)) +
                        slideInVertically(tween(450, easing = EmphasizedDecelerate)) { (it * 0.12f).toInt() })
                        .togetherWith(fadeOut(tween(170, easing = EmphasizedAccelerate)))
                },
                label = "song_info",
                modifier = Modifier.weight(1f),
            ) { (title, _) ->
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = onSongClick),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    MultiArtistText(
                        artists = song.artists,
                        onArtistClick = onArtistClick,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (config.showBitrateInfo && song.mimeType.isNotBlank()) {
                        val fmt = audioFormatLabel(song.mimeType)
                        val label = if (song.bitrate > 0) "$fmt · ${song.bitrate}kbps" else fmt
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            val loveScale by animateFloatAsState(
                targetValue = if (isTrackLoved) 1.18f else 1f,
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                label = "love_scale",
            )
            val loveColor by animateColorAsState(
                targetValue = if (isTrackLoved) BrandColors.loved
                else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                label = "love_color",
            )
            BouncyIconButton(
                onClick = onLoveTrack,
                modifier = Modifier.scale(loveScale),
                hapticType = if (isTrackLoved) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
            ) {
                Icon(
                    imageVector = if (isTrackLoved) Icons.Rounded.Favorite
                    else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (isTrackLoved) "Unlike" else "Like",
                    tint = loveColor,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        SeekBarAndTimeLabels(
            position = position,
            durationMs = durationMs,
            seekbarStyle = seekbarStyle,
            waveformData = waveformData,
            isPlaying = isPlaying,
            playedColor = seekbarPlayedColor,
            showRemainingTime = config.showRemainingTime,
            onSeek = onSeek,
        )

        if (config.showNextSongInPlayer && nextSong != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.SkipNext,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = nextSongAccent,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "Next: ${nextSong.title}",
                    style = MaterialTheme.typography.labelSmall,
                    color = nextSongAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        TransportControls(
            isPlaying = isPlaying,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            playButtonColor = playButtonColor,
            playButtonContentColor = playButtonContentColor,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPrevious = onPrevious,
            onShuffleToggle = onShuffleToggle,
            onRepeatModeChange = onRepeatModeChange,
        )
    }
}

/** Shuffle, previous, play/pause, next and repeat. The play button morphs between a slowly
 *  rotating cookie while playing and a rounded square while paused. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TransportControls(
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    playButtonColor: Color,
    playButtonContentColor: Color,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatModeChange: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val morph = remember { Morph(MaterialShapes.Square, MaterialShapes.Cookie9Sided) }
    // Morph, spin and press scale are all read inside graphicsLayer below, so the per-frame
    // spin redraws one layer instead of recomposing the controls row.
    val morphProgress = animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = motion.defaultSpatialSpec(),
        label = "play_morph",
    )
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            // Slow continuous spin while playing; picks up from where it stopped.
            while (true) {
                rotation.animateTo(rotation.value + 360f, tween(24_000, easing = LinearEasing))
            }
        }
    }
    val haptic = rememberHaptics()
    val playInteraction = remember { MutableInteractionSource() }
    val pressed by playInteraction.collectIsPressedAsState()
    val pressScale = animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = motion.fastSpatialSpec(),
        label = "play_press",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconToggleButton(
            checked = shuffleEnabled,
            onCheckedChange = {
                haptic(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onShuffleToggle()
            },
            shapes = IconButtonDefaults.toggleableShapes(),
            colors = IconButtonDefaults.iconToggleButtonColors(
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(Icons.Rounded.Shuffle, "Shuffle")
        }

        FilledTonalIconButton(
            onClick = {
                haptic(HapticFeedbackType.ContextClick)
                onPrevious()
            },
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(56.dp),
        ) {
            Icon(Icons.Rounded.SkipPrevious, "Previous", modifier = Modifier.size(30.dp))
        }

        Box(
            modifier = Modifier
                .size(84.dp)
                .graphicsLayer {
                    val p = morphProgress.value
                    scaleX = pressScale.value
                    scaleY = pressScale.value
                    shape = MorphShape(morph, p, rotation.value % 360f * p)
                    clip = true
                }
                .background(playButtonColor)
                .clickable(
                    interactionSource = playInteraction,
                    indication = ripple(),
                    onClick = {
                        haptic(HapticFeedbackType.Confirm)
                        onPlayPause()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                    (scaleIn(motion.fastSpatialSpec(), initialScale = 0.5f) + fadeIn(motion.fastEffectsSpec())) togetherWith
                        (scaleOut(motion.fastSpatialSpec(), targetScale = 0.5f) + fadeOut(motion.fastEffectsSpec()))
                },
                label = "play_pause_icon",
            ) { playing ->
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = playButtonContentColor,
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        FilledTonalIconButton(
            onClick = {
                haptic(HapticFeedbackType.ContextClick)
                onNext()
            },
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(56.dp),
        ) {
            Icon(Icons.Rounded.SkipNext, "Next", modifier = Modifier.size(30.dp))
        }

        IconToggleButton(
            checked = repeatMode != RepeatMode.NONE,
            onCheckedChange = {
                haptic(HapticFeedbackType.SegmentTick)
                onRepeatModeChange()
            },
            shapes = IconButtonDefaults.toggleableShapes(),
            colors = IconButtonDefaults.iconToggleButtonColors(
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(
                if (repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                "Repeat",
            )
        }
    }
}

// The only part of the player that reads the live position, so only it recomposes on ticks.
@Composable
private fun SeekBarAndTimeLabels(
    position: State<Long>,
    durationMs: Long,
    seekbarStyle: String,
    waveformData: WaveformData?,
    isPlaying: Boolean,
    playedColor: Color,
    showRemainingTime: Boolean,
    onSeek: (Long) -> Unit,
) {
    // Whole seconds only: the labels recompose once a second, not on every position tick.
    val positionSec by remember(position) { derivedStateOf { position.value / 1000 } }

    when (seekbarStyle) {
        "WAVEFORM" -> {
            WaveformSeekbar(
                waveformData = waveformData,
                position = position,
                durationMs = durationMs,
                onSeek = onSeek,
                isPlaying = isPlaying,
                playedColor = playedColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )
        }
        "MATERIAL_YOU_3" -> {
            MaterialYou3Seekbar(
                position = position,
                durationMs = durationMs,
                onSeek = onSeek,
                playedColor = playedColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )
        }
        else -> {
            Slider(
                value = position.value.toFloat(),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                colors = SliderDefaults.colors(
                    thumbColor = playedColor,
                    activeTrackColor = playedColor,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            formatDuration(positionSec * 1000),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (showRemainingTime && durationMs > 0)
                "-${formatDuration((durationMs - positionSec * 1000).coerceAtLeast(0))}"
            else formatDuration(durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerFooter(
    lyricsResult: LyricsResult,
    showLyricsButton: Boolean,
    onLyricsClick: () -> Unit,
    onLyricsEdit: () -> Unit,
    onMoreOptions: () -> Unit,
    onEqualizerClick: () -> Unit = {},
) {
    val footerConfig = LocalAppearanceConfig.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (footerConfig.showEqualizerInPlayer) {
            BouncyIconButton(onClick = onEqualizerClick) {
                Icon(
                    Icons.Rounded.GraphicEq, "Equalizer",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showLyricsButton) {
            val hasLyrics = lyricsResult is LyricsResult.Synced || lyricsResult is LyricsResult.Plain
            val isLoading = lyricsResult is LyricsResult.Loading
            val lyricsInteraction = remember { MutableInteractionSource() }
            val isLyricsPressed by lyricsInteraction.collectIsPressedAsState()
            val lyricsScale by animateFloatAsState(
                targetValue = if (isLyricsPressed) 0.84f else 1f,
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                label = "lyrics_press",
            )
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (hasLyrics && !isLoading)
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                else Color.Transparent,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(40.dp)
                    .scale(lyricsScale)
                    .combinedClickable(
                        interactionSource = lyricsInteraction,
                        indication = null,
                        onClickLabel = "Show lyrics",
                        onClick = onLyricsClick,
                        onLongClickLabel = "Edit lyrics",
                        onLongClick = onLyricsEdit,
                    ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isLoading) {
                        @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                        LoadingIndicator(modifier = Modifier.size(28.dp))
                    } else {
                        Icon(
                            imageVector = if (hasLyrics) Icons.Rounded.MicExternalOn else Icons.Rounded.MicExternalOff,
                            contentDescription = "Lyrics",
                            modifier = Modifier.size(22.dp),
                            tint = if (hasLyrics) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }

        BouncyIconButton(onClick = onMoreOptions) {
            Icon(
                Icons.Rounded.MoreHoriz, "More options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class OptionsPage { Main, Playlist, Info, SmartQueue }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerOptionsSheet(
    song: Song,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: (Playlist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onShare: () -> Unit,
    onArtworkMissing: (suspend () -> String?)? = null,
    isLoadingSmartQueue: Boolean = false,
    onSelectSmartQueueReason: (SmartQueueReason) -> Unit = {},
) {
    var page by remember { mutableStateOf(OptionsPage.Main) }
    var showCreatePlaylist by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                // Back to the main page slides in from the left, sub-pages from the right.
                val direction = if (targetState == OptionsPage.Main) -1 else 1
                val enter = fadeIn(tween(490, easing = EmphasizedDecelerate)) +
                    slideInHorizontally(tween(560, easing = EmphasizedDecelerate)) { direction * it / 2 }
                val exit = fadeOut(tween(350, easing = EmphasizedAccelerate)) +
                    slideOutHorizontally(tween(420, easing = EmphasizedAccelerate)) { -direction * it / 2 }
                enter togetherWith exit
            },
            label = "options_page",
        ) { currentPage ->
            when (currentPage) {
                OptionsPage.Main -> SheetMainPage(
                    song = song,
                    onAddToQueue = onAddToQueue,
                    onOpenPlaylists = { page = OptionsPage.Playlist },
                    onOpenInfo = { page = OptionsPage.Info },
                    onOpenSmartQueue = { page = OptionsPage.SmartQueue },
                    onShare = onShare,
                    onArtworkMissing = onArtworkMissing,
                )
                OptionsPage.Playlist -> SheetPlaylistPage(
                    playlists = playlists,
                    showCreatePlaylist = showCreatePlaylist,
                    onShowCreate = { showCreatePlaylist = true },
                    onBack = { page = OptionsPage.Main },
                    onPlaylistSelected = { onAddToPlaylist(it); onDismiss() },
                    onCreatePlaylist = { name ->
                        onCreatePlaylist(name)
                        showCreatePlaylist = false
                    },
                )
                OptionsPage.Info -> SheetInfoPage(
                    song = song,
                    onBack = { page = OptionsPage.Main },
                    onArtworkMissing = onArtworkMissing,
                )
                OptionsPage.SmartQueue -> SheetSmartQueuePage(
                    onBack = { page = OptionsPage.Main },
                    isLoading = isLoadingSmartQueue,
                    onSelectReason = onSelectSmartQueueReason,
                )
            }
        }
    }
}

@Composable
private fun SheetMainPage(
    song: Song,
    onAddToQueue: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenSmartQueue: () -> Unit = {},
    onShare: () -> Unit,
    onArtworkMissing: (suspend () -> String?)? = null,
) {
    Column(modifier = Modifier.padding(bottom = 28.dp)) {
        ListItem(
            headlineContent = {
                Text(
                    song.title,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    song.displayArtist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                Surface(shape = MaterialTheme.shapes.small, tonalElevation = 2.dp) {
                    ArtworkImage(
                        uri = song.artworkUri,
                        contentDescription = song.album,
                        modifier = Modifier.size(52.dp),
                        cornerRadius = 10.dp,
                        onArtworkMissing = onArtworkMissing,
                    )
                }
            },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        ListItem(
            modifier = Modifier.clickable(onClick = onAddToQueue),
            headlineContent = { Text("Add to Queue") },
            leadingContent = {
                Icon(
                    Icons.AutoMirrored.Rounded.QueueMusic, null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )

        ListItem(
            modifier = Modifier.clickable(onClick = onOpenSmartQueue),
            headlineContent = { Text("Smart Queue") },
            leadingContent = {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                Icon(
                    Icons.Rounded.ChevronRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        ListItem(
            modifier = Modifier.clickable(onClick = onOpenPlaylists),
            headlineContent = { Text("Add to Playlist") },
            leadingContent = {
                Icon(
                    Icons.AutoMirrored.Rounded.PlaylistAdd, null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            trailingContent = {
                Icon(
                    Icons.Rounded.ChevronRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        ListItem(
            modifier = Modifier.clickable(onClick = onOpenInfo),
            headlineContent = { Text("Info") },
            leadingContent = {
                Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                Icon(
                    Icons.Rounded.ChevronRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        ListItem(
            modifier = Modifier.clickable(onClick = onShare),
            headlineContent = { Text("Share") },
            leadingContent = {
                Icon(Icons.Rounded.Share, null, tint = MaterialTheme.colorScheme.primary)
            },
        )
    }
}

private data class SmartQueueOption(val reason: SmartQueueReason, val label: String)

private val smartQueueOptions = listOf(
    SmartQueueOption(SmartQueueReason.RELATED_BY_HISTORY, "Related by history"),
    SmartQueueOption(SmartQueueReason.SIMILAR_RELEASE_DATE, "Similar release date"),
    SmartQueueOption(SmartQueueReason.SAME_GENRE, "Same genre"),
    SmartQueueOption(SmartQueueReason.SAME_ERA, "From this era"),
    SmartQueueOption(SmartQueueReason.RANDOM_DISCOVERY, "Random discovery"),
    SmartQueueOption(SmartQueueReason.MOST_PLAYED, "Most played"),
    SmartQueueOption(SmartQueueReason.LOST_MEMORIES, "Songs you haven't heard in a while"),
)

@Composable
private fun SheetSmartQueuePage(
    onBack: () -> Unit,
    isLoading: Boolean,
    onSelectReason: (SmartQueueReason) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 28.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(
                "Smart Queue",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).padding(end = 12.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        smartQueueOptions.forEach { option ->
            ListItem(
                modifier = Modifier.clickable(enabled = !isLoading) { onSelectReason(option.reason) },
                headlineContent = { Text(option.label) },
                leadingContent = {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                },
            )
        }
    }
}

@Composable
private fun SheetPlaylistPage(
    playlists: List<Playlist>,
    showCreatePlaylist: Boolean,
    onShowCreate: () -> Unit,
    onBack: () -> Unit,
    onPlaylistSelected: (Playlist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(bottom = 28.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(
                "Add to Playlist",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onShowCreate) { Icon(Icons.Rounded.Add, "New playlist") }
        }

        AnimatedVisibility(
            visible = showCreatePlaylist,
            enter = expandVertically(tween(420, easing = Emphasized)) + fadeIn(tween(420, easing = Emphasized)),
            exit = shrinkVertically(tween(420, easing = Emphasized)) + fadeOut(tween(280, easing = Emphasized)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onCreatePlaylist(newName.trim())
                            newName = ""
                        }
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("Create") }
            }
        }

        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No playlists yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            playlists.forEach { playlist ->
                ListItem(
                    modifier = Modifier.clickable { onPlaylistSelected(playlist) },
                    headlineContent = { Text(playlist.name) },
                    supportingContent = {
                        Text(
                            "${playlist.songCount} song${if (playlist.songCount == 1) "" else "s"}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.QueueMusic, null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SheetInfoPage(
    song: Song,
    onBack: () -> Unit,
    onArtworkMissing: (suspend () -> String?)? = null,
) {
    Column(modifier = Modifier.padding(bottom = 32.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(
                "Info",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
                ArtworkImage(
                    uri = song.artworkUri,
                    contentDescription = song.album,
                    modifier = Modifier.size(72.dp),
                    cornerRadius = 14.dp,
                    onArtworkMissing = onArtworkMissing,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
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
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))

        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            if (song.album.isNotBlank())
                SongInfoRow("Album", song.album)
            if (song.year > 0)
                SongInfoRow("Year", song.year.toString())
            if (song.genre.isNotBlank())
                SongInfoRow("Genre", song.genre)
            if (song.albumArtist.isNotBlank() && song.albumArtist != song.artist)
                SongInfoRow("Album artist", song.albumArtist)
            if (song.trackNumber > 0) {
                val track = if (song.discNumber > 1)
                    "Disc ${song.discNumber}  ·  Track ${song.trackNumber}"
                else "Track ${song.trackNumber}"
                SongInfoRow("Track", track)
            }
            SongInfoRow("Duration", formatDuration(song.duration))
            SongInfoRow("File size", if (song.size > 0) formatFileSize(song.size) else "—")
            if (song.bitrate > 0)
                SongInfoRow("Bitrate", "${song.bitrate} kbps")
            if (song.sampleRate > 0)
                SongInfoRow("Sample rate", formatSampleRate(song.sampleRate))
            val fmt = formatMimeType(song.mimeType)
            if (fmt.isNotBlank())
                SongInfoRow("Format", fmt)
            if (song.path.isNotBlank())
                SongInfoRow("Path", song.path, isPath = true)
        }
    }
}

@Composable
private fun SongInfoRow(
    label: String,
    value: String,
    isPath: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = if (isPath) Alignment.Top else Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
            maxLines = if (isPath) 4 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DynamicBackground(
    artworkUri: Uri?,
    blurBackground: Boolean,
    blurStrength: Float,
) {
    val surface = MaterialTheme.colorScheme.surface
    if (!blurBackground) {
        Box(modifier = Modifier.fillMaxSize().background(surface))
        return
    }
    val context = LocalContext.current
    val motion = MaterialTheme.motionScheme

    // Blurs a quarter-size layer scaled up 4x: it looks the same and has 16x fewer pixels to
    // process. Below API 31 there's no blur, and the scaled-up thumbnail stands in for it.
    BoxWithConstraints(Modifier.fillMaxSize().background(surface)) {
        val factor = 4
        val layerW = maxWidth / factor
        val layerH = maxHeight / factor
        val blurRadius = (blurStrength * 200).dp / factor
        Crossfade(
            targetState = artworkUri,
            animationSpec = motion.slowEffectsSpec(),
            label = "bg_artwork",
        ) { uri ->
            Box(
                Modifier
                    .size(layerW, layerH)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = factor.toFloat()
                        scaleY = factor.toFloat()
                        alpha = 0.38f
                    }
            ) {
                AsyncImage(
                    model = remember(uri) {
                        coil.request.ImageRequest.Builder(context).data(uri).size(96).build()
                    },
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(blurRadius, BlurredEdgeTreatment.Rectangle),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LyricsOverlay(
    result: LyricsResult,
    activeLine: StateFlow<Int>,
    position: StateFlow<Long>,
    fontScale: Float,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onSearch: () -> Unit,
) {
    val config = LocalAppearanceConfig.current
    val textAlign = if (config.lyricAlignment == "CENTER") TextAlign.Center else TextAlign.Start
    val listState = rememberLazyListState()
    val clipboard = LocalClipboard.current
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // Collected here, not in PlayerScreen, so a line change only recomposes the overlay.
    val activeLineIndex by activeLine.collectAsStateWithLifecycle()

    val lyrics: List<LyricLine> = remember(result) {
        when (result) {
            is LyricsResult.Synced -> result.lines
            is LyricsResult.Plain -> result.lines.map { l -> LyricLine(timeMs = -1L, text = l) }
            else -> emptyList()
        }
    }
    val synced = result is LyricsResult.Synced

    // Auto-scroll follows the song, but steps aside while the user scrolls the lyrics; it
    // resumes 3 s after they stop, or right away from the "Sync" button.
    var programmaticScroll by remember { mutableStateOf(false) }
    var userScrolledAt by remember { mutableLongStateOf(0L) }
    var followSong by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !programmaticScroll) {
                followSong = false
                userScrolledAt = System.currentTimeMillis()
            }
        }
    }
    LaunchedEffect(followSong, userScrolledAt) {
        if (!followSong) {
            delay(3_000)
            if (!listState.isScrollInProgress) followSong = true
        }
    }
    // First positioning after the lyrics (re)load jumps; later line changes glide.
    var positioned by remember(result) { mutableStateOf(false) }
    LaunchedEffect(activeLineIndex, followSong, result) {
        if (!synced || !followSong || activeLineIndex < 0) return@LaunchedEffect
        val viewport = listState.layoutInfo.viewportSize.height
        programmaticScroll = true
        try {
            if (positioned) listState.animateScrollToItem(activeLineIndex, scrollOffset = -(viewport / 3))
            else listState.scrollToItem(activeLineIndex, scrollOffset = -(viewport / 3))
            positioned = true
        } finally {
            programmaticScroll = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, "Close lyrics")
                    }
                }

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        result is LyricsResult.Loading -> ContainedLoadingIndicator()
                        result is LyricsResult.Error -> LyricsMessage(
                            icon = Icons.Rounded.ErrorOutline,
                            title = "Couldn't load lyrics",
                            action = "Retry",
                            onAction = onRetry,
                        )
                        result is LyricsResult.Instrumental -> LyricsMessage(
                            icon = Icons.Rounded.MusicNote,
                            title = "Instrumental",
                        )
                        lyrics.isEmpty() -> LyricsMessage(
                            icon = Icons.Rounded.MicExternalOff,
                            title = "No lyrics found for this track",
                            action = "Search or add lyrics",
                            onAction = onSearch,
                        )
                        else -> {
                            val lyricsSpacing = when (config.lyricsLineSpacing) {
                                "COMPACT" -> 16.dp
                                "SPACIOUS" -> 44.dp
                                else -> 28.dp
                            }
                            val lyricsFontFamily = when (config.lyricsFontFamily) {
                                "ROUNDED" -> DmSans
                                "SERIF" -> FontFamily.Serif
                                "MONOSPACE" -> FontFamily.Monospace
                                else -> null
                            }
                            // One text style for every line; the active line is scaled and
                            // tinted in graphicsLayer, so a line change never re-measures.
                            val baseStyle = MaterialTheme.typography.headlineSmall
                            val lineStyle = remember(baseStyle, fontScale, lyricsFontFamily) {
                                baseStyle.copy(
                                    fontSize = baseStyle.fontSize * fontScale,
                                    lineHeight = baseStyle.lineHeight * fontScale,
                                    fontFamily = lyricsFontFamily ?: baseStyle.fontFamily,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            val translationStyle = MaterialTheme.typography.titleMedium.copy(
                                fontSize = MaterialTheme.typography.titleMedium.fontSize * fontScale,
                                fontFamily = lyricsFontFamily ?: MaterialTheme.typography.titleMedium.fontFamily,
                            )
                            val activeColor = MaterialTheme.colorScheme.primary
                            val idleColor = MaterialTheme.colorScheme.onSurface
                            val motion = MaterialTheme.motionScheme
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 100.dp),
                                verticalArrangement = Arrangement.spacedBy(lyricsSpacing),
                            ) {
                                itemsIndexed(
                                    lyrics,
                                    key = { index, line -> "$index-${line.timeMs}" },
                                    contentType = { _, line -> if (line.isGap) "gap" else "line" },
                                ) { index, line ->
                                    val isActive = synced && index == activeLineIndex
                                    val emphasis by animateFloatAsState(
                                        targetValue = if (isActive) 1f else 0f,
                                        animationSpec = motion.defaultSpatialSpec(),
                                        label = "lyric_emphasis",
                                    )
                                    val lineAlpha = when {
                                        !synced || isActive -> 1f
                                        index < activeLineIndex -> 0.35f
                                        else -> 0.55f
                                    }
                                    val color by animateColorAsState(
                                        targetValue = if (isActive) activeColor else idleColor.copy(alpha = lineAlpha),
                                        animationSpec = motion.defaultEffectsSpec(),
                                        label = "lyric_color",
                                    )
                                    val itemModifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer {
                                            val s = 1f + 0.06f * emphasis
                                            scaleX = s
                                            scaleY = s
                                            transformOrigin = if (textAlign == TextAlign.Center) TransformOrigin.Center else TransformOrigin(0f, 0.5f)
                                        }
                                        .combinedClickable(
                                            enabled = !line.isGap,
                                            onClick = { if (line.timeMs >= 0L) onSeek(line.timeMs) },
                                            onLongClick = {
                                                haptic(HapticFeedbackType.LongPress)
                                                scope.launch {
                                                    clipboard.setClipEntry(
                                                        ClipEntry(
                                                            ClipData.newPlainText("Lyrics", line.text)
                                                        )
                                                    )
                                                    snackbar.showSnackbar("Line copied")
                                                }
                                            },
                                        )

                                    if (line.isGap) {
                                        // Instrumental break: a quiet marker instead of an empty line.
                                        Text(
                                            "♪",
                                            style = lineStyle,
                                            color = color,
                                            textAlign = textAlign,
                                            modifier = itemModifier,
                                        )
                                    } else {
                                        Column(itemModifier) {
                                            if (isActive && line.wordTimings.isNotEmpty()) {
                                                KaraokeLine(
                                                    line = line,
                                                    position = position,
                                                    style = lineStyle,
                                                    sungColor = activeColor,
                                                    unsungColor = idleColor.copy(alpha = 0.55f),
                                                    textAlign = textAlign,
                                                )
                                            } else {
                                                Text(
                                                    text = line.text,
                                                    style = lineStyle,
                                                    color = color,
                                                    textAlign = textAlign,
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                            }
                                            line.translation?.let { translation ->
                                                Text(
                                                    text = translation,
                                                    style = translationStyle,
                                                    color = color.copy(alpha = color.alpha * 0.75f),
                                                    textAlign = textAlign,
                                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Shown while auto-scroll is paused because the user scrolled away.
            AnimatedVisibility(
                visible = synced && !followSong && lyrics.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
                enter = fadeIn() + scaleIn(initialScale = 0.9f),
                exit = fadeOut() + scaleOut(targetScale = 0.9f),
            ) {
                FilledTonalButton(onClick = { followSong = true }) {
                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sync")
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

/** The active line with word-level timing: sung words take the accent color as they're sung. */
@Composable
private fun KaraokeLine(
    line: LyricLine,
    position: StateFlow<Long>,
    style: TextStyle,
    sungColor: Color,
    unsungColor: Color,
    textAlign: TextAlign,
) {
    val positionMs by position.collectAsStateWithLifecycle()
    // Only the number of words sung so far drives recomposition, not every position tick.
    val sung by remember(line) {
        derivedStateOf { line.wordTimings.count { it.startMs <= positionMs } }
    }
    val text = remember(line, sung) {
        buildAnnotatedString {
            line.wordTimings.forEachIndexed { i, word ->
                if (i > 0) append(" ")
                val start = length
                append(word.word)
                addStyle(SpanStyle(color = if (i < sung) sungColor else unsungColor), start, length)
            }
        }
    }
    Text(text = text, style = style, textAlign = textAlign, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun LyricsMessage(
    icon: ImageVector,
    title: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

private fun formatSampleRate(hz: Int): String =
    if (hz >= 1000) "${DecimalFormat("#.#").format(hz / 1000.0)} kHz" else "$hz Hz"

private fun formatMimeType(mime: String): String = when (mime.lowercase()) {
    "audio/mpeg" -> "MP3"
    "audio/flac", "audio/x-flac" -> "FLAC"
    "audio/ogg" -> "OGG Vorbis"
    "audio/opus" -> "Opus"
    "audio/aac" -> "AAC"
    "audio/mp4", "audio/m4a" -> "AAC / M4A"
    "audio/x-wav", "audio/wav" -> "WAV"
    "audio/x-aiff" -> "AIFF"
    "audio/alac" -> "ALAC"
    "audio/x-ms-wma" -> "WMA"
    else -> mime.substringAfterLast('/').uppercase()
}
