package dev.yuwixx.resonance.data.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.yuwixx.resonance.data.database.dao.NavidromeSongDao
import dev.yuwixx.resonance.data.database.dao.SongDao
import dev.yuwixx.resonance.data.model.MusicSource
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.data.repository.LastFmRepository
import dev.yuwixx.resonance.data.repository.MalojaRepository
import dev.yuwixx.resonance.data.repository.MusicRepository
import dev.yuwixx.resonance.data.repository.NavidromeRepository
import dev.yuwixx.resonance.data.repository.toDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scrobbling (Last.fm, Maloja, Navidrome), "now playing" and listening history for local
 * playback. It lives in MusicService so it keeps working with the UI closed.
 *
 * Instead of polling the position, it schedules one wake-up for when the scrobble threshold
 * (minimum listened time and minimum share of the track) can first be met, and re-plans on
 * play, pause and seek. History is written when a track ends, with the time actually listened.
 */
@Singleton
class ScrobbleTracker @Inject constructor(
    private val prefs: ResonancePreferences,
    private val songDao: SongDao,
    private val navidromeSongDao: NavidromeSongDao,
    private val lastFm: LastFmRepository,
    private val maloja: MalojaRepository,
    private val navidrome: NavidromeRepository,
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
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                finishTrack()
                startTrack(mediaItem)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // The queue ran out: the last track is finished but no transition follows.
                if (playbackState == Player.STATE_ENDED) {
                    finishTrack()
                    song = null
                }
            }

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

    /** Records the finished track in the listening history, with the time actually listened. */
    fun finishTrack() {
        val s = song ?: return
        val listened = listenedMs()
        if (listened <= 0) return
        val duration = s.duration
        scope.launch { musicRepository.recordListen(s.id, listened, duration) }
        accumulatedMs = 0L
        segmentStart = 0L
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

    // Local and Navidrome IDs can overlap on servers with numeric IDs, so the active source
    // decides which table is checked first.
    private suspend fun resolve(id: Long): Song? {
        val navidromeFirst = prefs.musicSource.first() == MusicSource.NAVIDROME
        val local = suspend { songDao.getSongById(id)?.toDomain(prefs.artistDelimiter.first()) }
        val remote = suspend { navidromeSongDao.getSongByNumericId(id)?.toDomain() }
        return if (navidromeFirst) remote() ?: local() else local() ?: remote()
    }

    private fun sendNowPlayingIfNeeded() {
        val s = song ?: return
        if (nowPlayingSent) return
        nowPlayingSent = true
        lastFm.updateNowPlaying(s)
        s.navidromeId?.let { id -> scope.launch { navidrome.notifyNowPlaying(id) } }
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
                    submit(s)
                    return@launch
                }
                delay(wait + 250)
                if (!player.isPlaying) return@launch
            }
        }
    }

    private fun submit(s: Song) {
        if (submitted) return
        submitted = true
        lastFm.scrobble(s, startedAt)
        maloja.scrobble(s, startedAt)
        s.navidromeId?.let { id -> scope.launch { navidrome.submitScrobble(id, startedAt) } }
    }
}
