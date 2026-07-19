// Media3 MediaLibraryService that owns the ExoPlayer instance, manages the playback session,
// and handles crossfade, ReplayGain, EQ, headphone events, queue persistence, and widget updates.
package dev.yuwixx.resonance.data.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.*
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.yuwixx.resonance.MainActivity
import dev.yuwixx.resonance.data.database.dao.LikedSongsDao
import dev.yuwixx.resonance.data.database.dao.NavidromeSongDao
import dev.yuwixx.resonance.data.database.dao.PlaylistDao
import dev.yuwixx.resonance.data.database.dao.QueueDao
import dev.yuwixx.resonance.data.database.dao.SongDao
import dev.yuwixx.resonance.data.database.entity.LikedSongEntity
import dev.yuwixx.resonance.data.database.entity.QueueEntity
import dev.yuwixx.resonance.data.model.MusicSource
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.data.repository.NavidromeDownloadRepository
import dev.yuwixx.resonance.domain.usecase.ReplayGainProcessor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import dev.yuwixx.resonance.ui.glancewidget.ACTION_WIDGET_PLAY_PAUSE
import dev.yuwixx.resonance.ui.glancewidget.ACTION_WIDGET_SEEK
import dev.yuwixx.resonance.ui.glancewidget.ACTION_WIDGET_SKIP_NEXT
import dev.yuwixx.resonance.ui.glancewidget.ACTION_WIDGET_SKIP_PREV
import dev.yuwixx.resonance.ui.glancewidget.ACTION_WIDGET_TOGGLE_LIKE
import dev.yuwixx.resonance.ui.glancewidget.EXTRA_SEEK_FRACTION_PCT
import dev.yuwixx.resonance.ui.glancewidget.ResonanceWidget

@UnstableApi
@AndroidEntryPoint
class MusicService : MediaLibraryService() {

    @Inject lateinit var prefs: ResonancePreferences
    @Inject lateinit var queueDao: QueueDao
    @Inject lateinit var songDao: SongDao
    @Inject lateinit var navidromeSongDao: NavidromeSongDao
    @Inject lateinit var playlistDao: PlaylistDao
    @Inject lateinit var likedSongsDao: LikedSongsDao
    @Inject lateinit var navidromeDownloadRepository: NavidromeDownloadRepository
    @Inject lateinit var replayGainProcessor: ReplayGainProcessor
    @Inject lateinit var navidromePreloader: NavidromePreloader
    @Inject lateinit var eqStateHolder: EqStateHolder
    @Inject lateinit var visualizerStateHolder: VisualizerStateHolder

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var equalizer: Equalizer? = null
    private var visualizer: Visualizer? = null
    private var visualizerActiveRequested = false

    private var crossfadeJob: Job? = null
    private var crossfadeDurationMs: Int = 0
    private var gaplessEnabled: Boolean = true

    private var volumeNormalizationEnabled = false
    private var replayGainMode = "TRACK"
    private var replayGainPreampDb = 0f

    private var headphonesReceiver: BroadcastReceiver? = null
    private var resumeOnHeadphones = true
    private var pauseOnHeadphonesEnabled = true
    private var wasPlayingBeforeUnplug = false

    private var showSkipButtons = true

    private var widgetActionsReceiver: BroadcastReceiver? = null
    private var widgetProgressJob: kotlinx.coroutines.Job? = null

    private var savedShuffleOrder: ShuffleOrder? = null
    private var smartShuffleEnabled: Boolean = false
    // Tracks the actual playlist contents (by id, in window-index order) so a fresh
    // setMediaItems() call can be told apart from a shuffle-order-only Timeline update —
    // the latter fires the same onTimelineChanged callback but must not re-trigger itself.
    private var lastKnownMediaItemIds: List<Long> = emptyList()

    override fun onCreate() {
        super.onCreate()
        buildPlayer()
        buildSession()
        setupEqualizer()
        setupVisualizer()
        visualizerStateHolder.onActiveRequestChanged = { active ->
            visualizerActiveRequested = active
            updateVisualizerEnabled()
        }
        applyInitialPreferences()
        observePreferences()
        restoreQueue()
        registerHeadphonesReceiver()
        registerWidgetActionsReceiver()
    }

    // ─── Player / Session Setup ───

    private fun buildPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs                    */ 15_000,
                /* maxBufferMs                    */ 60_000,
                /* bufferForPlaybackMs            */  2_500,
                /* bufferForPlaybackAfterRebuffer */  5_000,
            )
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus= */ true
            )
            .setHandleAudioBecomingNoisy(false)
            .setSkipSilenceEnabled(false)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                persistQueue()
                applyVolumeForCurrentItem()
                scheduleCrossfade()
                pushWidgetState()
                preloadUpcomingTracks()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) persistQueue()
                pushWidgetState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && player.playbackState != Player.STATE_ENDED) {
                }
                if (isPlaying) {
                    preloadUpcomingTracks()
                    startWidgetProgressUpdates()
                } else {
                    stopWidgetProgressUpdates()
                    pushWidgetState()
                }
                updateVisualizerEnabled()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled) applyShuffleOrder()
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val count = player.mediaItemCount
                val currentIds = (0 until count).map { player.getMediaItemAt(it).mediaId.toLongOrNull() ?: 0L }
                if (currentIds != lastKnownMediaItemIds) {
                    val previousIds = lastKnownMediaItemIds
                    lastKnownMediaItemIds = currentIds
                    // A pure addition (e.g. "Play Next"/Smart Queue inserting one or more songs
                    // into an already-shuffled queue) must NOT trigger a full reshuffle — that
                    // would scatter the just-inserted item to a random spot, defeating "play
                    // next" entirely. Only re-randomize on a genuine wholesale replacement (a new
                    // playlist via setMediaItems, or a removal); ExoPlayer's own default
                    // ShuffleOrder.cloneAndInsert already places pure additions sensibly without
                    // disturbing the rest of the order.
                    // Known limitation: this can't distinguish "pure addition" from "addition +
                    // manual reorder" — not reachable today since nothing calls moveMediaItem(),
                    // but a future drag-to-reorder queue feature would need to special-case this.
                    val currentSet = currentIds.toHashSet()
                    val isPureAddition = currentIds.size > previousIds.size && previousIds.all { it in currentSet }
                    if (!isPureAddition && player.shuffleModeEnabled) {
                        applyShuffleOrder()
                    }
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                setupEqualizer()
                setupVisualizer()
            }
        })
    }

        private fun preloadUpcomingTracks() {
        val currentIndex = player.currentMediaItemIndex
        val count        = player.mediaItemCount
        if (count == 0) return

        val upcoming = mutableListOf<String>()
        for (i in 1..PRELOAD_AHEAD) {
            val idx = currentIndex + i
            if (idx >= count) break
            val uri = player.getMediaItemAt(idx).localConfiguration?.uri?.toString() ?: continue
            if (uri.contains("/rest/stream")) upcoming += uri
        }
        if (upcoming.isNotEmpty()) navidromePreloader.preload(upcoming)
    }

    companion object {
        private const val PRELOAD_AHEAD = 2
    }

    private fun buildSession() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(pendingIntent)
            .build()
    }

    private fun setupEqualizer() {
        try {
            val sessionId = player.audioSessionId
            if (sessionId == 0) return

            equalizer?.release()

            val eq = Equalizer(0, sessionId)
            equalizer = eq

            val bandCount = eq.numberOfBands.toInt()
            val centerFreqs = (0 until bandCount).map { eq.getCenterFreq(it.toShort()) / 1000 }
            val bandLevelRange = eq.bandLevelRange
            val minLevel = bandLevelRange[0]
            val maxLevel = bandLevelRange[1]

            eqStateHolder.publishCapabilities(
                EqStateHolder.EqCapabilities(bandCount, centerFreqs, minLevel, maxLevel)
            )

            scope.launch {
                val savedLevels = prefs.eqBandLevels.first()
                val levels = if (savedLevels.isNotEmpty()) {
                    savedLevels.split(",").mapIndexedNotNull { i, s ->
                        if (i < bandCount) s.trim().toShortOrNull() else null
                    }
                } else {
                    List(bandCount) { 0.toShort() }
                }

                val enabled = prefs.eqEnabled.first()
                eq.enabled = enabled
                levels.forEachIndexed { i, level ->
                    if (i < bandCount) eq.setBandLevel(i.toShort(), level)
                }
                eqStateHolder.publishBandLevels(levels)
                eqStateHolder.publishEnabled(enabled)
            }

            eqStateHolder.onBandLevelChanged = { band, level ->
                equalizer?.setBandLevel(band.toShort(), level)
                val current = eqStateHolder.bandLevels.value.toMutableList()
                if (band < current.size) current[band] = level
                eqStateHolder.publishBandLevels(current)
                scope.launch { prefs.setEqBandLevels(current.joinToString(",")) }
            }

            eqStateHolder.onEnabledChanged = { enabled ->
                equalizer?.enabled = enabled
                eqStateHolder.publishEnabled(enabled)
                scope.launch { prefs.setEqEnabled(enabled) }
            }
        } catch (e: Exception) {
            Log.w("MusicService", "Failed to set up Equalizer (session ${player.audioSessionId})", e)
        }
    }

    // Cava-style spectrum: attaches to the same audio session as the equalizer (no extra
    // permission needed — RECORD_AUDIO is only required to capture another app's session).
    // Capture only runs while the UI actually requests it (Cava seekbar visible) AND audio
    // is playing, to avoid burning battery for a bar row nobody is looking at.
    private fun setupVisualizer(retryOnFailure: Boolean = true) {
        try {
            val sessionId = player.audioSessionId
            if (sessionId == 0) return

            visualizer?.release()

            val captureSizeRange = Visualizer.getCaptureSizeRange()
            val captureSize = captureSizeRange[1].coerceAtMost(1024).coerceAtLeast(captureSizeRange[0])

            val viz = Visualizer(sessionId).apply {
                enabled = false
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                setCaptureSize(captureSize)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                            if (fft == null) return
                            visualizerStateHolder.publishMagnitudes(
                                computeSpectrumBars(fft, VisualizerStateHolder.BAR_COUNT)
                            )
                        }
                    },
                    Visualizer.getMaxCaptureRate(),
                    /* waveform= */ false,
                    /* fft= */ true,
                )
            }
            visualizer = viz
            updateVisualizerEnabled()
        } catch (e: Exception) {
            Log.w("MusicService", "Failed to set up Visualizer (session ${player.audioSessionId})", e)
            // The underlying AudioTrack may not have fully attached yet when this races
            // onAudioSessionIdChanged — one short retry covers that without looping forever
            // on a device that genuinely can't support a Visualizer on this session.
            if (retryOnFailure) {
                scope.launch {
                    delay(300)
                    setupVisualizer(retryOnFailure = false)
                }
            }
        }
    }

    private fun updateVisualizerEnabled() {
        try {
            visualizer?.enabled = visualizerActiveRequested && player.isPlaying
        } catch (e: Exception) {
            Log.w("MusicService", "Failed to update Visualizer enabled state", e)
        }
    }

    // Decodes the Visualizer's packed 8-bit FFT format (fft[0]=Re(0), fft[1]=Re(N/2), then
    // Re(i)/Im(i) pairs) into magnitudes, then groups those linear frequency bins into
    // `barCount` log-spaced bars — bass notes span few bins so they'd otherwise be crushed
    // into a single bar next to treble, which spans hundreds.
    private fun computeSpectrumBars(fft: ByteArray, barCount: Int): FloatArray {
        val numBins = fft.size / 2
        if (numBins < 2) return FloatArray(barCount)

        val magnitudes = FloatArray(numBins)
        magnitudes[0] = kotlin.math.abs(fft[0].toInt()).toFloat()
        for (i in 1 until numBins) {
            val re = fft[2 * i].toInt()
            val im = fft[2 * i + 1].toInt()
            magnitudes[i] = kotlin.math.sqrt((re * re + im * im).toFloat())
        }

        val bars = FloatArray(barCount)
        val logMax = kotlin.math.ln(numBins.toDouble())
        var startBin = 1
        for (b in 0 until barCount) {
            val endBin = kotlin.math.exp(logMax * (b + 1) / barCount)
                .toInt()
                .coerceIn(startBin + 1, numBins)
            var peak = 0f
            for (i in startBin until endBin) peak = maxOf(peak, magnitudes[i])
            // fft[] entries are signed 8-bit (-128..127), so a Re/Im pair's magnitude tops
            // out around sqrt(2)*128 ≈ 181; 128 is a reasonable practical ceiling to scale by.
            bars[b] = (peak / 128f).coerceIn(0f, 1f)
            startBin = endBin
        }
        return bars
    }

    // ─── Preferences ───

    private fun applyInitialPreferences() {
        scope.launch {
            player.skipSilenceEnabled = prefs.skipSilence.first()

            val repeat = prefs.repeatMode.first()
            player.repeatMode = when (repeat) {
                dev.yuwixx.resonance.data.model.RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                dev.yuwixx.resonance.data.model.RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            smartShuffleEnabled       = prefs.smartShuffleEnabled.first()
            player.shuffleModeEnabled = prefs.shuffleEnabled.first()

            gaplessEnabled            = prefs.gaplessEnabled.first()
            crossfadeDurationMs       = prefs.crossfadeDurationMs.first()
            volumeNormalizationEnabled = prefs.volumeNormalization.first()
            replayGainMode            = prefs.replayGainMode.first()
            replayGainPreampDb        = prefs.replayGainPreampDb.first()
            resumeOnHeadphones        = prefs.resumeOnHeadphones.first()
            showSkipButtons           = prefs.showSkipButtons.first()

            updateNotificationCommandButtons(showSkipButtons)
        }
    }

    private fun observePreferences() {

        scope.launch {
            prefs.skipSilence.collect { player.skipSilenceEnabled = it }
        }

        scope.launch {
            prefs.smartShuffleEnabled.collect { smartShuffleEnabled = it }
        }

        scope.launch {
            prefs.gaplessEnabled.collect { enabled ->
                gaplessEnabled = enabled
                scheduleCrossfade()
            }
        }

        scope.launch {
            prefs.duckAudioOnFocusLoss.collect { duckAudio ->
                val currentAttrs = player.audioAttributes
                player.setAudioAttributes(currentAttrs, duckAudio)
            }
        }

        scope.launch {
            prefs.pauseOnHeadphonesOut.collect { enabled ->
                pauseOnHeadphonesEnabled = enabled
            }
        }

        scope.launch {
            prefs.crossfadeDurationMs.collect { ms ->
                crossfadeDurationMs = ms
                scheduleCrossfade()
            }
        }

        scope.launch {
            prefs.volumeNormalization.collect { enabled ->
                volumeNormalizationEnabled = enabled
                applyVolumeForCurrentItem()
            }
        }

        scope.launch {
            prefs.replayGainMode.collect { mode ->
                replayGainMode = mode
                applyVolumeForCurrentItem()
            }
        }
        scope.launch {
            prefs.replayGainPreampDb.collect { db ->
                replayGainPreampDb = db
                applyVolumeForCurrentItem()
            }
        }

        scope.launch {
            prefs.resumeOnHeadphones.collect { enabled ->
                resumeOnHeadphones = enabled
            }
        }

        scope.launch {
            prefs.lockscreenArtwork.collect { enabled ->
                applyLockscreenArtwork(enabled)
            }
        }

        scope.launch {
            prefs.showSkipButtons.collect { show ->
                showSkipButtons = show
                updateNotificationCommandButtons(show)
            }
        }
    }

    // ─── Crossfade & Volume ───

        private fun scheduleCrossfade() {
        crossfadeJob?.cancel()

        val fadeDurationMs = when {
            crossfadeDurationMs > 0 -> crossfadeDurationMs
            !gaplessEnabled         -> 200
            else                    -> 0
        }

        if (fadeDurationMs == 0) {
            player.volume = computeTargetVolume()
            return
        }

        crossfadeJob = scope.launch {
            while (isActive) {
                val duration = player.duration
                val position = player.currentPosition
                if (duration > 0 && position > 0) {
                    val remaining = duration - position
                    if (remaining in 1..fadeDurationMs) {
                        val fadeProgress = 1f - (remaining.toFloat() / fadeDurationMs)
                        player.volume = computeTargetVolume() * (1f - fadeProgress)
                    } else if (remaining <= 0 || player.playbackState == Player.STATE_ENDED) {
                        fadeIn(fadeDurationMs, computeTargetVolume())
                        break
                    } else {
                        val target = computeTargetVolume()
                        if (player.volume < target * 0.95f) {
                            player.volume = target
                        }
                    }
                }
                delay(50)
            }
        }
    }

    private suspend fun fadeIn(durationMs: Int, targetVolume: Float) {
        player.volume = 0f
        val steps = (durationMs / 50).coerceAtLeast(1)
        for (i in 0..steps) {
            player.volume = targetVolume * (i.toFloat() / steps)
            delay(50)  // throws CancellationException if crossfadeJob is cancelled
        }
        player.volume = targetVolume
        scheduleCrossfade()
    }

        // ReplayGain multiplier: convert dB gain tag → linear amplitude, fall back to -3 dBFS
        // normalisation (0.707) if no tag is present and volume normalisation is on.
        private fun computeTargetVolume(): Float {
        if (replayGainMode != "OFF") {
            val extras = player.currentMediaItem?.mediaMetadata?.extras
            if (extras != null) {
                val trackGain = extras.getFloat("replayGainTrack", Float.MAX_VALUE)
                    .takeIf { it != Float.MAX_VALUE }
                val albumGain = extras.getFloat("replayGainAlbum", Float.MAX_VALUE)
                    .takeIf { it != Float.MAX_VALUE }

                if (trackGain != null || albumGain != null) {
                    val info = ReplayGainProcessor.ReplayGainInfo(
                        trackGainDb = trackGain,
                        trackPeak   = null,
                        albumGainDb = albumGain,
                        albumPeak   = null,
                    )
                    val mode = when (replayGainMode) {
                        "ALBUM" -> ReplayGainProcessor.Mode.ALBUM
                        "OFF"   -> ReplayGainProcessor.Mode.OFF
                        else    -> ReplayGainProcessor.Mode.TRACK
                    }
                    val multiplier = replayGainProcessor.computeMultiplier(info, mode, replayGainPreampDb)
                    return multiplier
                }
            }
        }

        if (volumeNormalizationEnabled) {
            return 0.707f
        }

        return 1f
    }

    private fun applyVolumeForCurrentItem() {
        if (crossfadeJob?.isActive == true) return
        player.volume = computeTargetVolume()
    }

    // ─── Notification / Lockscreen ───

        private fun applyLockscreenArtwork(enabled: Boolean) {
        val item = player.currentMediaItem ?: return
        val originalMetadata = item.mediaMetadata

        val updatedMetadata = if (enabled) {
            originalMetadata
        } else {
            originalMetadata.buildUpon()
                .setArtworkUri(null)
                .setArtworkData(null, null)
                .build()
        }

        val updatedItem = item.buildUpon().setMediaMetadata(updatedMetadata).build()

        val index = player.currentMediaItemIndex
        if (index >= 0) {
            player.replaceMediaItem(index, updatedItem)
        }
    }

        private fun updateNotificationCommandButtons(showSkip: Boolean) {
        val layout = mutableListOf<CommandButton>()

        if (showSkip) {
            layout += CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
                .setDisplayName("Previous")
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

            layout += CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
                .setDisplayName("Next")
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()
        }

        mediaSession.setCustomLayout(layout)
    }

    private fun registerHeadphonesReceiver() {
        headphonesReceiver?.let { unregisterReceiver(it) }
        headphonesReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                        wasPlayingBeforeUnplug = player.isPlaying
                        if (wasPlayingBeforeUnplug && pauseOnHeadphonesEnabled) player.pause()
                    }
                    AudioManager.ACTION_HEADSET_PLUG -> {
                        val state = intent.getIntExtra("state", -1)
                        if (state == 1 && resumeOnHeadphones && wasPlayingBeforeUnplug) {
                            player.play()
                            wasPlayingBeforeUnplug = false
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(headphonesReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(headphonesReceiver, filter)
        }
    }

    // ─── Queue Persistence ───

    private fun restoreQueue() {
        scope.launch(Dispatchers.IO) {
            try {
                val queueEntity = queueDao.getCurrentQueue() ?: return@launch
                val songIds = queueEntity.songIds
                    .split(",")
                    .mapNotNull { it.trim().toLongOrNull() }
                if (songIds.isEmpty()) return@launch

                val isNavidrome = prefs.musicSource.first() == MusicSource.NAVIDROME

                val mediaItems = if (isNavidrome) {
                    songIds.mapNotNull { id ->
                        val entity = navidromeSongDao.getSongByNumericId(id) ?: return@mapNotNull null
                        val artworkUri = entity.coverArtUrl?.let { android.net.Uri.parse(it) }
                        // Play the downloaded local copy instead of streaming, when one exists.
                        val localPath = navidromeDownloadRepository.localPathFor(id)
                        val playbackUri = localPath?.let { android.net.Uri.fromFile(java.io.File(it)) }
                            ?: android.net.Uri.parse(entity.streamUrl)
                        MediaItem.Builder()
                            .setMediaId(entity.numericId.toString())
                            .setUri(playbackUri)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(entity.title)
                                    .setArtist(entity.artist)
                                    .setAlbumTitle(entity.album)
                                    .setArtworkUri(artworkUri)
                                    .setExtras(android.os.Bundle())
                                    .build()
                            )
                            .setRequestMetadata(
                                MediaItem.RequestMetadata.Builder()
                                    .setMediaUri(playbackUri)
                                    .build()
                            )
                            .build()
                    }
                } else {
                    songIds.mapNotNull { id ->
                        val entity = songDao.getSongById(id) ?: return@mapNotNull null
                        val artworkUri = android.content.ContentUris.withAppendedId(
                            android.net.Uri.parse("content://media/external/audio/albumart"),
                            entity.albumId
                        )
                        val extras = android.os.Bundle().apply {
                            entity.replayGainTrack?.let { putFloat("replayGainTrack", it) }
                            entity.replayGainAlbum?.let { putFloat("replayGainAlbum", it) }
                        }
                        MediaItem.Builder()
                            .setMediaId(entity.id.toString())
                            .setUri(android.net.Uri.parse(entity.uri))
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(entity.title)
                                    .setArtist(entity.artist)
                                    .setAlbumTitle(entity.album)
                                    .setArtworkUri(artworkUri)
                                    .setExtras(extras)
                                    .build()
                            )
                            .setRequestMetadata(
                                MediaItem.RequestMetadata.Builder()
                                    .setMediaUri(android.net.Uri.parse(entity.uri))
                                    .build()
                            )
                            .build()
                    }
                }
                if (mediaItems.isEmpty()) return@launch

                val repeatMode = when (queueEntity.repeatMode) {
                    "ONE" -> Player.REPEAT_MODE_ONE
                    "ALL" -> Player.REPEAT_MODE_ALL
                    else  -> Player.REPEAT_MODE_OFF
                }

                withContext(Dispatchers.Main) {
                    player.setMediaItems(
                        mediaItems,
                        queueEntity.currentIndex.coerceIn(0, mediaItems.lastIndex),
                        /* startPositionMs= */ 0L,
                    )
                    player.repeatMode = repeatMode
                    player.shuffleModeEnabled = queueEntity.shuffleEnabled

                    // Restore the saved shuffle order so next/previous follow the same sequence.
                    val savedOrder = queueEntity.originalOrder
                    if (queueEntity.shuffleEnabled && savedOrder.isNotEmpty()) {
                        val shuffledIds = savedOrder.split(",").mapNotNull { it.trim().toLongOrNull() }
                        // A list (not map) of positions per ID so a duplicate song in the queue
                        // maps each occurrence to its own position instead of collapsing to one.
                        val idToPositions = songIds.withIndex().groupBy({ it.value }, { it.index })
                            .mapValues { (_, indices) -> ArrayDeque(indices) }
                        val shuffleTable = shuffledIds.mapNotNull { idToPositions[it]?.removeFirstOrNull() }.toIntArray()
                        if (shuffleTable.size == mediaItems.size) {
                            val restoredOrder = ShuffleOrder.DefaultShuffleOrder(shuffleTable, 0L)
                            savedShuffleOrder = restoredOrder
                            player.setShuffleOrder(restoredOrder)
                        }
                    }

                    player.prepare()
                }
            } catch (_: Exception) {}
        }
    }

    private fun buildQueueEntity(): QueueEntity? {
        val count = player.mediaItemCount
        if (count == 0) return null
        val ids = (0 until count).map { player.getMediaItemAt(it).mediaId.toLongOrNull() ?: 0L }
        val shuffle = player.shuffleModeEnabled
        val repeatStr = when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> "ONE"
            Player.REPEAT_MODE_ALL -> "ALL"
            else -> "NONE"
        }
        return QueueEntity(
            id = 0L,
            songIds = ids.joinToString(","),
            currentIndex = player.currentMediaItemIndex,
            shuffleEnabled = shuffle,
            repeatMode = repeatStr,
            originalOrder = buildShuffledOrder(ids, shuffle),
        )
    }

    private suspend fun saveQueueEntity(entity: QueueEntity) {
        try {
            queueDao.saveQueue(entity)
        } catch (e: Exception) {
            Log.e("MusicService", "Failed to persist queue", e)
        }
    }

    fun persistQueue() {
        scope.launch {
            val entity = buildQueueEntity() ?: return@launch
            withContext(Dispatchers.IO) { saveQueueEntity(entity) }
        }
    }

    // Applies a plain random order synchronously — no suspension, no window where
    // shuffleModeEnabled is true but the order ExoPlayer actually uses is still sequential.
    // If smart shuffle is on, it's refined asynchronously right after via applySmartShuffleOrder().
    private fun applyShuffleOrder() {
        val count = player.mediaItemCount
        if (count == 0) return
        val order = ShuffleOrder.DefaultShuffleOrder(count, System.currentTimeMillis())
        savedShuffleOrder = order
        player.setShuffleOrder(order)
        if (smartShuffleEnabled) {
            scope.launch { applySmartShuffleOrder() }
        }
    }

    private suspend fun applySmartShuffleOrder() {
        val count = player.mediaItemCount
        if (count == 0) return
        val songIds = (0 until count).map { player.getMediaItemAt(it).mediaId.toLongOrNull() ?: 0L }
        val listenCounts = withContext(Dispatchers.IO) {
            songDao.getListenCountsForIds(songIds).associate { it.songId to it.playCount }
        }
        val maxListens = listenCounts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val rng = java.util.Random()
        val shuffledIndices = (0 until count)
            .sortedByDescending { i ->
                val listens = listenCounts[songIds[i]] ?: 0
                listens * 2 + rng.nextInt(maxListens + 1)
            }
            .toIntArray()
        // The playlist may have changed while the DB lookup was in flight; bail rather than
        // hand ExoPlayer a shuffled-index array whose length no longer matches mediaItemCount.
        if (player.mediaItemCount != count) return
        val order = ShuffleOrder.DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis())
        savedShuffleOrder = order
        player.setShuffleOrder(order)
    }

    private fun buildShuffledOrder(ids: List<Long>, shuffle: Boolean): String {
        if (!shuffle) return ""
        val order = savedShuffleOrder ?: return ""
        val result = mutableListOf<Long>()
        var idx = order.getFirstIndex()
        while (idx != C.INDEX_UNSET) {
            result.add(ids.getOrElse(idx) { 0L })
            idx = order.getNextIndex(idx)
        }
        return result.joinToString(",")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = mediaSession

    override fun onDestroy() {
        // Kept synchronous/blocking on purpose: losing the queue if the process dies right
        // after onDestroy() returns is worse than the small ANR risk of one fast DB write.
        buildQueueEntity()?.let { entity ->
            kotlinx.coroutines.runBlocking(Dispatchers.IO) { saveQueueEntity(entity) }
        }
        kotlinx.coroutines.runBlocking {
            try { ResonanceWidget.updateState(this@MusicService, "", "", "", false, false, false, false) }
            catch (e: Exception) { Log.e("MusicService", "Failed to clear widget state", e) }
        }
        crossfadeJob?.cancel()
        stopWidgetProgressUpdates()
        widgetActionsReceiver?.let { unregisterReceiver(it) }
        headphonesReceiver?.let { unregisterReceiver(it) }
        scope.cancel()
        equalizer?.release()
        visualizer?.release()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    // ─── Home-Screen Widget ───

    private fun pushWidgetState() {
        val mediaItem = player.currentMediaItem ?: run {
            scope.launch {
                val cornerRadius = prefs.cornerRadius.first()
                ResonanceWidget.updateState(
                    context        = this@MusicService,
                    title          = "", artist = "", artworkUri = "",
                    isPlaying      = false, hasSong = false,
                    hasPrev        = false, hasNext = false, progress = 0f,
                    isLiked        = false, cornerRadiusDp = cornerRadius,
                )
            }
            return
        }
        val meta       = mediaItem.mediaMetadata
        val title      = meta.title?.toString() ?: mediaItem.mediaId
        val artist     = meta.artist?.toString() ?: meta.albumArtist?.toString() ?: ""
        val artworkUri = meta.artworkUri?.toString() ?: ""
        val duration   = player.duration.takeIf { it > 0 } ?: 1L
        val progress   = (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
        val songId     = mediaItem.mediaId.toLongOrNull()
        scope.launch {
            val isLiked      = songId?.let { likedSongsDao.isLiked(it) > 0 } ?: false
            val cornerRadius = prefs.cornerRadius.first()
            ResonanceWidget.updateState(
                context        = this@MusicService,
                title          = title,
                artist         = artist,
                artworkUri     = artworkUri,
                isPlaying      = player.isPlaying,
                hasSong        = true,
                hasPrev        = player.hasPreviousMediaItem(),
                hasNext        = player.hasNextMediaItem(),
                progress       = progress,
                isLiked        = isLiked,
                cornerRadiusDp = cornerRadius,
            )
        }
    }

    private fun startWidgetProgressUpdates() {
        widgetProgressJob?.cancel()
        widgetProgressJob = scope.launch {
            while (true) {
                pushWidgetState()
                kotlinx.coroutines.delay(1_000)
            }
        }
    }

    private fun stopWidgetProgressUpdates() {
        widgetProgressJob?.cancel()
        widgetProgressJob = null
    }

    private fun registerWidgetActionsReceiver() {
        widgetActionsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_WIDGET_PLAY_PAUSE -> {
                        if (player.isPlaying) player.pause() else player.play()
                    }
                    ACTION_WIDGET_SKIP_NEXT -> {
                        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                    }
                    ACTION_WIDGET_SKIP_PREV -> {
                        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
                        else player.seekTo(0)
                    }
                    ACTION_WIDGET_SEEK -> {
                        val pct = intent.getIntExtra(EXTRA_SEEK_FRACTION_PCT, -1)
                        if (pct in 0..100 && player.duration > 0) {
                            player.seekTo((player.duration * pct / 100L))
                        }
                    }
                    ACTION_WIDGET_TOGGLE_LIKE -> {
                        val songId = player.currentMediaItem?.mediaId?.toLongOrNull()
                        if (songId != null) {
                            scope.launch {
                                val isLiked = likedSongsDao.isLiked(songId) > 0
                                if (isLiked) {
                                    likedSongsDao.unlikeSong(songId)
                                } else {
                                    likedSongsDao.likeSong(LikedSongEntity(songId = songId, likedAt = System.currentTimeMillis()))
                                }
                                pushWidgetState()
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_WIDGET_PLAY_PAUSE)
            addAction(ACTION_WIDGET_SKIP_NEXT)
            addAction(ACTION_WIDGET_SKIP_PREV)
            addAction(ACTION_WIDGET_SEEK)
            addAction(ACTION_WIDGET_TOGGLE_LIKE)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, widgetActionsReceiver, filter,
            dev.yuwixx.resonance.ui.glancewidget.PERMISSION_WIDGET_CONTROL,
            /* scheduler= */ null,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun applyReplayGain(
        trackGainDb: Float?,
        albumGainDb: Float?,
        mode: String,
        preampDb: Float = 0f,
    ) {
        val gainDb = when (mode) {
            "TRACK" -> trackGainDb
            "ALBUM" -> albumGainDb ?: trackGainDb
            else -> null
        } ?: return

        val totalGain = gainDb + preampDb
        val linearGain = Math.pow(10.0, totalGain / 20.0).toFloat()
        player.volume = linearGain.coerceIn(0.01f, 4.0f)
    }

    // ─── Android Auto / Media Browser ───

    // Exposes a two-level browse tree (Songs, Playlists) to Android Auto and other Media3 browsers.
    private inner class LibraryCallback : MediaLibrarySession.Callback {

        private val ROOT_ID      = "resonance_root"
        private val SONGS_ID     = "songs"
        private val PLAYLISTS_ID = "playlists"

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Resonance")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                ).build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch(Dispatchers.IO) {
                try {
                    val items = when (parentId) {
                        ROOT_ID -> buildRootChildren()
                        SONGS_ID -> buildSongItems()
                        PLAYLISTS_ID -> buildPlaylistItems()
                        else -> {
                            if (parentId.startsWith("playlist_")) {
                                val id = parentId.removePrefix("playlist_").toLongOrNull()
                                if (id != null) buildPlaylistSongItems(id) else emptyList()
                            } else emptyList()
                        }
                    }
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                } catch (e: Exception) {
                    future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
                }
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolvedItems = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                item.buildUpon().setUri(uri).build()
            }
            return Futures.immediateFuture(resolvedItems.toMutableList())
        }

        // Backs the android.media.action.MEDIA_PLAY_FROM_SEARCH intent filter: Android Auto /
        // Assistant voice search ("Play <query> on Resonance") arrives here as a single
        // unresolved MediaItem carrying only a search string, which we resolve against the
        // local library by title/artist/album.
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val query = mediaItems.singleOrNull()?.requestMetadata?.searchQuery
            if (query.isNullOrBlank()) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
                )
            }

            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch(Dispatchers.IO) {
                val matches = songDao.searchSongs(query).first().map { it.toAutoMediaItem() }
                val resolved = matches.ifEmpty { mediaItems }
                future.set(MediaSession.MediaItemsWithStartPosition(resolved, 0, 0L))
            }
            return future
        }

        private fun buildRootChildren(): List<MediaItem> = listOf(
            browseItem(SONGS_ID, "Songs", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
            browseItem(PLAYLISTS_ID, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        )

        private suspend fun buildSongItems(): List<MediaItem> =
            songDao.getAllSongsList().map { it.toAutoMediaItem() }

        private suspend fun buildPlaylistItems(): List<MediaItem> =
            playlistDao.getAllPlaylistsList().map { playlist ->
                browseItem("playlist_${playlist.id}", playlist.name, MediaMetadata.MEDIA_TYPE_PLAYLIST)
            }

        private suspend fun buildPlaylistSongItems(playlistId: Long): List<MediaItem> {
            val refs = playlistDao.getPlaylistSongRefs(playlistId)
            return refs.mapNotNull { ref ->
                songDao.getSongById(ref.songId)?.toAutoMediaItem()
            }
        }

        private fun browseItem(id: String, title: String, mediaType: Int) =
            MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(mediaType)
                        .build()
                ).build()

        private fun dev.yuwixx.resonance.data.database.entity.SongEntity.toAutoMediaItem(): MediaItem {
            val artworkUri = android.content.ContentUris.withAppendedId(
                android.net.Uri.parse("content://media/external/audio/albumart"),
                albumId
            )
            return MediaItem.Builder()
                .setMediaId(id.toString())
                .setUri(android.net.Uri.parse(uri))
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setMediaUri(android.net.Uri.parse(uri))
                        .build()
                )
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setAlbumTitle(album)
                        .setArtworkUri(artworkUri)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build()
                ).build()
        }
    }
}