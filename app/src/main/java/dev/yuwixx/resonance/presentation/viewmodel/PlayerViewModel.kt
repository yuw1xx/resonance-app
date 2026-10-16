package dev.yuwixx.resonance.presentation.viewmodel

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.cast.CastManager
import dev.yuwixx.resonance.cast.LocalAudioServer
import dev.yuwixx.resonance.data.database.dao.LikedSongsDao
import dev.yuwixx.resonance.data.database.entity.LikedSongEntity
import dev.yuwixx.resonance.data.model.*
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.data.repository.*
import dev.yuwixx.resonance.data.service.MusicService
import dev.yuwixx.resonance.data.service.NavidromePreloader
import dev.yuwixx.resonance.data.service.SessionProtocol
import dev.yuwixx.resonance.data.util.runCatchingCancellable
import dev.yuwixx.resonance.domain.usecase.ReplayGainProcessor
import dev.yuwixx.resonance.domain.usecase.WaveformExtractor
import java.io.File
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val activeLibrary: ActiveLibrary,
    val lyricsRepository: LyricsRepository,
    private val likedSongsDao: LikedSongsDao,
    private val lastFmRepository: LastFmRepository,
    private val malojaRepository: MalojaRepository,
    private val waveformExtractor: WaveformExtractor,
    val prefs: ResonancePreferences,
    private val navidromePreloader: NavidromePreloader,
    private val castManager: CastManager,
    private val localAudioServer: LocalAudioServer,
    private val artworkRepository: ArtworkRepository,
    private val replayGainProcessor: ReplayGainProcessor,
    private val navidromeDownloadRepository: NavidromeDownloadRepository,
    private val navidromeSyncRepository: NavidromeSyncRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    private var _controller: MediaController? = null

    // Songs of the active library by ID, rebuilt only when the library changes.
    private val songMap: StateFlow<Map<Long, Song>> = activeLibrary.songs
        .map { songs -> songs.associateBy { it.id } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // On a cold start the first library emission may still be in flight; wait briefly for it
    // rather than falling back to the bare MediaItem metadata.
    private suspend fun lookupSong(id: Long): Song? {
        songMap.value[id]?.let { return it }
        if (songMap.value.isNotEmpty()) return null
        return withTimeoutOrNull(2_000) { songMap.first { it.isNotEmpty() } }?.get(id)
    }

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.NONE)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _waveformData = MutableStateFlow<WaveformData?>(null)
    val waveformData: StateFlow<WaveformData?> = _waveformData.asStateFlow()

    private val _sleepTimer = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    val sleepTimer: StateFlow<SleepTimer> = _sleepTimer.asStateFlow()

    private val _lyricsResult = MutableStateFlow<LyricsResult>(LyricsResult.NotFound)
    val lyricsResult: StateFlow<LyricsResult> = _lyricsResult.asStateFlow()

    private val _activeLyricIndex = MutableStateFlow(-1)
    val activeLyricIndex: StateFlow<Int> = _activeLyricIndex.asStateFlow()

    // Follows the current song without an extra DB query.
    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    val isCurrentSongLiked: StateFlow<Boolean> = _currentSong
        .flatMapLatest { song ->
            if (song == null) flowOf(false)
            else likedSongsDao.getLikedSongIds().map { ids -> song.id in ids }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Accent colors sampled from the artwork for the seekbar, play button and next-song text.
    // Null when there's no artwork; callers then use the theme accent.
    val artworkColors: StateFlow<ArtworkComposeColors?> = artworkColorsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    private fun artworkColorsFlow(): Flow<ArtworkComposeColors?> = _currentSong
        .map { it?.albumId to it?.artworkUri }
        .distinctUntilChanged()
        .flatMapLatest { (albumId, uri) ->
            if (albumId == null || uri == null) flowOf<ArtworkComposeColors?>(null)
            else flow {
                emit(runCatchingCancellable { artworkRepository.extractArtworkColors(albumId, uri).toComposeColors() }.getOrNull())
            }
        }

    suspend fun getSongArtworkUrl(song: Song): String? {
        if (!prefs.fetchAlbumArt.first()) return null
        return artworkRepository.getSongArtworkUrl(song.albumId, song.title, song.displayArtist)
    }

    val partyMode = prefs.partyMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val dynamicColor = combine(
        prefs.dynamicColorEnabled,
        prefs.presetColor,
        prefs.partyMode,
    ) { enabled, preset, party ->
        when {
            party -> Color(0xFFFF1493.toInt())
            enabled || preset == null -> null
            else -> Color(preset)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val seekbarStyle = prefs.seekbarStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "WAVEFORM")

    val blurBackground = prefs.blurArtworkBackground
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val blurStrength = prefs.blurStrength
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.3f)

    val artworkAnimation = prefs.artworkAnimation
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val playerLayout = prefs.playerLayout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "STANDARD")

    val miniPlayerStyle = prefs.miniPlayerStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "CARD")

    val showLyricsButton = prefs.showLyricsButton
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val lyricsFontScale = prefs.lyricsFontScale
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)

    private var scrobbleTrackStartedAt: Long = 0L // reported to Last.fm and Maloja
    private var scrobbleSegmentStart: Long = 0L
    private var scrobbleAccumulatedMs: Long = 0L // played time across pauses
    private var scrobbleSubmittedForCurrentTrack = false

    val scrobblePct = prefs.lastFmScrobblePercent.stateIn(viewModelScope, SharingStarted.Eagerly, 0.5f)
    val scrobbleMinSecs = prefs.lastFmScrobbleMinSecs.stateIn(viewModelScope, SharingStarted.Eagerly, 30)

    val isCasting: StateFlow<Boolean> = castManager.isCasting

    // The queue while casting; the Media3 player is paused meanwhile.
    private val castQueue = mutableListOf<Song>()
    private var castQueueIndex = 0
    // The song auto-advance last fired for, so the 100 ms ticker advances once per track.
    private var castLastAdvancedSongId: Long = -1L

    // Declared before init{}: initializeController() and the queue sync read these synchronously.
    private val controllerListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            applySessionExtras(extras)
        }
    }

    private val _previousSong = MutableStateFlow<Song?>(null)
    /** The song a "previous" swipe would go to (shuffle-aware), for the mini player peek. */
    val previousSong: StateFlow<Song?> = _previousSong.asStateFlow()

    init {
        initializeController()
        startProgressTracker()
        observePreferences()
        observeCasting()
    }

    private fun observeCasting() {
        viewModelScope.launch {
            var wasCasting = false
            castManager.isCasting.collect { casting ->
                // The LAN server that feeds local files to the Cast device only needs to run
                // during a cast session.
                if (casting) localAudioServer.safeStart() else localAudioServer.safeStop()
                if (casting) {
                    wasCasting = true
                    // Hand off only if the local player is playing. When the app comes back to
                    // a running Cast session, the local player is already paused.
                    val song = _currentSong.value
                    val isLocallyActive = _controller?.isPlaying == true
                    if (song != null && isLocallyActive) {
                        val songs = _queue.value.ifEmpty { listOfNotNull(song) }
                        val idx = _currentQueueIndex.value.coerceAtLeast(0)
                        val pos = _positionMs.value
                        localAudioServer.registerSongs(songs)
                        castQueue.clear()
                        castQueue.addAll(songs)
                        castQueueIndex = idx
                        castLastAdvancedSongId = -1L
                        _controller?.pause()
                        castManager.castSong(song.castUrl(), song, pos, song.castArtworkUrl())
                    }
                } else if (wasCasting) {
                    wasCasting = false
                    val lastPos = _positionMs.value
                    // _isPlaying still holds the Cast state here; read it before ctrl.play()
                    // replaces it with the local controller's.
                    val shouldResume = _isPlaying.value
                    // Resume the local player from where Cast left off.
                    _controller?.let { ctrl ->
                        if (lastPos > 0) ctrl.seekTo(lastPos)
                        if (shouldResume) ctrl.play()
                        _isPlaying.value = ctrl.isPlaying
                    }
                }
            }
        }
        viewModelScope.launch {
            castManager.castIsPlaying.collect { playing ->
                if (castManager.isCasting.value) {
                    _isPlaying.value = playing
                }
            }
        }
    }

    private fun Song.castUrl(): String =
        if (uri.scheme == "content") localAudioServer.getUrl(this) else uri.toString()

    private fun Song.castArtworkUrl(): String? = localAudioServer.getArtworkUrl(this)

    private fun observePreferences() {
        viewModelScope.launch {
            combine(prefs.playbackSpeed, prefs.playbackPitch) { speed, pitch ->
                PlaybackParameters(speed, pitch)
            }.collect { params ->
                _controller?.playbackParameters = params
            }
        }
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null

    @OptIn(UnstableApi::class)
    private fun initializeController() {
        val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
        val future = MediaController.Builder(context, sessionToken)
            .setListener(controllerListener)
            .buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val controller = try {
                    future.get()
                } catch (e: Exception) {
                    // Cancelled from onCleared(), or the service refused the connection.
                    Log.w("PlayerViewModel", "MediaController connection failed", e)
                    return@addListener
                }
                _controller = controller
                controller.addListener(playerListener)
                applySessionExtras(controller.sessionExtras)
                syncStateWithController()
                // Preferences emit only on change; the first value may have arrived before
                // the controller existed.
                viewModelScope.launch {
                    controller.playbackParameters = PlaybackParameters(
                        prefs.playbackSpeed.first(),
                        prefs.playbackPitch.first(),
                    )
                }
            },
            // Main thread: the listener touches the controller, which must be used on its
            // application looper.
            ContextCompat.getMainExecutor(context)
        )
    }

    /** Waveforms are only decoded when the waveform seekbar style is in use. */
    private suspend fun extractWaveform(songId: Long, uri: Uri) =
        if (prefs.seekbarStyle.first() == "WAVEFORM") waveformExtractor.extract(songId, uri) else null

    private fun startProgressTracker() {
        // Ticks only while playing and while the UI collects position or lyrics. Casting keeps it
        // running because Cast auto-advance is driven from here.
        val uiWatching = combine(
            _positionMs.subscriptionCount,
            _activeLyricIndex.subscriptionCount,
        ) { position, lyrics -> position + lyrics > 0 }
        val shouldTick = combine(_isPlaying, uiWatching, castManager.isCasting) { playing, watching, casting ->
            casting || (playing && watching)
        }
        viewModelScope.launch {
            while (isActive) {
                shouldTick.first { it }
                if (castManager.isCasting.value) {
                    val pos = castManager.getCurrentPosition()
                    val dur = castManager.getStreamDuration()
                    _positionMs.value = pos
                    if (dur > 0) _durationMs.value = dur
                    // checkScrobbleThreshold relies on castIsPlaying, which the collector in
                    // init{} keeps in sync with _isPlaying.
                    if (castManager.castIsPlaying.value && dur > 0) {
                        updateActiveLyricIndex(pos)
                        checkScrobbleThreshold(pos, dur)
                    }
                    // Auto-advance when the Cast track finishes, once per track.
                    val currentId = _currentSong.value?.id ?: -1L
                    if (dur > 0 && pos >= dur - 1500 && !castManager.castIsPlaying.value
                        && pos > 0 && castLastAdvancedSongId != currentId
                    ) {
                        castLastAdvancedSongId = currentId
                        val nextIndex = castQueueIndex + 1
                        if (nextIndex < castQueue.size) {
                            castQueueIndex = nextIndex
                            val next = castQueue[nextIndex]
                            _currentSong.value = next
                            _currentQueueIndex.value = nextIndex
                            loadLyricsAndWaveform(next)
                            castManager.castSong(next.castUrl(), next, artworkUrl = next.castArtworkUrl())
                        }
                    }
                } else {
                    _controller?.let { ctrl ->
                        val rawDuration = ctrl.duration
                        if (rawDuration != C.TIME_UNSET && rawDuration > 0) {
                            _durationMs.value = rawDuration
                        }
                        val rawPosition = ctrl.currentPosition
                        if (rawPosition != C.TIME_UNSET) {
                            _positionMs.value = rawPosition.coerceAtLeast(0)
                        }
                        // Local scrobbling/history live in MusicService (ScrobbleTracker).
                        if (ctrl.isPlaying) updateActiveLyricIndex(ctrl.currentPosition)
                    }
                }
                delay(if (castManager.isCasting.value) 500 else 100)
            }
        }
    }

    private fun checkScrobbleThreshold(positionMs: Long, durationMs: Long) {
        if (scrobbleSubmittedForCurrentTrack) return
        val song = _currentSong.value ?: return
        if (!_isPlaying.value) return
        if (durationMs <= 0L) return

        val currentSegmentMs = if (_isPlaying.value && scrobbleSegmentStart > 0L)
            System.currentTimeMillis() - scrobbleSegmentStart else 0L
        val listenedMs = scrobbleAccumulatedMs + currentSegmentMs
        val minSecs = scrobbleMinSecs.value
        val minPct = scrobblePct.value

        val meetsTimeCriteria = listenedMs >= minSecs * 1000L
        val meetsPctCriteria = positionMs.toFloat() / durationMs >= minPct

        if (meetsTimeCriteria && meetsPctCriteria) {
            scrobbleSubmittedForCurrentTrack = true
            lastFmRepository.scrobble(song, scrobbleTrackStartedAt)
            malojaRepository.scrobble(song, scrobbleTrackStartedAt)

            viewModelScope.launch {
                musicRepository.recordListen(song.id, listenedMs, durationMs)
            }
        }
    }

    private fun updateActiveLyricIndex(positionMs: Long) {
        val result = _lyricsResult.value
        if (result is LyricsResult.Synced) {
            // Last line starting at or before the position (lines are sorted by time).
            val lines = result.lines
            var lo = 0
            var hi = lines.lastIndex
            var activeIndex = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (lines[mid].timeMs <= positionMs) { activeIndex = mid; lo = mid + 1 } else hi = mid - 1
            }
            if (activeIndex != _activeLyricIndex.value) {
                _activeLyricIndex.value = activeIndex
            }
        }
    }

    fun play(songs: List<Song>, startIndex: Int) {
        castQueue.clear()
        castQueue.addAll(songs)
        castQueueIndex = startIndex

        if (castManager.isCasting.value) {
            localAudioServer.registerSongs(songs)
            val song = songs.getOrNull(startIndex) ?: return
            _currentSong.value = song
            _queue.value = songs
            _currentQueueIndex.value = startIndex
            loadLyricsAndWaveform(song)
            castLastAdvancedSongId = -1L
            castManager.castSong(song.castUrl(), song, artworkUrl = song.castArtworkUrl())
            _controller?.pause()
        } else {
            // Decode the waveform of this song and the next one while ExoPlayer prepares, so the
            // first song of a queue doesn't wait for onMediaItemTransition.
            songs.getOrNull(startIndex)?.let { song ->
                viewModelScope.launch(Dispatchers.IO) { extractWaveform(song.id, song.uri) }
                prefetchReplayGain(song)
            }
            songs.getOrNull(startIndex + 1)?.let { song ->
                viewModelScope.launch(Dispatchers.IO) { extractWaveform(song.id, song.uri) }
                prefetchReplayGain(song)
            }

            _controller?.let { ctrl ->
                navidromePreloader.reset()
                ctrl.setMediaItems(songs.map { it.toMediaItem() }, startIndex, 0)
                ctrl.prepare()
                ctrl.play()
            }
        }
    }

    // Shuffled here so the first song is random too, then shuffle mode stays on.
    fun playShuffled(songs: List<Song>) {
        if (songs.isEmpty()) return
        play(songs.shuffled(), 0)
        _controller?.shuffleModeEnabled = true
    }

    fun playPause() {
        if (castManager.isCasting.value) {
            castManager.playPause()
        } else {
            _controller?.let {
                if (it.isPlaying) it.pause() else it.play()
            }
        }
    }

    fun skipNext() {
        if (castManager.isCasting.value) {
            val newIndex = castQueueIndex + 1
            if (newIndex < castQueue.size) {
                castQueueIndex = newIndex
                val song = castQueue[newIndex]
                _currentSong.value = song
                _currentQueueIndex.value = newIndex
                loadLyricsAndWaveform(song)
                castLastAdvancedSongId = -1L
                castManager.castSong(song.castUrl(), song, artworkUrl = song.castArtworkUrl())
            }
        } else {
            _controller?.seekToNextMediaItem()
        }
    }

    fun skipPrevious() {
        if (castManager.isCasting.value) {
            val newIndex = (castQueueIndex - 1).coerceAtLeast(0)
            castQueueIndex = newIndex
            val song = castQueue.getOrNull(newIndex) ?: return
            _currentSong.value = song
            _currentQueueIndex.value = newIndex
            loadLyricsAndWaveform(song)
            castLastAdvancedSongId = -1L
            castManager.castSong(song.castUrl(), song, artworkUrl = song.castArtworkUrl())
        } else {
            _controller?.seekToPreviousMediaItem()
        }
    }

    /** The live position from the player, for when the UI ticker isn't running. */
    fun currentPositionMs(): Long =
        if (castManager.isCasting.value) castManager.getCurrentPosition()
        else _controller?.currentPosition?.coerceAtLeast(0) ?: _positionMs.value

    fun seekTo(position: Long) {
        if (castManager.isCasting.value) {
            castManager.seekTo(position)
        } else {
            _controller?.seekTo(position)
        }
        _positionMs.value = position.coerceAtLeast(0)
        updateActiveLyricIndex(position)
    }

    fun toggleRepeat() {
        _controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun toggleShuffle() {
        _controller?.let {
            it.shuffleModeEnabled = !it.shuffleModeEnabled
        }
    }

    fun toggleLike() {
        val song = _currentSong.value ?: return
        viewModelScope.launch {
            val isLiked = likedSongsDao.isLiked(song.id) > 0
            if (isLiked) {
                likedSongsDao.unlikeSong(song.id)
                song.navidromeId?.let { navidromeSyncRepository.unstar(song.id, it) }
            } else {
                likedSongsDao.likeSong(LikedSongEntity(songId = song.id, likedAt = System.currentTimeMillis()))
                song.navidromeId?.let { navidromeSyncRepository.star(song.id, it) }
            }
        }
    }

    fun reloadLyrics() {
        val song = _currentSong.value ?: return
        viewModelScope.launch {
            _lyricsResult.value = LyricsResult.Loading
            _lyricsResult.value = lyricsRepository.getLyrics(
                songId = song.id,
                title = song.title,
                artist = song.displayArtist,
                album = song.album,
                durationMs = song.duration,
                songUri = song.uri,
                songPath = song.path,
                navidromeId = song.navidromeId,
                forceRefresh = true,
            )
        }
    }

    /** A queue change the app-wide snackbar can offer to undo. */
    class UndoableEvent(val message: String, val undo: () -> Unit)

    private val _undoEvents = MutableSharedFlow<UndoableEvent>(extraBufferCapacity = 4)
    val undoEvents: SharedFlow<UndoableEvent> = _undoEvents.asSharedFlow()

    // Several adds in a row (multi-select "play next") become one snackbar that undoes them all.
    private val pendingUndos = mutableListOf<Pair<String, () -> Unit>>()
    private var pendingVerb = ""
    private var undoFlushJob: Job? = null

    private fun offerUndo(title: String, verb: String, undo: () -> Unit) {
        if (pendingUndos.isNotEmpty() && verb != pendingVerb) flushUndo()
        pendingVerb = verb
        pendingUndos += title to undo
        undoFlushJob?.cancel()
        undoFlushJob = viewModelScope.launch {
            delay(150)
            flushUndo()
        }
    }

    private fun flushUndo() {
        if (pendingUndos.isEmpty()) return
        val batch = pendingUndos.toList()
        pendingUndos.clear()
        val message = if (batch.size == 1) "“${batch[0].first}” $pendingVerb" else "${batch.size} songs $pendingVerb"
        _undoEvents.tryEmit(UndoableEvent(message) { batch.asReversed().forEach { it.second() } })
    }

    fun addToQueueEnd(song: Song) {
        _controller?.addMediaItem(song.toMediaItem())
        offerUndo(song.title, "added to queue") { removeQueuedSong(song.id) }
        viewModelScope.launch(Dispatchers.IO) { extractWaveform(song.id, song.uri) }
        prefetchReplayGain(song)
    }

    fun addToQueueNext(song: Song) {
        _controller?.let { ctrl ->
            val insertIndex = (ctrl.currentMediaItemIndex + 1)
                .coerceAtMost(ctrl.mediaItemCount)
            ctrl.addMediaItem(insertIndex, song.toMediaItem())
            offerUndo(song.title, "plays next") { removeQueuedSong(song.id, insertIndex) }
        }
        viewModelScope.launch(Dispatchers.IO) { extractWaveform(song.id, song.uri) }
        prefetchReplayGain(song)
    }

    /** Plays [songs] next, in the given order, as one queue change. */
    fun addToQueueNext(songs: List<Song>) {
        if (songs.isEmpty()) return
        _controller?.let { ctrl ->
            val insertIndex = (ctrl.currentMediaItemIndex + 1).coerceAtMost(ctrl.mediaItemCount)
            ctrl.addMediaItems(insertIndex, songs.map { it.toMediaItem() })
            songs.forEachIndexed { i, song ->
                offerUndo(song.title, "plays next") { removeQueuedSong(song.id, insertIndex + i) }
            }
        }
        songs.forEach(::prefetchReplayGain)
    }

    /** Appends [songs] to the queue, in the given order, as one queue change. */
    fun addToQueueEnd(songs: List<Song>) {
        if (songs.isEmpty()) return
        _controller?.addMediaItems(songs.map { it.toMediaItem() })
        songs.forEach { song -> offerUndo(song.title, "added to queue") { removeQueuedSong(song.id) } }
        songs.forEach(::prefetchReplayGain)
    }

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentQueueIndex = MutableStateFlow(0)
    val currentQueueIndex: StateFlow<Int> = _currentQueueIndex.asStateFlow()

    // The song that really plays next, following the shuffle order.
    private val _nextSong = MutableStateFlow<Song?>(null)
    val nextSong: StateFlow<Song?> = _nextSong.asStateFlow()

    // The queue in playback order for QueueSheet. Each entry keeps its window index so remove and
    // play-at act on the right item. _queue stays in window order for updateNextSong().
    data class QueueEntry(val index: Int, val song: Song)
    private val _orderedQueue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val orderedQueue: StateFlow<List<QueueEntry>> = _orderedQueue.asStateFlow()

    private fun updateNextSong() {
        val ctrl = _controller
        val nextIndex = ctrl?.nextMediaItemIndex ?: C.INDEX_UNSET
        _nextSong.value = if (nextIndex != C.INDEX_UNSET) _queue.value.getOrNull(nextIndex) else null
        val prevIndex = ctrl?.previousMediaItemIndex ?: C.INDEX_UNSET
        _previousSong.value = if (prevIndex != C.INDEX_UNSET) _queue.value.getOrNull(prevIndex) else null
    }

    // Walks the timeline the way ExoPlayer does for next/previous. REPEAT_MODE_OFF stops the walk
    // at either end instead of wrapping around forever with repeat-all on.
    private fun buildOrderedQueue(ctrl: MediaController, songs: List<Song>): List<QueueEntry> {
        val count = songs.size
        if (count == 0) return emptyList()
        val current = ctrl.currentMediaItemIndex
        if (!ctrl.shuffleModeEnabled || current == C.INDEX_UNSET) {
            return songs.mapIndexed { i, s -> QueueEntry(i, s) }
        }
        val timeline = ctrl.currentTimeline
        val visited = HashSet<Int>().apply { add(current) }
        val prefix = ArrayDeque<Int>()
        var idx = current
        while (prefix.size < count) {
            idx = timeline.getPreviousWindowIndex(idx, Player.REPEAT_MODE_OFF, true)
            if (idx == C.INDEX_UNSET || !visited.add(idx)) break
            prefix.addFirst(idx)
        }
        val suffix = mutableListOf<Int>()
        idx = current
        while (suffix.size < count) {
            idx = timeline.getNextWindowIndex(idx, Player.REPEAT_MODE_OFF, true)
            if (idx == C.INDEX_UNSET || !visited.add(idx)) break
            suffix.add(idx)
        }
        return (prefix.toList() + current + suffix).mapNotNull { i -> songs.getOrNull(i)?.let { QueueEntry(i, it) } }
    }

    // Seeks within the current queue; unlike play(), it keeps the playlist and shuffle order.
    fun playAtQueueIndex(index: Int) {
        _controller?.let { ctrl ->
            ctrl.seekTo(index, 0L)
            ctrl.play()
        }
    }

    fun clearQueue() {
        _controller?.clearMediaItems()
    }

    val likedSongIds: StateFlow<List<Long>> = likedSongsDao.getLikedSongIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoadingSmartQueue = MutableStateFlow(false)
    val isLoadingSmartQueue: StateFlow<Boolean> = _isLoadingSmartQueue.asStateFlow()

    private val _smartQueueError = MutableStateFlow<String?>(null)
    val smartQueueError: StateFlow<String?> = _smartQueueError.asStateFlow()

    fun clearSmartQueueError() {
        _smartQueueError.value = null
    }

    fun loadSmartQueue(reason: SmartQueueReason) {
        if (_isLoadingSmartQueue.value) return
        viewModelScope.launch {
            _smartQueueError.value = null
            _isLoadingSmartQueue.value = true
            try {
                val seedSong = _currentSong.value
                    ?: musicRepository.getMostPlayedSongs(1).firstOrNull()
                if (seedSong == null) {
                    _smartQueueError.value = "Add some songs to your library first."
                    return@launch
                }
                val result = musicRepository.generateSmartQueue(seedSong, reason)
                if (result.songs.isEmpty()) {
                    _smartQueueError.value = "No songs found for this queue type."
                } else if (_currentSong.value != null) {
                    _controller?.let { ctrl ->
                        val insertIndex = ctrl.currentMediaItemIndex + 1
                        result.songs.forEachIndexed { i, song ->
                            ctrl.addMediaItem(insertIndex + i, song.toMediaItem())
                            prefetchReplayGain(song)
                        }
                    }
                } else {
                    play(result.songs, 0)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _smartQueueError.value = e.message ?: "Failed to load smart queue."
            } finally {
                _isLoadingSmartQueue.value = false
            }
        }
    }

    // The timer runs in MusicService. These send it commands; _sleepTimer mirrors its state from
    // the session extras.
    fun setSleepTimer(timer: SleepTimer) {
        val ctrl = _controller ?: return
        when (timer) {
            SleepTimer.Off -> ctrl.sendCustomCommand(
                SessionCommand(SessionProtocol.CMD_SLEEP_CANCEL, Bundle.EMPTY),
                Bundle.EMPTY,
            )
            is SleepTimer.Time -> sendSleepSet(minutes = timer.minutes, tracks = 0, fade = timer.fadeOut)
            is SleepTimer.Tracks -> sendSleepSet(minutes = 0, tracks = timer.tracksLeft, fade = timer.fadeOut)
        }
    }

    fun setSleepAfterTracks(tracksLeft: Int, fadeOut: Boolean = false) {
        setSleepTimer(if (tracksLeft <= 0) SleepTimer.Off else SleepTimer.Tracks(tracksLeft, fadeOut))
    }

    private fun sendSleepSet(minutes: Int, tracks: Int, fade: Boolean) {
        val args = Bundle().apply {
            putInt(SessionProtocol.ARG_MINUTES, minutes)
            putInt(SessionProtocol.ARG_TRACKS, tracks)
            putBoolean(SessionProtocol.ARG_FADE, fade)
        }
        _controller?.sendCustomCommand(
            SessionCommand(SessionProtocol.CMD_SLEEP_SET, Bundle.EMPTY),
            args,
        )
    }

    private fun applySessionExtras(extras: Bundle) {
        val fade = extras.getBoolean(SessionProtocol.EXTRA_SLEEP_FADE, false)
        _sleepTimer.value = when (extras.getString(SessionProtocol.EXTRA_SLEEP_TYPE)) {
            "time" -> {
                val minutes = extras.getInt(SessionProtocol.EXTRA_SLEEP_MINUTES)
                val endsAt = extras.getLong(SessionProtocol.EXTRA_SLEEP_ENDS_AT)
                SleepTimer.Time(minutes, endsAt - minutes * 60_000L, fade)
            }
            "tracks" -> SleepTimer.Tracks(extras.getInt(SessionProtocol.EXTRA_SLEEP_TRACKS), fade)
            else -> SleepTimer.Off
        }
    }


    /** Moves a queue item between window indices. Only offered with shuffle off. */
    fun moveQueueItem(from: Int, to: Int) {
        val ctrl = _controller ?: return
        if (from == to || from !in 0 until ctrl.mediaItemCount || to !in 0 until ctrl.mediaItemCount) return
        ctrl.moveMediaItem(from, to)
    }

    /** Removes the item and returns what's needed to undo it. */
    fun removeFromQueueForUndo(index: Int): Pair<Int, Song>? {
        val song = _queue.value.getOrNull(index) ?: return null
        _controller?.removeMediaItem(index)
        return index to song
    }

    fun restoreQueueItem(index: Int, song: Song) {
        val ctrl = _controller ?: return
        ctrl.addMediaItem(index.coerceIn(0, ctrl.mediaItemCount), song.toMediaItem())
    }

    /** Undo for "play next" / "add to queue": removes the most recently inserted copy. */
    fun removeQueuedSong(songId: Long, nearIndex: Int? = null) {
        val ctrl = _controller ?: return
        val indices = (0 until ctrl.mediaItemCount).filter { ctrl.getMediaItemAt(it).mediaId == songId.toString() }
        val target = nearIndex?.let { n -> indices.minByOrNull { abs(it - n) } } ?: indices.lastOrNull()
        target?.takeIf { it != ctrl.currentMediaItemIndex }?.let { ctrl.removeMediaItem(it) }
    }

    /** Saves the queue in playback order as a new playlist; returns its id. */
    suspend fun saveQueueAsPlaylist(name: String): Long? {
        val ids = _orderedQueue.value.map { it.song.id }
        if (ids.isEmpty()) return null
        return playlistRepository.createPlaylistWithSongs(name, ids)
    }

    private val playerListener = object : Player.Listener {
        @OptIn(UnstableApi::class)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncStateWithController()
            // The new track starts from its own position, even while paused (no ticker runs).
            _positionMs.value = _controller?.currentPosition?.coerceAtLeast(0) ?: 0L
            if (mediaItem == null || (_controller?.mediaItemCount ?: 0) == 0) {
                // The queue was cleared.
                _currentSong.value = null
                _waveformData.value = null
                _lyricsResult.value = LyricsResult.NotFound
                _activeLyricIndex.value = -1
                return
            }
            val songId = mediaItem.mediaId.toLongOrNull()
            if (songId != null) {
                viewModelScope.launch {
                    val song = lookupSong(songId)
                        ?: mediaItem.toSong()
                    if (song != null) {
                        _currentSong.value = song
                        // Show a prefetched waveform right away instead of the placeholder.
                        _waveformData.value = waveformExtractor.peekCached(song.id)
                        _lyricsResult.value = LyricsResult.Loading
                        _activeLyricIndex.value = -1

                        // Load waveform and lyrics in parallel. A slow result for a song the
                        // user already skipped must not overwrite the current one.
                        launch {
                            val waveform = extractWaveform(song.id, song.uri)
                            if (_currentSong.value?.id == song.id) _waveformData.value = waveform
                        }
                        launch {
                            val lyrics = lyricsRepository.getLyrics(
                                songId = song.id,
                                title = song.title,
                                artist = song.displayArtist,
                                album = song.album,
                                durationMs = song.duration,
                                songUri = song.uri,
                                songPath = song.path,
                                navidromeId = song.navidromeId,
                            )
                            if (_currentSong.value?.id == song.id) _lyricsResult.value = lyrics
                        }

                        _controller?.let { ctrl ->
                            val nextIndex = ctrl.nextMediaItemIndex
                            if (nextIndex != C.INDEX_UNSET) {
                                val nextItem = ctrl.getMediaItemAt(nextIndex)
                                val nextId = nextItem.mediaId.toLongOrNull()
                                if (nextId != null) {
                                    launch(Dispatchers.IO) {
                                        val nextSong = lookupSong(nextId)
                                            ?: nextItem.toSong()
                                        if (nextSong != null) {
                                            extractWaveform(nextSong.id, nextSong.uri)
                                            lyricsRepository.getLyrics(
                                                songId = nextSong.id,
                                                title = nextSong.title,
                                                artist = nextSong.displayArtist,
                                                album = nextSong.album,
                                                durationMs = nextSong.duration,
                                                songUri = nextSong.uri,
                                                songPath = nextSong.path,
                                                navidromeId = nextSong.navidromeId,
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

        override fun onPlaybackStateChanged(playbackState: Int) {
            _controller?.let { ctrl ->
                val rawDuration = ctrl.duration
                if (rawDuration != C.TIME_UNSET && rawDuration > 0) {
                    _durationMs.value = rawDuration
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // While casting, the paused local player must not override the Cast play state.
            if (castManager.isCasting.value) return
            _isPlaying.value = isPlaying
            // A final position snapshot, since the ticker stops while paused.
            if (!isPlaying) _controller?.currentPosition?.let { _positionMs.value = it.coerceAtLeast(0) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.NONE
            }
            updateNextSong()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleEnabled.value = shuffleModeEnabled
            updateNextSong()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            syncQueueFromController()
        }
    }

    private fun syncStateWithController() {
        _controller?.let { ctrl ->
            _isPlaying.value = ctrl.isPlaying
            _repeatMode.value = when (ctrl.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.NONE
            }
            _shuffleEnabled.value = ctrl.shuffleModeEnabled
            _currentQueueIndex.value = ctrl.currentMediaItemIndex
        }
        syncQueueFromController()
    }

    private fun syncQueueFromController() {
        val ctrl = _controller ?: return
        viewModelScope.launch {
            val map = songMap.value
            val songs = (0 until ctrl.mediaItemCount).mapNotNull { i ->
                val item = ctrl.getMediaItemAt(i)
                val songId = item.mediaId.toLongOrNull() ?: return@mapNotNull null
                map[songId] ?: item.toSong()
            }
            _queue.value = songs
            _currentQueueIndex.value = ctrl.currentMediaItemIndex
            _orderedQueue.value = buildOrderedQueue(ctrl, songs)
            updateNextSong()
        }
    }

    private fun loadLyricsAndWaveform(song: Song) {
        viewModelScope.launch {
            _waveformData.value = null
            _lyricsResult.value = LyricsResult.Loading
            _activeLyricIndex.value = -1
            launch {
                val waveform = extractWaveform(song.id, song.uri)
                if (_currentSong.value?.id == song.id) _waveformData.value = waveform
            }
            launch {
                val lyrics = lyricsRepository.getLyrics(
                    songId = song.id,
                    title = song.title,
                    artist = song.displayArtist,
                    album = song.album,
                    durationMs = song.duration,
                    songUri = song.uri,
                    songPath = song.path,
                    navidromeId = song.navidromeId,
                )
                if (_currentSong.value?.id == song.id) _lyricsResult.value = lyrics
            }
            val now = System.currentTimeMillis()
            scrobbleTrackStartedAt = now
            scrobbleSegmentStart = now
            scrobbleAccumulatedMs = 0L
            scrobbleSubmittedForCurrentTrack = false
            lastFmRepository.updateNowPlaying(song)
        }
    }

    override fun onCleared() {
        _controller?.removeListener(playerListener)
        // Also releases a connection still in flight, which release() on a null controller would leak.
        controllerFuture?.let { MediaController.releaseFuture(it) } ?: _controller?.release()
        _controller = null
        localAudioServer.safeStop()
    }

    // A member so it can read replayGainProcessor's cache.
    private fun Song.toMediaItem(): MediaItem {
        val cached = replayGainProcessor.peekCachedGain(id)
        val trackGain = cached?.first ?: replayGainTrack
        val albumGain = cached?.second ?: replayGainAlbum
        val extras = Bundle().apply {
            trackGain?.let { putFloat("replayGainTrack", it) }
            albumGain?.let { putFloat("replayGainAlbum", it) }
            // Read back by MediaItem.toSong() when a song isn't in the library map.
            putLong(EXTRA_DURATION, duration)
            putLong(EXTRA_ALBUM_ID, albumId)
            navidromeId?.let { putString(EXTRA_NAVIDROME_ID, it) }
        }

        // Play the downloaded copy instead of streaming when there is one.
        val playbackUri = navidromeDownloadRepository.localPathForSync(id)
            ?.let { Uri.fromFile(File(it)) }
            ?: uri

        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(playbackUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(displayArtist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artworkUri)
                    .setExtras(extras)
                    .build()
            )
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(playbackUri)
                    .build()
            )
            .build()
    }

    // Reads ReplayGain tags in the background and stores them, so they apply from the next play
    // on. The first play may miss them; blocking playback on a file read would be worse.
    private fun prefetchReplayGain(song: Song) {
        // Skip known songs: every write to the songs table re-emits the whole library.
        if (song.replayGainTrack != null || song.replayGainAlbum != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val (track, album) = replayGainProcessor.readAndCacheGain(song.id, song.path) ?: return@launch
            if (track == song.replayGainTrack && album == song.replayGainAlbum) return@launch
            musicRepository.updateReplayGain(song.id, track, album)
        }
    }
}

// A minimal Song from a MediaItem's metadata, for songs missing from the library map.
private fun MediaItem.toSong(): Song? {
    val songId = mediaId.toLongOrNull() ?: return null
    val songUri = localConfiguration?.uri ?: requestMetadata.mediaUri ?: return null
    val meta = mediaMetadata
    val extras = meta.extras
    val artist = meta.artist?.toString() ?: ""
    return Song(
        id = songId,
        uri = songUri,
        title = meta.title?.toString() ?: "",
        artist = artist,
        artists = listOf(artist),
        albumArtist = meta.albumArtist?.toString() ?: artist,
        album = meta.albumTitle?.toString() ?: "",
        albumId = extras?.getLong(EXTRA_ALBUM_ID) ?: 0L,
        genre = "",
        duration = extras?.getLong(EXTRA_DURATION) ?: 0L,
        size = 0L,
        bitrate = 0,
        sampleRate = 0,
        trackNumber = 0,
        discNumber = 1,
        year = 0,
        dateAdded = 0L,
        dateModified = 0L,
        path = songUri.toString(),
        folder = "",
        mimeType = "",
        replayGainTrack = extras?.getFloat("replayGainTrack").takeIf { extras?.containsKey("replayGainTrack") == true },
        replayGainAlbum = extras?.getFloat("replayGainAlbum").takeIf { extras?.containsKey("replayGainAlbum") == true },
        artworkUri = meta.artworkUri,
        navidromeId = extras?.getString(EXTRA_NAVIDROME_ID),
    )
}

private const val EXTRA_DURATION = "resonance.duration"
private const val EXTRA_ALBUM_ID = "resonance.albumId"
private const val EXTRA_NAVIDROME_ID = "resonance.navidromeId"