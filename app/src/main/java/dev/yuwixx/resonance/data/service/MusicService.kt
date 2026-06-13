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
import android.os.Bundle
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
import dev.yuwixx.resonance.data.database.dao.NavidromeSongDao
import dev.yuwixx.resonance.data.database.dao.PlaylistDao
import dev.yuwixx.resonance.data.database.dao.QueueDao
import dev.yuwixx.resonance.data.database.dao.SongDao
import dev.yuwixx.resonance.data.database.entity.QueueEntity
import dev.yuwixx.resonance.data.model.MusicSource
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.domain.usecase.ReplayGainProcessor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import dev.yuwixx.resonance.ui.glancewidget.ACTION_WIDGET_PLAY_PAUSE
import dev.yuwixx.resonance.ui.glancewidget.ACTION_WIDGET_SKIP_NEXT
import dev.yuwixx.resonance.ui.glancewidget.ACTION_WIDGET_SKIP_PREV
import dev.yuwixx.resonance.ui.glancewidget.ResonanceWidget

@UnstableApi
@AndroidEntryPoint
class MusicService : MediaLibraryService() {

    @Inject lateinit var prefs: ResonancePreferences
    @Inject lateinit var queueDao: QueueDao
    @Inject lateinit var songDao: SongDao
    @Inject lateinit var navidromeSongDao: NavidromeSongDao
    @Inject lateinit var playlistDao: PlaylistDao
    @Inject lateinit var replayGainProcessor: ReplayGainProcessor
    @Inject lateinit var navidromePreloader: NavidromePreloader
    @Inject lateinit var eqStateHolder: EqStateHolder

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var equalizer: Equalizer? = null

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
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled) {
                    scope.launch {
                        val newOrder = createShuffleOrder()
                        savedShuffleOrder = newOrder
                        player.setShuffleOrder(newOrder)
                    }
                }
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
        } catch (_: Exception) {}
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
        registerReceiver(headphonesReceiver, filter)
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
                        MediaItem.Builder()
                            .setMediaId(entity.numericId.toString())
                            .setUri(android.net.Uri.parse(entity.streamUrl))
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
                                    .setMediaUri(android.net.Uri.parse(entity.streamUrl))
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
                        val idToPos = songIds.mapIndexed { i, id -> id to i }.toMap()
                        val shuffleTable = shuffledIds.mapNotNull { idToPos[it] }.toIntArray()
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

    fun persistQueue() {
        scope.launch {
            val count = player.mediaItemCount
            if (count == 0) return@launch
            val ids = (0 until count).map { player.getMediaItemAt(it).mediaId.toLongOrNull() ?: 0L }
            val index = player.currentMediaItemIndex
            val shuffle = player.shuffleModeEnabled
            val repeatStr = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> "ONE"
                Player.REPEAT_MODE_ALL -> "ALL"
                else -> "NONE"
            }
            val shuffledOrder = buildShuffledOrder(ids, shuffle)
            withContext(Dispatchers.IO) {
                try {
                    queueDao.saveQueue(
                        QueueEntity(
                            id = 0L,
                            songIds = ids.joinToString(","),
                            currentIndex = index,
                            shuffleEnabled = shuffle,
                            repeatMode = repeatStr,
                            originalOrder = shuffledOrder,
                        )
                    )
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun createShuffleOrder(): ShuffleOrder {
        val count = player.mediaItemCount
        if (count == 0) return ShuffleOrder.DefaultShuffleOrder(0, System.currentTimeMillis())
        if (!prefs.smartShuffleEnabled.first()) {
            return ShuffleOrder.DefaultShuffleOrder(count, System.currentTimeMillis())
        }
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
        return ShuffleOrder.DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis())
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
        val count = player.mediaItemCount
        if (count > 0) {
            val ids = (0 until count).map { player.getMediaItemAt(it).mediaId.toLongOrNull() ?: 0L }
            val index = player.currentMediaItemIndex
            val shuffle = player.shuffleModeEnabled
            val repeatStr = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> "ONE"
                Player.REPEAT_MODE_ALL -> "ALL"
                else -> "NONE"
            }
            val shuffledOrder = buildShuffledOrder(ids, shuffle)
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                try {
                    queueDao.saveQueue(
                        QueueEntity(
                            id = 0L,
                            songIds = ids.joinToString(","),
                            currentIndex = index,
                            shuffleEnabled = shuffle,
                            repeatMode = repeatStr,
                            originalOrder = shuffledOrder,
                        )
                    )
                } catch (_: Exception) {}
            }
        }
        kotlinx.coroutines.runBlocking {
            try { ResonanceWidget.updateState(this@MusicService, "", "", "", false, false, false, false) }
            catch (_: Exception) {}
        }
        crossfadeJob?.cancel()
        stopWidgetProgressUpdates()
        widgetActionsReceiver?.let { unregisterReceiver(it) }
        headphonesReceiver?.let { unregisterReceiver(it) }
        scope.cancel()
        navidromePreloader.destroy()
        equalizer?.release()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    // ─── Home-Screen Widget ───

    private fun pushWidgetState() {
        val mediaItem = player.currentMediaItem ?: run {
            scope.launch {
                ResonanceWidget.updateState(
                    context    = this@MusicService,
                    title      = "", artist = "", artworkUri = "",
                    isPlaying  = false, hasSong = false,
                    hasPrev    = false, hasNext = false, progress = 0f,
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
        scope.launch {
            ResonanceWidget.updateState(
                context    = this@MusicService,
                title      = title,
                artist     = artist,
                artworkUri = artworkUri,
                isPlaying  = player.isPlaying,
                hasSong    = true,
                hasPrev    = player.hasPreviousMediaItem(),
                hasNext    = player.hasNextMediaItem(),
                progress   = progress,
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
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_WIDGET_PLAY_PAUSE)
            addAction(ACTION_WIDGET_SKIP_NEXT)
            addAction(ACTION_WIDGET_SKIP_PREV)
        }
        registerReceiver(widgetActionsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
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
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
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