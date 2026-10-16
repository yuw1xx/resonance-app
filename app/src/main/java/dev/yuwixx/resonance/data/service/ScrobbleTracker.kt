// Scrobbling (Last.fm, Maloja), "now playing" and play-history recording for local playback.
// Lives in MusicService so it keeps working when the UI is gone, and is event-driven: instead
// of polling the position it schedules a single wake-up for the moment the scrobble threshold
// (min listened time AND min % of the track) can first be met, re-planned on play/pause/seek.
package dev.yuwixx.resonance.data.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.yuwixx.resonance.data.database.dao.NavidromeSongDao
import dev.yuwixx.resonance.data.database.dao.SongDao
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.data.repository.LastFmRepository
import dev.yuwixx.resonance.data.repository.MalojaRepository
import dev.yuwixx.resonance.data.repository.MusicRepository
import dev.yuwixx.resonance.data.repository.toDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScrobbleTracker @Inject constructor(
    private val prefs: ResonancePreferences,
    private val songDao: SongDao,
    private val navidromeSongDao: NavidromeSongDao,
    private val lastFm: LastFmRepository,
    private val maloja: MalojaRepository,
    private val musicRepository: MusicRepository,
) {
    private lateinit var player: Player
    private lateinit var scope: CoroutineScope

    private var song: Song? = null
    private var startedAt = 0L          // wall-clock start reported to scrobblers
    private var segmentStart = 0L       // start of the current uninterrupted play segment
    private var accumulatedMs = 0L      // listened time from earlier segments
    private var submitted = false
    private var nowPlayingSent = false
    private var checkJob: Job? = null
    private var resolveJob: Job? = null

    fun attach(player: Player, scope: CoroutineScope) {
        this.player = player
        this.scope = scope
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = startTrack(mediaItem)

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val now = System.currentTimeMillis()
                if (isPlaying) {
                    if (segmentStart == 0L) segmentStart = now
                    sendNowPlayingIfNeeded()
                } else if (segmentStart > 0L) {
                    accumulatedMs += now - segmentStart
                    segmentStart = 0L
                }
                planCheck()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) planCheck()
            }
        })
        startTrack(player.currentMediaItem)
    }

    private fun startTrack(item: MediaItem?) {
        checkJob?.cancel()
        resolveJob?.cancel()
        val now = System.currentTimeMillis()
        song = null
        startedAt = now
        segmentStart = if (player.isPlaying) now else 0L
        accumulatedMs = 0L
        submitted = false
        nowPlayingSent = false
        val id = item?.mediaId?.toLongOrNull() ?: return
        resolveJob = scope.launch {
            song = resolve(id) ?: return@launch
            if (player.isPlaying) sendNowPlayingIfNeeded()
            planCheck()
        }
    }

    private suspend fun resolve(id: Long): Song? {
        songDao.getSongById(id)?.let { return it.toDomain(prefs.artistDelimiter.first()) }
        return navidromeSongDao.getSongByNumericId(id)?.toDomain()
    }

    private fun sendNowPlayingIfNeeded() {
        val s = song ?: return
        if (nowPlayingSent) return
        nowPlayingSent = true
        lastFm.updateNowPlaying(s)
    }

    private fun listenedMs(): Long =
        accumulatedMs + if (segmentStart > 0L) System.currentTimeMillis() - segmentStart else 0L

    /** Schedules the next threshold check, or nothing while paused / already scrobbled. */
    private fun planCheck() {
        checkJob?.cancel()
        if (submitted || song == null || !player.isPlaying) return
        checkJob = scope.launch {
            while (true) {
                val s = song ?: return@launch
                val duration = player.duration.takeIf { it > 0 } ?: s.duration
                if (duration <= 0) return@launch
                val minMs = prefs.lastFmScrobbleMinSecs.first() * 1000L
                val minFraction = prefs.lastFmScrobblePercent.first()
                val listened = listenedMs()
                val position = player.currentPosition
                val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
                val waitForTime = minMs - listened
                val waitForPosition = ((minFraction * duration - position) / speed).toLong()
                val wait = maxOf(waitForTime, waitForPosition)
                if (wait <= 0) {
                    submit(s, listened, duration)
                    return@launch
                }
                delay(wait + 250)
                if (!player.isPlaying) return@launch
            }
        }
    }

    private fun submit(s: Song, listenedMs: Long, durationMs: Long) {
        if (submitted) return
        submitted = true
        lastFm.scrobble(s, startedAt)
        maloja.scrobble(s, startedAt)
        scope.launch { musicRepository.recordListen(s.id, listenedMs, durationMs) }
    }
}
