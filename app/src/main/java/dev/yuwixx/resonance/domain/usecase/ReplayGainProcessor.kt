package dev.yuwixx.resonance.domain.usecase

import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.yuwixx.resonance.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

@Singleton
class ReplayGainProcessor @Inject constructor() {

    enum class Mode { TRACK, ALBUM, OFF }

    data class ReplayGainInfo(
        val trackGainDb: Float?,
        val trackPeak: Float?,
        val albumGainDb: Float?,
        val albumPeak: Float?,
    )

        fun parseGain(song: Song): ReplayGainInfo {
        if (song.replayGainTrack != null || song.replayGainAlbum != null) {
            return ReplayGainInfo(
                trackGainDb = song.replayGainTrack,
                trackPeak = null,
                albumGainDb = song.replayGainAlbum,
                albumPeak = null,
            )
        }

        return ReplayGainInfo(null, null, null, null)
    }

        fun computeMultiplier(
        info: ReplayGainInfo,
        mode: Mode,
        preampDb: Float = 0f,
    ): Float {
        if (mode == Mode.OFF) return 1f

        val gainDb = when (mode) {
            Mode.TRACK -> info.trackGainDb
            Mode.ALBUM -> info.albumGainDb ?: info.trackGainDb
            Mode.OFF -> null
        } ?: return 1f

        val totalDb = gainDb + preampDb
        val linear = 10f.pow(totalDb / 20f)

        val peak = when (mode) {
            Mode.TRACK -> info.trackPeak ?: 1f
            Mode.ALBUM -> info.albumPeak ?: info.trackPeak ?: 1f
            Mode.OFF -> 1f
        }
        val maxSafe = 1f / peak.coerceAtLeast(0.001f)

        return linear.coerceIn(0.01f, maxSafe.coerceAtMost(4f))
    }
}
