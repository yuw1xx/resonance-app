package dev.yuwixx.resonance.cast

import android.content.Context
import android.util.Log
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.common.images.WebImage
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CastManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()

    private val _castIsPlaying = MutableStateFlow(false)
    val castIsPlaying: StateFlow<Boolean> = _castIsPlaying.asStateFlow()

    private var _castContext: CastContext? = null

    val remoteMediaClient: RemoteMediaClient?
        get() = _castContext?.sessionManager?.currentCastSession?.remoteMediaClient

    private val clientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val client = remoteMediaClient ?: return
            _castIsPlaying.value = client.isPlaying
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            _isCasting.value = true
            session.remoteMediaClient?.registerCallback(clientCallback)
        }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            _isCasting.value = true
            session.remoteMediaClient?.registerCallback(clientCallback)
        }
        override fun onSessionEnded(session: CastSession, error: Int) {
            session.remoteMediaClient?.unregisterCallback(clientCallback)
            _isCasting.value = false
            _castIsPlaying.value = false
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            session.remoteMediaClient?.unregisterCallback(clientCallback)
            _isCasting.value = false
        }
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
    }

    // Must be called from the main thread (MainActivity.onCreate).
    // Safe to call on every Activity recreation — listener is only registered once.
    fun initialize() {
        if (_castContext != null) return
        try {
            _castContext = CastContext.getSharedInstance(context)
            val sessionManager = _castContext?.sessionManager ?: return
            sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
            // Sync state if already in a session (e.g., app restarted while casting)
            val currentSession = sessionManager.currentCastSession
            if (currentSession != null) {
                _isCasting.value = true
                currentSession.remoteMediaClient?.registerCallback(clientCallback)
            }
        } catch (e: Exception) {
            Log.w("CastManager", "Cast not available: ${e.message}")
        }
    }

    fun castSong(audioUrl: String, song: Song, positionMs: Long = 0L, artworkUrl: String? = null) {
        val client = remoteMediaClient ?: return
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, song.title)
            putString(MediaMetadata.KEY_ARTIST, song.displayArtist)
            putString(MediaMetadata.KEY_ALBUM_TITLE, song.album)
            if (artworkUrl != null) {
                addImage(WebImage(android.net.Uri.parse(artworkUrl), 1000, 1000))
            }
        }
        val mediaInfo = MediaInfo.Builder(audioUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(song.mimeType.ifBlank { "audio/mpeg" })
            .setMetadata(metadata)
            .setStreamDuration(song.duration)
            .build()
        client.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setCurrentTime(positionMs)
                .setAutoplay(true)
                .build()
        )
    }

    fun playPause() {
        val client = remoteMediaClient ?: return
        if (client.isPlaying) client.pause(null) else client.play()
    }

    fun seekTo(positionMs: Long) {
        remoteMediaClient?.seek(
            MediaSeekOptions.Builder()
                .setPosition(positionMs)
                .build()
        )
    }

    fun getCurrentPosition(): Long =
        remoteMediaClient?.approximateStreamPosition ?: 0L

    fun getStreamDuration(): Long =
        remoteMediaClient?.mediaInfo?.streamDuration ?: -1L
}
