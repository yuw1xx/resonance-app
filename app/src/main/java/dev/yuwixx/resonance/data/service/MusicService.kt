package dev.yuwixx.resonance.data.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import dev.yuwixx.resonance.data.database.entity.NavidromeSongEntity
import dev.yuwixx.resonance.data.database.entity.QueueEntity
import dev.yuwixx.resonance.data.database.entity.SongEntity
import dev.yuwixx.resonance.data.model.MusicSource
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.data.repository.NavidromeDownloadRepository
import dev.yuwixx.resonance.data.repository.NavidromeSyncRepository
import dev.yuwixx.resonance.di.ApplicationScope
import dev.yuwixx.resonance.domain.usecase.ReplayGainProcessor
import dev.yuwixx.resonance.ui.glancewidget.ACTION_WIDGET_PLAY_PAUSE
import dev.yuwixx.resonance.ui.glancewidget.ACTION_WIDGET_SEEK
import dev.yuwixx.resonance.ui.glancewidget.ACTION_WIDGET_SKIP_NEXT
import dev.yuwixx.resonance.ui.glancewidget.ACTION_WIDGET_SKIP_PREV
import dev.yuwixx.resonance.ui.glancewidget.ACTION_WIDGET_TOGGLE_LIKE
import dev.yuwixx.resonance.ui.glancewidget.EXTRA_SEEK_FRACTION_PCT
import dev.yuwixx.resonance.ui.glancewidget.PERMISSION_WIDGET_CONTROL
import dev.yuwixx.resonance.ui.glancewidget.ResonanceWidget
import java.io.File
import java.util.Random
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

@UnstableApi
@AndroidEntryPoint
class MusicService : MediaLibraryService() {

    @Inject lateinit var prefs: ResonancePreferences
    @Inject lateinit var queueDao: QueueDao
    @Inject lateinit var songDao: SongDao
    @Inject lateinit var navidromeSongDao: NavidromeSongDao
    @Inject lateinit var playlistDao: PlaylistDao
    @Inject lateinit var likedSongsDao: LikedSongsDao
    @Inject lateinit var navidromeSyncRepository: NavidromeSyncRepository
    @Inject lateinit var navidromeDownloadRepository: NavidromeDownloadRepository
    @Inject lateinit var replayGainProcessor: ReplayGainProcessor
    @Inject lateinit var navidromePreloader: NavidromePreloader
    @Inject lateinit var eqStateHolder: EqStateHolder
    @Inject lateinit var scrobbleTracker: ScrobbleTracker
    @Inject lateinit var streamCache: SimpleCache
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var equalizer: Equalizer? = null

    private var crossfadeJob: Job? = null
    /** Mirrors player.isPlaying so loops can suspend while paused instead of polling. */
    private val playingState = MutableStateFlow(false)
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
    private var lockscreenArtworkEnabled = true

    // The sleep timer lives in the service so it keeps running with the UI closed.
    private var sleepJob: Job? = null
    private var sleepEndsAt = 0L
    private var sleepMinutes = 0
    private var sleepTracksLeft = 0
    private var sleepFade = false
    // Multiplies the normal target volume while a sleep fade-out is running.
    private var sleepFadeFactor = 1f

    // Cached for widget pushes so the periodic progress refresh doesn't hit the DB/DataStore.
    private var widgetIsLiked = false
    private var widgetCornerRadius = 28

    private var widgetActionsReceiver: BroadcastReceiver? = null
    private var widgetProgressJob: Job? = null

    // True while restoreQueue applies the saved shuffle order, which must not be reshuffled.
    private var restoringShuffle = false
    private var smartShuffleEnabled: Boolean = false
    // The playlist's song IDs in window order. A shuffle-order-only timeline change fires the same
    // callback as a new queue; comparing this tells them apart.
    private var lastKnownMediaItemIds: List<Long> = emptyList()

    override fun onCreate() {
        super.onCreate()
        buildPlayer()
        buildSession()
        setupEqualizer()
        applyInitialPreferences()
        observePreferences()
        restoreQueue()
        registerHeadphonesReceiver()
        registerWidgetActionsReceiver()
    }

    private fun buildPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs                    */ 15_000,
                /* maxBufferMs                    */ 60_000,
                /* bufferForPlaybackMs            */  2_500,
                /* bufferForPlaybackAfterRebuffer */  5_000,
            )
            .build()

        // Network reads go through the stream cache; content:// and file:// are read directly.
        val upstream = DefaultDataSource.Factory(this)
        val dataSourceFactory = StreamCachingDataSource.Factory(
            cached = CacheDataSource.Factory()
                .setCache(streamCache)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
            direct = upstream,
        )

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(dataSourceFactory)
            )
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

        scrobbleTracker.attach(player, scope)

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Only tracks that finished on their own (including repeat-one) count toward a
                // track-based sleep timer; manual skips don't.
                val finishedOnItsOwn = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                if (finishedOnItsOwn && sleepTracksLeft > 0) {
                    sleepTracksLeft--
                    if (sleepTracksLeft == 0) {
                        player.pause()
                        clearSleepTimer()
                    } else {
                        publishSleepState()
                        if (sleepTracksLeft == 1 && sleepFade) startTrackEndFadeWatcher()
                    }
                }
                persistQueue()
                applyVolumeForCurrentItem()
                // After a crossfade-out, bring the next track in from silence.
                scheduleCrossfade(fadeIn = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                applyLockscreenArtwork()
                refreshWidgetLikeState()
                preloadUpcomingTracks()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    persistQueue()
                    if (sleepTracksLeft > 0) clearSleepTimer()
                }
                pushWidgetState()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // The fade watcher sleeps until the fade window; a seek moves that window.
                if (reason == Player.DISCONTINUITY_REASON_SEEK) scheduleCrossfade()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playingState.value = isPlaying
                if (isPlaying) {
                    preloadUpcomingTracks()
                    startWidgetProgressUpdates()
                } else {
                    stopWidgetProgressUpdates()
                    pushWidgetState()
                    // Pausing is the natural checkpoint for the resume position.
                    persistQueue()
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled && !restoringShuffle) applyShuffleOrder()
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val count = player.mediaItemCount
                val currentIds = (0 until count).map { player.getMediaItemAt(it).mediaId.toLongOrNull() ?: 0L }
                if (currentIds != lastKnownMediaItemIds) {
                    val previousIds = lastKnownMediaItemIds
                    lastKnownMediaItemIds = currentIds
                    // Additions and removals keep the existing shuffle order (additions are placed
                    // where the user asked); only a new queue gets a fresh shuffle.
                    val currentSet = currentIds.toHashSet()
                    val previousSet = previousIds.toHashSet()
                    val isPureAddition = currentIds.size > previousIds.size && previousIds.all { it in currentSet }
                    val isPureRemoval = currentIds.size < previousIds.size && currentIds.all { it in previousSet }
                    if (player.shuffleModeEnabled) {
                        when {
                            isPureAddition -> placeAdditionsInShuffle(previousIds, currentIds)
                            isPureRemoval -> Unit
                            else -> applyShuffleOrder()
                        }
                    }
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                setupEqualizer()
            }
        })
    }

    // Follows the actual play order (shuffle and repeat included).
    private fun preloadUpcomingTracks() {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return
        val upcoming = mutableListOf<String>()
        var idx = player.currentMediaItemIndex
        repeat(PRELOAD_AHEAD) {
            idx = timeline.getNextWindowIndex(idx, player.repeatMode, player.shuffleModeEnabled)
            if (idx == C.INDEX_UNSET) return@repeat
            val uri = player.getMediaItemAt(idx).localConfiguration?.uri?.toString() ?: return@repeat
            if (uri.contains("/rest/stream")) upcoming += uri
        }
        if (upcoming.isNotEmpty()) navidromePreloader.preload(upcoming)
    }

    companion object {
        private const val PRELOAD_AHEAD = 2
        private const val WIDGET_PROGRESS_INTERVAL_MS = 10_000L
        private const val EXTRA_STASHED_ARTWORK = "resonance.stashedArtworkUri"
        // Poll interval while the duration is still unknown; some streams report none at first.
        private const val DURATION_UNKNOWN_POLL_MS = 300L
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
            // Cleared first so the EQ callbacks never reach a released effect if creation fails.
            equalizer = null

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

    private fun applyInitialPreferences() {
        scope.launch {
            // Repeat and shuffle come back with the saved queue (restoreQueue).
            player.skipSilenceEnabled = prefs.skipSilence.first()
            smartShuffleEnabled = prefs.smartShuffleEnabled.first()

            gaplessEnabled = prefs.gaplessEnabled.first()
            crossfadeDurationMs = prefs.crossfadeDurationMs.first()
            volumeNormalizationEnabled = prefs.volumeNormalization.first()
            replayGainMode = prefs.replayGainMode.first()
            replayGainPreampDb = prefs.replayGainPreampDb.first()
            resumeOnHeadphones = prefs.resumeOnHeadphones.first()
            showSkipButtons = prefs.showSkipButtons.first()

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

        // Audio focus is always handled (calls, other players). The ducking preference only
        // decides what happens on a "may duck" focus loss: lower the volume, or pause. Media3
        // pauses instead of ducking for speech content, so that's how "don't duck" is expressed.
        scope.launch {
            prefs.duckAudioOnFocusLoss.collect { duckAudio ->
                val attrs = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(if (duckAudio) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build()
                player.setAudioAttributes(attrs, /* handleAudioFocus= */ true)
            }
        }

        // Keep the CPU (and, for streams, Wi-Fi) awake only while actually playing; Media3
        // holds and releases the locks with the playback state.
        scope.launch {
            prefs.musicSource.collect { source ->
                player.setWakeMode(if (source == MusicSource.NAVIDROME) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL)
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
                lockscreenArtworkEnabled = enabled
                applyLockscreenArtwork()
            }
        }

        scope.launch {
            var first = true
            prefs.cornerRadius.collect { radius ->
                widgetCornerRadius = radius
                // The first value is just the cache fill; only later changes need a repaint.
                if (!first && player.currentMediaItem != null) pushWidgetState()
                first = false
            }
        }

        scope.launch {
            prefs.showSkipButtons.collect { show ->
                showSkipButtons = show
                updateNotificationCommandButtons(show)
            }
        }
    }

    private fun scheduleCrossfade(fadeIn: Boolean = false) {
        crossfadeJob?.cancel()

        val fadeDurationMs = when {
            crossfadeDurationMs > 0 -> crossfadeDurationMs
            !gaplessEnabled -> 200
            else -> 0
        }

        if (fadeDurationMs == 0) {
            player.volume = computeTargetVolume()
            return
        }

        crossfadeJob = scope.launch {
            if (fadeIn && crossfadeDurationMs > 0) rampVolumeUp(fadeDurationMs, computeTargetVolume())
            while (isActive) {
                // Nothing to do while paused: wait for playback instead of polling.
                if (!player.isPlaying) {
                    playingState.first { it }
                    continue
                }
                val duration = player.duration
                val position = player.currentPosition
                if (duration > 0 && position > 0) {
                    val remaining = duration - position
                    val untilFade = remaining - fadeDurationMs
                    if (untilFade > 1_000) {
                        // Far from the end: one long sleep until just before the fade window
                        // (re-checked at least every 30 s; seeks restart this job).
                        val target = computeTargetVolume()
                        if (player.volume < target * 0.95f) player.volume = target
                        val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
                        delay(((untilFade - 500) / speed).toLong().coerceIn(50L, 30_000L))
                        continue
                    }
                    if (remaining in 1..fadeDurationMs) {
                        val fadeProgress = 1f - (remaining.toFloat() / fadeDurationMs)
                        player.volume = computeTargetVolume() * (1f - fadeProgress)
                    } else if (remaining <= 0 || player.playbackState == Player.STATE_ENDED) {
                        // The next track's transition starts the fade-in.
                        break
                    } else {
                        val target = computeTargetVolume()
                        if (player.volume < target * 0.95f) {
                            player.volume = target
                        }
                    }
                }
                delay(if (duration > 0) 50 else DURATION_UNKNOWN_POLL_MS)
            }
        }
    }

    private suspend fun rampVolumeUp(durationMs: Int, targetVolume: Float) {
        player.volume = 0f
        val steps = (durationMs / 50).coerceAtLeast(1)
        for (i in 0..steps) {
            player.volume = targetVolume * (i.toFloat() / steps)
            delay(50)
        }
        player.volume = targetVolume
    }

    // ReplayGain as a linear multiplier. Without a tag, normalisation falls back to -3 dBFS (0.707).
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
                        trackPeak = null,
                        albumGainDb = albumGain,
                        albumPeak = null,
                    )
                    val mode = when (replayGainMode) {
                        "ALBUM" -> ReplayGainProcessor.Mode.ALBUM
                        "OFF" -> ReplayGainProcessor.Mode.OFF
                        else -> ReplayGainProcessor.Mode.TRACK
                    }
                    val multiplier = replayGainProcessor.computeMultiplier(info, mode, replayGainPreampDb)
                    return multiplier * sleepFadeFactor
                }
            }
        }

        if (volumeNormalizationEnabled) {
            return 0.707f * sleepFadeFactor
        }

        return sleepFadeFactor
    }

    private fun applyVolumeForCurrentItem() {
        if (crossfadeJob?.isActive == true) return
        player.volume = computeTargetVolume()
    }

    private fun setSleepTimer(minutes: Int, tracks: Int, fade: Boolean) {
        clearSleepTimer(publish = false)
        sleepFade = fade
        if (minutes > 0) {
            sleepMinutes = minutes
            sleepEndsAt = System.currentTimeMillis() + minutes * 60_000L
            sleepJob = scope.launch {
                val fadeStart = sleepEndsAt - if (fade) SessionProtocol.SLEEP_FADE_MS else 0L
                delay((fadeStart - System.currentTimeMillis()).coerceAtLeast(0L))
                if (fade) {
                    while (isActive) {
                        val remaining = sleepEndsAt - System.currentTimeMillis()
                        if (remaining <= 0) break
                        setSleepFade(remaining.toFloat() / SessionProtocol.SLEEP_FADE_MS)
                        delay(100)
                    }
                }
                player.pause()
                clearSleepTimer()
            }
        } else if (tracks > 0) {
            sleepTracksLeft = tracks
            if (tracks == 1 && fade) startTrackEndFadeWatcher()
        }
        publishSleepState()
    }

    // Fades out across the final seconds of the last track of a track-based timer.
    private fun startTrackEndFadeWatcher() {
        sleepJob?.cancel()
        sleepJob = scope.launch {
            while (isActive && sleepTracksLeft == 1) {
                val duration = player.duration
                val remaining = if (duration > 0) duration - player.currentPosition else Long.MAX_VALUE
                setSleepFade((remaining.toFloat() / SessionProtocol.SLEEP_FADE_MS).coerceIn(0f, 1f))
                delay(if (remaining < SessionProtocol.SLEEP_FADE_MS) 100 else 1_000)
            }
        }
    }

    private fun setSleepFade(factor: Float) {
        sleepFadeFactor = factor.coerceIn(0f, 1f)
        if (crossfadeJob?.isActive != true) player.volume = computeTargetVolume()
    }

    private fun clearSleepTimer(publish: Boolean = true) {
        sleepJob?.cancel()
        sleepJob = null
        sleepEndsAt = 0L
        sleepMinutes = 0
        sleepTracksLeft = 0
        if (sleepFadeFactor != 1f) setSleepFade(1f)
        if (publish) publishSleepState()
    }

    private fun publishSleepState() {
        val extras = Bundle().apply {
            when {
                sleepEndsAt > 0 -> {
                    putString(SessionProtocol.EXTRA_SLEEP_TYPE, "time")
                    putLong(SessionProtocol.EXTRA_SLEEP_ENDS_AT, sleepEndsAt)
                    putInt(SessionProtocol.EXTRA_SLEEP_MINUTES, sleepMinutes)
                }
                sleepTracksLeft > 0 -> {
                    putString(SessionProtocol.EXTRA_SLEEP_TYPE, "tracks")
                    putInt(SessionProtocol.EXTRA_SLEEP_TRACKS, sleepTracksLeft)
                }
                else -> putString(SessionProtocol.EXTRA_SLEEP_TYPE, "off")
            }
            putBoolean(SessionProtocol.EXTRA_SLEEP_FADE, sleepFade)
        }
        mediaSession.setSessionExtras(extras)
    }

    // Hides or restores the current item's artwork on the lock screen and notification. The
    // original URI is kept in the item's extras so it can be put back. Only the current item is
    // touched, so this runs on every transition, and does nothing if the item is already right.
    private fun applyLockscreenArtwork() {
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        if (index < 0) return
        val metadata = item.mediaMetadata
        val stashed = metadata.extras?.getString(EXTRA_STASHED_ARTWORK)

        val updatedMetadata = when {
            !lockscreenArtworkEnabled && metadata.artworkUri != null -> {
                val extras = Bundle(metadata.extras ?: Bundle()).apply {
                    putString(EXTRA_STASHED_ARTWORK, metadata.artworkUri.toString())
                }
                metadata.buildUpon().setArtworkUri(null).setArtworkData(null, null).setExtras(extras).build()
            }
            lockscreenArtworkEnabled && stashed != null -> {
                val extras = Bundle(metadata.extras ?: Bundle()).apply { remove(EXTRA_STASHED_ARTWORK) }
                metadata.buildUpon().setArtworkUri(Uri.parse(stashed)).setExtras(extras).build()
            }
            else -> return
        }
        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(updatedMetadata).build())
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
                        val artworkUri = entity.coverArtUrl?.let { Uri.parse(it) }
                        // Play the downloaded local copy instead of streaming, when one exists.
                        val localPath = navidromeDownloadRepository.localPathFor(id)
                        val playbackUri = localPath?.let { Uri.fromFile(File(it)) }
                            ?: Uri.parse(entity.streamUrl)
                        MediaItem.Builder()
                            .setMediaId(entity.numericId.toString())
                            .setUri(playbackUri)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(entity.title)
                                    .setArtist(entity.artist)
                                    .setAlbumTitle(entity.album)
                                    .setArtworkUri(artworkUri)
                                    .setExtras(Bundle())
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
                        val artworkUri = ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            entity.albumId
                        )
                        val extras = Bundle().apply {
                            entity.replayGainTrack?.let { putFloat("replayGainTrack", it) }
                            entity.replayGainAlbum?.let { putFloat("replayGainAlbum", it) }
                        }
                        MediaItem.Builder()
                            .setMediaId(entity.id.toString())
                            .setUri(Uri.parse(entity.uri))
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
                                    .setMediaUri(Uri.parse(entity.uri))
                                    .build()
                            )
                            .build()
                    }
                }
                if (mediaItems.isEmpty()) return@launch

                val repeatMode = when (queueEntity.repeatMode) {
                    "ONE" -> Player.REPEAT_MODE_ONE
                    "ALL" -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }

                withContext(Dispatchers.Main) {
                    // Only resume mid-track when the saved item survived the restore; if songs
                    // went missing the indices shift, so start that item from the top instead.
                    val startIndex = queueEntity.currentIndex.coerceIn(0, mediaItems.lastIndex)
                    val startPositionMs = if (mediaItems.size == songIds.size) queueEntity.positionMs else 0L
                    player.setMediaItems(mediaItems, startIndex, startPositionMs)
                    player.repeatMode = repeatMode

                    // Restore the saved shuffle order so next/previous follow the same sequence.
                    val savedOrder = queueEntity.originalOrder
                    var restoredShuffle = false
                    if (queueEntity.shuffleEnabled && savedOrder.isNotEmpty()) {
                        val shuffledIds = savedOrder.split(",").mapNotNull { it.trim().toLongOrNull() }
                        // A list (not map) of positions per ID so a duplicate song in the queue
                        // maps each occurrence to its own position instead of collapsing to one.
                        val idToPositions = songIds.withIndex().groupBy({ it.value }, { it.index })
                            .mapValues { (_, indices) -> ArrayDeque(indices) }
                        val shuffleTable = shuffledIds.mapNotNull { idToPositions[it]?.removeFirstOrNull() }.toIntArray()
                        if (shuffleTable.size == mediaItems.size) {
                            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(shuffleTable, 0L))
                            restoredShuffle = true
                        }
                    }
                    restoringShuffle = restoredShuffle
                    player.shuffleModeEnabled = queueEntity.shuffleEnabled
                    restoringShuffle = false

                    player.prepare()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MusicService", "Failed to restore queue", e)
            }
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
            positionMs = player.currentPosition.coerceAtLeast(0L),
        )
    }

    private suspend fun saveQueueEntity(entity: QueueEntity) {
        try {
            queueDao.saveQueue(entity)
        } catch (e: CancellationException) {
            throw e
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

    // Applies a random order right away, so shuffle is never on with a sequential order in place.
    // Smart shuffle then refines it in the background.
    private fun applyShuffleOrder() {
        val count = player.mediaItemCount
        if (count == 0) return
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(count, System.currentTimeMillis()))
        if (smartShuffleEnabled) {
            scope.launch { applySmartShuffleOrder() }
        }
    }

    // ExoPlayer scatters inserted items randomly in the shuffle order; put them where the user
    // asked for them instead.
    private fun placeAdditionsInShuffle(previousIds: List<Long>, currentIds: List<Long>) {
        // Window indices of the new items: walk both lists, skipping matches in order.
        val inserted = ArrayList<Int>()
        var p = 0
        currentIds.forEachIndexed { i, id ->
            if (p < previousIds.size && previousIds[p] == id) p++ else inserted += i
        }
        if (inserted.isEmpty()) return
        val timeline = player.currentTimeline
        val playOrder = ArrayList<Int>()
        var idx = timeline.getFirstWindowIndex(true)
        while (idx != C.INDEX_UNSET) {
            playOrder += idx
            idx = timeline.getNextWindowIndex(idx, Player.REPEAT_MODE_OFF, true)
        }
        // "Play next" inserts right after the current window, "add to queue" at the end; other
        // insertions (an undo restoring a song mid-queue) keep ExoPlayer's placement.
        val current = player.currentMediaItemIndex
        val insertedSet = inserted.toHashSet()
        // New windows directly after the current one,
        val afterCurrent = ArrayList<Int>()
        var next = current + 1
        while (next in insertedSet) afterCurrent += next++
        // and new windows at the end of the queue.
        val atEnd = ArrayList<Int>()
        var last = currentIds.lastIndex
        while (last in insertedSet && last !in afterCurrent) atEnd.add(0, last--)
        val moved = (afterCurrent + atEnd).toHashSet()
        if (moved.isEmpty()) return
        playOrder.removeAll(moved)
        val currentPos = playOrder.indexOf(current)
        playOrder.addAll(if (currentPos >= 0) currentPos + 1 else 0, afterCurrent)
        playOrder.addAll(atEnd)
        if (playOrder.size == currentIds.size) {
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(playOrder.toIntArray(), System.currentTimeMillis()))
        }
    }

    private suspend fun applySmartShuffleOrder() {
        val count = player.mediaItemCount
        if (count == 0) return
        val songIds = (0 until count).map { player.getMediaItemAt(it).mediaId.toLongOrNull() ?: 0L }
        val listenCounts = withContext(Dispatchers.IO) {
            // SQLite allows at most 999 bound variables on older Android versions.
            songIds.distinct().chunked(900)
                .flatMap { songDao.getListenCountsForIds(it) }
                .associate { it.songId to it.playCount }
        }
        val maxListens = listenCounts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val rng = Random()
        val shuffledIndices = (0 until count)
            .sortedByDescending { i ->
                val listens = listenCounts[songIds[i]] ?: 0
                listens * 2 + rng.nextInt(maxListens + 1)
            }
            .toIntArray()
        // The playlist may have changed while the DB lookup was in flight; bail rather than
        // hand ExoPlayer a shuffled-index array whose length no longer matches mediaItemCount.
        if (player.mediaItemCount != count) return
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
    }

    // The play order as the player has it now, so inserts and removals made since the last
    // shuffle are included.
    private fun buildShuffledOrder(ids: List<Long>, shuffle: Boolean): String {
        if (!shuffle) return ""
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return ""
        val result = mutableListOf<Long>()
        var idx = timeline.getFirstWindowIndex(true)
        while (idx != C.INDEX_UNSET) {
            result.add(ids.getOrElse(idx) { 0L })
            idx = timeline.getNextWindowIndex(idx, Player.REPEAT_MODE_OFF, true)
        }
        return result.joinToString(",")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistQueue()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // The queue is also saved on every transition and pause. This last save runs on the
        // process-wide scope so it finishes after the service is gone without blocking the main
        // thread.
        val finalQueue = buildQueueEntity()
        val appContext = applicationContext
        applicationScope.launch(Dispatchers.IO) {
            finalQueue?.let { saveQueueEntity(it) }
            try { ResonanceWidget.updateState(appContext, "", "", "", false, false, false, false) }
            catch (e: Exception) { Log.e("MusicService", "Failed to clear widget state", e) }
        }
        // EqStateHolder is a singleton; drop the callbacks that capture this service.
        eqStateHolder.onBandLevelChanged = null
        eqStateHolder.onEnabledChanged = null
        crossfadeJob?.cancel()
        stopWidgetProgressUpdates()
        widgetActionsReceiver?.let { unregisterReceiver(it) }
        headphonesReceiver?.let { unregisterReceiver(it) }
        scope.cancel()
        equalizer?.release()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    private fun pushWidgetState() {
        val mediaItem = player.currentMediaItem ?: run {
            val cornerRadius = widgetCornerRadius
            scope.launch {
                ResonanceWidget.updateState(
                    context = this@MusicService,
                    title = "", artist = "", artworkUri = "",
                    isPlaying = false, hasSong = false,
                    hasPrev = false, hasNext = false, progress = 0f,
                    isLiked = false, cornerRadiusDp = cornerRadius,
                )
            }
            return
        }
        val meta = mediaItem.mediaMetadata
        val title = meta.title?.toString() ?: mediaItem.mediaId
        val artist = meta.artist?.toString() ?: meta.albumArtist?.toString() ?: ""
        val artworkUri = meta.artworkUri?.toString() ?: ""
        val duration = player.duration.takeIf { it > 0 } ?: 1L
        val progress = (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
        val isLiked = widgetIsLiked
        val cornerRadius = widgetCornerRadius
        scope.launch {
            ResonanceWidget.updateState(
                context = this@MusicService,
                title = title,
                artist = artist,
                artworkUri = artworkUri,
                isPlaying = player.isPlaying,
                hasSong = true,
                hasPrev = player.hasPreviousMediaItem(),
                hasNext = player.hasNextMediaItem(),
                progress = progress,
                isLiked = isLiked,
                cornerRadiusDp = cornerRadius,
            )
        }
    }

    private fun refreshWidgetLikeState() {
        val songId = player.currentMediaItem?.mediaId?.toLongOrNull()
        scope.launch {
            widgetIsLiked = songId?.let { withContext(Dispatchers.IO) { likedSongsDao.isLiked(it) > 0 } } ?: false
            pushWidgetState()
        }
    }

    // Track/play-state/like changes push immediately; this loop only advances the widget's
    // progress bar, so a coarse interval is plenty and saves a Glance update every second.
    private fun startWidgetProgressUpdates() {
        widgetProgressJob?.cancel()
        widgetProgressJob = scope.launch {
            // Only the progress bar needs ticking, and only when someone can see it: stop if
            // there are no widgets, and don't wake up while the screen is off.
            val widgets = GlanceAppWidgetManager(this@MusicService)
            if (widgets.getGlanceIds(ResonanceWidget::class.java).isEmpty()) return@launch
            val power = getSystemService(PowerManager::class.java)
            while (isActive) {
                if (power?.isInteractive != false) pushWidgetState()
                delay(WIDGET_PROGRESS_INTERVAL_MS)
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
                                // Same as liking in the app: Navidrome songs are (un)starred too,
                                // or the next sync would undo this.
                                val navidromeId = navidromeSongDao.getSongByNumericId(songId)?.navidromeId
                                if (isLiked) {
                                    likedSongsDao.unlikeSong(songId)
                                    navidromeId?.let { navidromeSyncRepository.unstar(songId, it) }
                                } else {
                                    likedSongsDao.likeSong(LikedSongEntity(songId = songId, likedAt = System.currentTimeMillis()))
                                    navidromeId?.let { navidromeSyncRepository.star(songId, it) }
                                }
                                widgetIsLiked = !isLiked
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
        ContextCompat.registerReceiver(
            this, widgetActionsReceiver, filter,
            PERMISSION_WIDGET_CONTROL,
            /* scheduler= */ null,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    // Exposes a two-level browse tree (Songs, Playlists) to Android Auto and other Media3 browsers.
    private inner class LibraryCallback : MediaLibrarySession.Callback {

        private val sleepSetCommand = SessionCommand(SessionProtocol.CMD_SLEEP_SET, Bundle.EMPTY)
        private val sleepCancelCommand = SessionCommand(SessionProtocol.CMD_SLEEP_CANCEL, Bundle.EMPTY)

        // Only the app's own controller gets the custom commands; Android Auto, the system
        // media controls and other apps keep the default command set.
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val result = super.onConnect(session, controller)
            if (controller.packageName != packageName) return result
            val commands = result.availableSessionCommands.buildUpon()
                .add(sleepSetCommand)
                .add(sleepCancelCommand)
                .build()
            // Player commands stay at the builder's default; the ones in `result` are empty.
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                SessionProtocol.CMD_SLEEP_SET -> setSleepTimer(
                    minutes = args.getInt(SessionProtocol.ARG_MINUTES, 0),
                    tracks = args.getInt(SessionProtocol.ARG_TRACKS, 0),
                    fade = args.getBoolean(SessionProtocol.ARG_FADE, false),
                )
                SessionProtocol.CMD_SLEEP_CANCEL -> clearSleepTimer()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        private val ROOT_ID = "resonance_root"
        private val SONGS_ID = "songs"
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
                    // Honour the browser's paging instead of shipping the whole library in one
                    // Binder transaction (large libraries could exceed the transaction limit).
                    val paged = if (pageSize > 0 && pageSize < Int.MAX_VALUE) {
                        val from = (page.toLong() * pageSize).coerceAtMost(items.size.toLong()).toInt()
                        items.subList(from, (from + pageSize).coerceAtMost(items.size))
                    } else items
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(paged), params))
                } catch (e: CancellationException) {
                    throw e
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

        // Voice search from Android Auto or Assistant ("Play <query> on Resonance") arrives as a
        // single MediaItem with only a search string; resolve it by title, artist or album.
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
                // Always complete the future, or the controller waits forever.
                val resolved = try {
                    searchForPlayback(query).ifEmpty { mediaItems }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("MusicService", "Voice search failed for '$query'", e)
                    mediaItems
                }
                future.set(MediaSession.MediaItemsWithStartPosition(resolved, 0, 0L))
            }
            return future
        }

        private fun buildRootChildren(): List<MediaItem> = listOf(
            browseItem(SONGS_ID, "Songs", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
            browseItem(PLAYLISTS_ID, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        )

        private suspend fun buildSongItems(): List<MediaItem> =
            if (prefs.musicSource.first() == MusicSource.NAVIDROME) {
                navidromeSongDao.getAllSongsList().map { it.toAutoMediaItem() }
            } else {
                songDao.getAllSongsList().map { it.toAutoMediaItem() }
            }

        private suspend fun buildPlaylistItems(): List<MediaItem> =
            playlistDao.getAllPlaylistsList().map { playlist ->
                browseItem("playlist_${playlist.id}", playlist.name, MediaMetadata.MEDIA_TYPE_PLAYLIST)
            }

        private suspend fun searchForPlayback(query: String): List<MediaItem> {
            if (prefs.musicSource.first() == MusicSource.NAVIDROME) {
                val q = query.trim().lowercase()
                return navidromeSongDao.getAllSongsList()
                    .filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q) }
                    .map { it.toAutoMediaItem() }
            }
            return songDao.searchSongs(query).first().map { it.toAutoMediaItem() }
        }

        // One batched lookup per source instead of a query per song; Navidrome-linked
        // playlists store numeric Navidrome ids, so resolve those against navidrome_songs.
        private suspend fun buildPlaylistSongItems(playlistId: Long): List<MediaItem> {
            val ids = playlistDao.getPlaylistSongRefs(playlistId).map { it.songId }
            if (ids.isEmpty()) return emptyList()
            val local = ids.chunked(900).flatMap { songDao.getSongsByIds(it) }.associateBy { it.id }
            val missing = ids.filter { it !in local }
            val remote = if (missing.isEmpty()) emptyMap()
                else missing.chunked(900).flatMap { navidromeSongDao.getSongsByNumericIds(it) }.associateBy { it.numericId }
            return ids.mapNotNull { id -> local[id]?.toAutoMediaItem() ?: remote[id]?.toAutoMediaItem() }
        }

        private fun NavidromeSongEntity.toAutoMediaItem(): MediaItem {
            val localPath = navidromeDownloadRepository.localPathForSync(numericId)
            val playbackUri = localPath?.let { Uri.fromFile(File(it)) }
                ?: Uri.parse(streamUrl)
            return MediaItem.Builder()
                .setMediaId(numericId.toString())
                .setUri(playbackUri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(playbackUri).build())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setAlbumTitle(album)
                        .setArtworkUri(coverArtUrl?.let { Uri.parse(it) })
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build()
                ).build()
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

        private fun SongEntity.toAutoMediaItem(): MediaItem {
            val artworkUri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                albumId
            )
            return MediaItem.Builder()
                .setMediaId(id.toString())
                .setUri(Uri.parse(uri))
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setMediaUri(Uri.parse(uri))
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