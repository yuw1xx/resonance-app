// Bridges the UI to MusicService via a Media3 MediaController: exposes playback state flows,
// handles scrobbling, lyrics/waveform loading, smart queue, sleep timer, and liked-songs toggle.
package dev.yuwixx.resonance.presentation.viewmodel

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.cast.CastManager
import dev.yuwixx.resonance.cast.LocalAudioServer
import dev.yuwixx.resonance.data.database.dao.LikedSongsDao
import dev.yuwixx.resonance.data.service.NavidromePreloader
import dev.yuwixx.resonance.data.database.entity.LikedSongEntity
import dev.yuwixx.resonance.data.model.*
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.data.repository.*
import dev.yuwixx.resonance.data.service.MusicService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    val lyricsRepository: LyricsRepository,
    private val likedSongsDao: LikedSongsDao,
    private val lastFmRepository: LastFmRepository,
    private val malojaRepository: MalojaRepository,
    private val waveformExtractor: dev.yuwixx.resonance.domain.usecase.WaveformExtractor,
    val prefs: ResonancePreferences,
    private val navidromePreloader: NavidromePreloader,
    private val castManager: CastManager,
    private val localAudioServer: LocalAudioServer,
    private val artworkRepository: ArtworkRepository,
    private val visualizerStateHolder: dev.yuwixx.resonance.data.service.VisualizerStateHolder,
    private val replayGainProcessor: dev.yuwixx.resonance.domain.usecase.ReplayGainProcessor,
    private val navidromeDownloadRepository: NavidromeDownloadRepository,
    private val navidromeSyncRepository: NavidromeSyncRepository,
) : ViewModel() {

    val visualizerBars: StateFlow<FloatArray> = visualizerStateHolder.magnitudes

    // Only capture while the Cava seekbar is actually composed (see CavaSeekbar's
    // DisposableEffect) — MusicService also gates on isPlaying, so this is belt-and-suspenders
    // against burning battery on a bar row nobody can see.
    fun setVisualizerActive(active: Boolean) = visualizerStateHolder.requestActive(active)

    private var _controller: MediaController? = null

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _repeatMode = MutableStateFlow(dev.yuwixx.resonance.data.model.RepeatMode.NONE)
    val repeatMode: StateFlow<dev.yuwixx.resonance.data.model.RepeatMode> = _repeatMode.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _waveformData = MutableStateFlow<dev.yuwixx.resonance.data.model.WaveformData?>(null)
    val waveformData: StateFlow<dev.yuwixx.resonance.data.model.WaveformData?> = _waveformData.asStateFlow()

    private val _sleepTimer = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    val sleepTimer: StateFlow<SleepTimer> = _sleepTimer.asStateFlow()

    private val _lyricsResult = MutableStateFlow<LyricsResult>(LyricsResult.NotFound)
    val lyricsResult: StateFlow<LyricsResult> = _lyricsResult.asStateFlow()

    private val _activeLyricIndex = MutableStateFlow(-1)
    val activeLyricIndex: StateFlow<Int> = _activeLyricIndex.asStateFlow()

    // Re-checks liked status whenever the current song changes without an extra DB query.
    val isCurrentSongLiked: StateFlow<Boolean> = _currentSong
        .flatMapLatest { song ->
            if (song == null) flowOf(false)
            else likedSongsDao.getLikedSongIds().map { ids -> song.id in ids }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Artwork-derived accent colors for the player (seekbar, play button, next-song text).
    // Null means "no artwork to sample" — callers fall back to the fixed theme accent.
    val artworkColors: StateFlow<ArtworkComposeColors?> = _currentSong
        .flatMapLatest { song ->
            val uri = song?.artworkUri
            if (song == null || uri == null) flowOf<ArtworkComposeColors?>(null)
            else flow { emit(artworkRepository.extractArtworkColors(song.albumId, uri).toComposeColors()) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

    private var scrobbleTrackStartedAt: Long = 0L   // timestamp reported to Last.fm/Maloja
    private var scrobbleSegmentStart: Long = 0L      // start of current play segment (for accumulation)
    private var scrobbleAccumulatedMs: Long = 0L     // total ms played across pauses
    private var scrobbleSubmittedForCurrentTrack = false

    val scrobblePct = prefs.lastFmScrobblePercent.stateIn(viewModelScope, SharingStarted.Eagerly, 0.5f)
    val scrobbleMinSecs = prefs.lastFmScrobbleMinSecs.stateIn(viewModelScope, SharingStarted.Eagerly, 30)

    val isCasting: StateFlow<Boolean> = castManager.isCasting

    // Internal queue used when casting (Media3 queue is paused)
    private val castQueue = mutableListOf<Song>()
    private var castQueueIndex = 0
    // Tracks the last song id for which auto-advance was triggered; prevents
    // the 100ms progress ticker from firing the advance multiple times per track.
    private var castLastAdvancedSongId: Long = -1L

    init {
        localAudioServer.safeStart()
        initializeController()
        startProgressTracker()
        observePreferences()
        observeCasting()
        viewModelScope.launch {
            _currentSong.collect { song ->
                if (song != null) localAudioServer.trackCurrentSong(song)
            }
        }
    }

    private fun observeCasting() {
        viewModelScope.launch {
            var wasCasting = false
            castManager.isCasting.collect { casting ->
                if (casting) {
                    wasCasting = true
                    // Hand off to Cast only when the local player is actively playing.
                    // On session resume (app returning while Cast is already connected)
                    // the local player is already paused, so we leave Cast alone.
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
                    // _isPlaying still holds the last cast-driven playing state at this point
                    // (set by the castIsPlaying collector below) — read it before ctrl.play()
                    // below overwrites it with the controller's own (still-paused) state.
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

    @OptIn(UnstableApi::class)
    private fun initializeController() {
        val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                _controller = controllerFuture.get()
                _controller?.addListener(playerListener)
                syncStateWithController()
            },
            MoreExecutors.directExecutor()
        )
    }

    private fun startProgressTracker() {
        viewModelScope.launch {
            while (isActive) {
                if (castManager.isCasting.value) {
                    val pos = castManager.getCurrentPosition()
                    val dur = castManager.getStreamDuration()
                    _positionMs.value = pos
                    if (dur > 0) _durationMs.value = dur
                    // Lyric auto-scroll and scrobbling were previously local-playback-only —
                    // castIsPlaying is kept in sync with _isPlaying by the collector in init{},
                    // which checkScrobbleThreshold relies on, so this must stay true while casting.
                    if (castManager.castIsPlaying.value && dur > 0) {
                        updateActiveLyricIndex(pos)
                        checkScrobbleThreshold(pos, dur)
                    }
                    // Auto-advance when the cast track finishes.
                    // Guard with castLastAdvancedSongId so the 100ms ticker can't
                    // fire the advance multiple times for the same track.
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
                        if (ctrl.isPlaying) {
                            updateActiveLyricIndex(ctrl.currentPosition)
                            checkScrobbleThreshold(ctrl.currentPosition, ctrl.duration)
                        }
                    }
                }
                delay(100)
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
        val minPct  = scrobblePct.value

        val meetsTimeCriteria = listenedMs >= minSecs * 1000L
        val meetsPctCriteria  = positionMs.toFloat() / durationMs >= minPct

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
            val lines = result.lines
            val activeIndex = lines.indexOfLast { it.timeMs <= positionMs }
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
            // Start the waveform decode for the song that's about to play (and the one right
            // after it) immediately, in parallel with handing off to ExoPlayer, instead of
            // waiting for onMediaItemTransition to fire — that was the only trigger before,
            // so the very first song of a queue never got a head start.
            songs.getOrNull(startIndex)?.let { song ->
                viewModelScope.launch(Dispatchers.IO) { waveformExtractor.extract(song.id, song.uri) }
                prefetchReplayGain(song)
            }
            songs.getOrNull(startIndex + 1)?.let { song ->
                viewModelScope.launch(Dispatchers.IO) { waveformExtractor.extract(song.id, song.uri) }
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

    // Shuffles the list client-side so the very first song played is random too (not just
    // later next/previous hops), then leaves shuffle mode on for the rest of the queue.
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

    fun seekTo(position: Long) {
        if (castManager.isCasting.value) {
            castManager.seekTo(position)
        } else {
            _controller?.seekTo(position)
        }
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
                artist = song.artist,
                album = song.album,
                durationMs = song.duration,
                songUri = song.uri,
            )
        }
    }

    fun addToQueueEnd(song: Song) {
        _controller?.addMediaItem(song.toMediaItem())
        viewModelScope.launch(Dispatchers.IO) { waveformExtractor.extract(song.id, song.uri) }
        prefetchReplayGain(song)
    }

    fun addToQueueNext(song: Song) {
        _controller?.let { ctrl ->
            val insertIndex = (ctrl.currentMediaItemIndex + 1)
                .coerceAtMost(ctrl.mediaItemCount)
            ctrl.addMediaItem(insertIndex, song.toMediaItem())
        }
        viewModelScope.launch(Dispatchers.IO) { waveformExtractor.extract(song.id, song.uri) }
        prefetchReplayGain(song)
    }

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentQueueIndex = MutableStateFlow(0)
    val currentQueueIndex: StateFlow<Int> = _currentQueueIndex.asStateFlow()

    // The song that will actually play next, per the controller's shuffle-aware traversal —
    // not just queue[currentQueueIndex + 1], which is only correct when shuffle is off.
    private val _nextSong = MutableStateFlow<Song?>(null)
    val nextSong: StateFlow<Song?> = _nextSong.asStateFlow()

    // Queue entries in actual upcoming playback order (shuffle-aware), each carrying its real
    // underlying window index so remove/play-at actions keep targeting the right item. Used by
    // QueueScreen — _queue above stays in raw linear order since updateNextSong() relies on that.
    data class QueueEntry(val index: Int, val song: Song)
    private val _orderedQueue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val orderedQueue: StateFlow<List<QueueEntry>> = _orderedQueue.asStateFlow()

    private fun updateNextSong() {
        val ctrl = _controller
        val nextIndex = ctrl?.nextMediaItemIndex ?: C.INDEX_UNSET
        _nextSong.value = if (nextIndex != C.INDEX_UNSET) _queue.value.getOrNull(nextIndex) else null
    }

    // Walks the timeline from the current item using the same shuffle-aware traversal ExoPlayer
    // itself uses for next()/previous(), so QueueScreen's order matches real playback order.
    // REPEAT_MODE_OFF is passed explicitly regardless of the live repeat mode — that's the
    // traversal-semantics parameter's purpose, and it prevents infinite wraparound under
    // REPEAT_MODE_ALL (INDEX_UNSET is returned once you'd wrap past either end instead of looping).
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

    // Seeks within the existing timeline/shuffle order — unlike play(), this doesn't rebuild the
    // playlist, so it doesn't disturb shuffle order or re-trigger preload/scrobble-reset machinery.
    fun playAtQueueIndex(index: Int) {
        _controller?.let { ctrl ->
            ctrl.seekTo(index, 0L)
            ctrl.play()
        }
    }

    fun clearQueue() {
        _controller?.clearMediaItems()
    }

    fun removeFromQueue(index: Int) {
        _controller?.removeMediaItem(index)
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

    fun loadSmartQueue(reason: dev.yuwixx.resonance.data.model.SmartQueueReason) {
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
            } catch (e: Exception) {
                _smartQueueError.value = e.message ?: "Failed to load smart queue."
            } finally {
                _isLoadingSmartQueue.value = false
            }
        }
    }

    private var sleepTimerJob: Job? = null

    fun setSleepTimer(timer: SleepTimer) {
        _sleepTimer.value = timer
        sleepTimerJob?.cancel()
        if (timer is SleepTimer.Time) {
            sleepTimerJob = viewModelScope.launch {
                val endMs = System.currentTimeMillis() + timer.minutes * 60 * 1000L
                while (System.currentTimeMillis() < endMs) { delay(1000) }
                _controller?.pause()
                _sleepTimer.value = SleepTimer.Off
            }
        }
    }

    fun setSleepAfterTracks(tracksLeft: Int) {
        if (tracksLeft <= 0) {
            _sleepTimer.value = SleepTimer.Off
            return
        }
        _sleepTimer.value = SleepTimer.Tracks(tracksLeft)
    }

    // ─── Player Listener ───

    private val playerListener = object : Player.Listener {
        @OptIn(UnstableApi::class)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncStateWithController()
            val songId = mediaItem?.mediaId?.toLongOrNull()
            if (songId != null) {
                viewModelScope.launch {
                    val song = musicRepository.allSongs.first().find { it.id == songId }
                        ?: mediaItem?.toSong()
                    if (song != null) {
                        _currentSong.value = song
                        // If play()'s or the previous transition's prefetch already warmed the
                        // in-memory cache, show it immediately instead of flashing the
                        // placeholder waveform while a redundant (cache-hit) extract() call
                        // round-trips through a coroutine.
                        _waveformData.value = waveformExtractor.peekCached(song.id)
                        _lyricsResult.value = dev.yuwixx.resonance.data.repository.LyricsResult.Loading
                        _activeLyricIndex.value = -1

                        // Kick off waveform and lyrics loading in parallel for the current track.
                        // Guarded against a "late response wins" race: if the user skips again
                        // before these resolve, an older transition's slow response must not
                        // overwrite the now-current song's already-correct state.
                        launch {
                            val waveform = waveformExtractor.extract(song.id, song.uri)
                            if (_currentSong.value?.id == song.id) _waveformData.value = waveform
                        }
                        launch {
                            val lyrics = lyricsRepository.getLyrics(
                                songId = song.id,
                                title = song.title,
                                artist = song.artist,
                                album = song.album,
                                durationMs = song.duration,
                                songUri = song.uri,
                            )
                            if (_currentSong.value?.id == song.id) _lyricsResult.value = lyrics
                        }

                        val now = System.currentTimeMillis()
                        scrobbleTrackStartedAt = now
                        scrobbleSegmentStart = now
                        scrobbleAccumulatedMs = 0L
                        scrobbleSubmittedForCurrentTrack = false
                        lastFmRepository.updateNowPlaying(song)

                        _controller?.let { ctrl ->
                            val nextIndex = ctrl.nextMediaItemIndex
                            if (nextIndex != C.INDEX_UNSET) {
                                val nextItem = ctrl.getMediaItemAt(nextIndex)
                                val nextId = nextItem.mediaId.toLongOrNull()
                                if (nextId != null) {
                                    launch(Dispatchers.IO) {
                                        val nextSong = musicRepository.allSongs.first().find { it.id == nextId }
                                            ?: nextItem.toSong()
                                        if (nextSong != null) {
                                            waveformExtractor.extract(nextSong.id, nextSong.uri)
                                            lyricsRepository.getLyrics(
                                                songId = nextSong.id,
                                                title = nextSong.title,
                                                artist = nextSong.artist,
                                                album = nextSong.album,
                                                durationMs = nextSong.duration,
                                                songUri = nextSong.uri,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val currentTimer = _sleepTimer.value
            if (currentTimer is SleepTimer.Tracks) {
                val newCount = currentTimer.tracksLeft - 1
                if (newCount <= 0) {
                    _controller?.pause()
                    _sleepTimer.value = SleepTimer.Off
                } else {
                    _sleepTimer.value = SleepTimer.Tracks(newCount)
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _controller?.let { ctrl ->
                val rawDuration = ctrl.duration
                if (rawDuration != androidx.media3.common.C.TIME_UNSET && rawDuration > 0) {
                    _durationMs.value = rawDuration
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // The local controller stays alive (paused) during a cast session, so a stray event
            // from it must not override the cast-driven play state — castManager.castIsPlaying's
            // own collector (see init{}) is the source of truth for _isPlaying while casting.
            if (castManager.isCasting.value) return
            _isPlaying.value = isPlaying
            if (!scrobbleSubmittedForCurrentTrack) {
                if (isPlaying) {
                    scrobbleSegmentStart = System.currentTimeMillis()
                } else if (scrobbleSegmentStart > 0L) {
                    scrobbleAccumulatedMs += System.currentTimeMillis() - scrobbleSegmentStart
                    scrobbleSegmentStart = 0L
                }
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> dev.yuwixx.resonance.data.model.RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> dev.yuwixx.resonance.data.model.RepeatMode.ALL
                else -> dev.yuwixx.resonance.data.model.RepeatMode.NONE
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleEnabled.value = shuffleModeEnabled
            updateNextSong()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            syncQueueFromController()
        }
    }

    private fun syncStateWithController() {
        _controller?.let { ctrl ->
            _isPlaying.value = ctrl.isPlaying
            _repeatMode.value = when (ctrl.repeatMode) {
                Player.REPEAT_MODE_ONE -> dev.yuwixx.resonance.data.model.RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> dev.yuwixx.resonance.data.model.RepeatMode.ALL
                else -> dev.yuwixx.resonance.data.model.RepeatMode.NONE
            }
            _shuffleEnabled.value = ctrl.shuffleModeEnabled
            _currentQueueIndex.value = ctrl.currentMediaItemIndex
        }
        syncQueueFromController()
    }

    private fun syncQueueFromController() {
        val ctrl = _controller ?: return
        viewModelScope.launch {
            val allSongs = musicRepository.allSongs.first()
            val songMap = allSongs.associateBy { it.id }
            val songs = (0 until ctrl.mediaItemCount).mapNotNull { i ->
                val item = ctrl.getMediaItemAt(i)
                val songId = item.mediaId.toLongOrNull() ?: return@mapNotNull null
                songMap[songId] ?: item.toSong()
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
                val waveform = waveformExtractor.extract(song.id, song.uri)
                if (_currentSong.value?.id == song.id) _waveformData.value = waveform
            }
            launch {
                val lyrics = lyricsRepository.getLyrics(
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.duration,
                    songUri = song.uri,
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
        _controller?.release()
        localAudioServer.safeStop()
        super.onCleared()
    }

    // A member function (not a top-level extension) so it can consult replayGainProcessor's
    // in-memory cache without needing that dependency threaded through every call site.
    private fun Song.toMediaItem(): MediaItem {
        val cached = replayGainProcessor.peekCachedGain(id)
        val trackGain = cached?.first ?: replayGainTrack
        val albumGain = cached?.second ?: replayGainAlbum
        val extras = android.os.Bundle().apply {
            trackGain?.let { putFloat("replayGainTrack", it) }
            albumGain?.let { putFloat("replayGainAlbum", it) }
        }

        // Play the downloaded local copy instead of streaming, when one exists (no-op for
        // local MediaStore songs — they never have a song_downloads row).
        val playbackUri = navidromeDownloadRepository.localPathForSync(id)
            ?.let { android.net.Uri.fromFile(java.io.File(it)) }
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

    // Kicks off a background tag read for a song about to be queued, so ReplayGain is applied
    // from the second play onward (the very first play may miss it — an acceptable tradeoff
    // versus blocking playback start on a file read). Persists the result to the DB too, so it
    // survives across app sessions without a re-read.
    private fun prefetchReplayGain(song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            val (track, album) = replayGainProcessor.readAndCacheGain(song.id, song.path) ?: return@launch
            musicRepository.updateReplayGain(song.id, track, album)
        }
    }
}

// Reconstructs a minimal Song from a MediaItem's embedded metadata.
// Used as fallback when a song isn't in the local songs table (e.g. Navidrome streams).
private fun MediaItem.toSong(): Song? {
    val songId = mediaId.toLongOrNull() ?: return null
    val songUri = localConfiguration?.uri ?: requestMetadata.mediaUri ?: return null
    val meta = mediaMetadata
    val extras = meta.extras
    val artist = meta.artist?.toString() ?: ""
    return Song(
        id            = songId,
        uri           = songUri,
        title         = meta.title?.toString() ?: "",
        artist        = artist,
        artists       = listOf(artist),
        albumArtist   = meta.albumArtist?.toString() ?: artist,
        album         = meta.albumTitle?.toString() ?: "",
        albumId       = 0L,
        genre         = "",
        duration      = 0L,
        size          = 0L,
        bitrate       = 0,
        sampleRate    = 0,
        trackNumber   = 0,
        discNumber    = 1,
        year          = 0,
        dateAdded     = 0L,
        dateModified  = 0L,
        path          = songUri.toString(),
        folder        = "",
        mimeType      = "",
        replayGainTrack = extras?.getFloat("replayGainTrack").takeIf { extras?.containsKey("replayGainTrack") == true },
        replayGainAlbum = extras?.getFloat("replayGainAlbum").takeIf { extras?.containsKey("replayGainAlbum") == true },
        artworkUri    = meta.artworkUri,
    )
}