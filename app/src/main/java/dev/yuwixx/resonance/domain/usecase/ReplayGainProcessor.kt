package dev.yuwixx.resonance.domain.usecase

import android.util.Log
import dev.yuwixx.resonance.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // Lazily-populated cache of tag-read ReplayGain values, keyed by song id. Unbounded is fine
    // here — entries are just two floats, not the large arrays WaveformExtractor's bounded cache
    // protects against.
    private val tagCache = java.util.concurrent.ConcurrentHashMap<Long, Pair<Float?, Float?>>()

    fun peekCachedGain(songId: Long): Pair<Float?, Float?>? = tagCache[songId]

    // Reads REPLAYGAIN_TRACK_GAIN/REPLAYGAIN_ALBUM_GAIN tags via jaudiotagger (the same library
    // already used for tag editing in MusicRepository.updateSongTags) and caches the result.
    // Deliberately not run during bulk library sync — opening every file to read tags would slow
    // down scans of large libraries — so this is called lazily, in the background, right before
    // a song is queued for playback.
    suspend fun readAndCacheGain(songId: Long, path: String): Pair<Float?, Float?>? =
        withContext(Dispatchers.IO) {
            tagCache[songId]?.let { return@withContext it }
            try {
                val file = java.io.File(path)
                if (!file.exists()) return@withContext null
                val tag = org.jaudiotagger.audio.AudioFileIO.read(file).tag ?: return@withContext null
                // Generic string-keyed lookup works uniformly across ID3 TXXX and Vorbis comment
                // tags, and sidesteps any jaudiotagger FieldKey enum version mismatch.
                val trackGain = parseGainString(tag.getFirst("REPLAYGAIN_TRACK_GAIN"))
                val albumGain = parseGainString(tag.getFirst("REPLAYGAIN_ALBUM_GAIN"))
                val result = trackGain to albumGain
                if (trackGain != null || albumGain != null) tagCache[songId] = result
                result
            } catch (e: Exception) {
                Log.w("ReplayGainProcessor", "Failed to read RG tags for $path: ${e.message}")
                null
            }
        }

    private fun parseGainString(raw: String?): Float? =
        raw?.trim()?.removeSuffix("dB")?.removeSuffix("DB")?.trim()?.toFloatOrNull()

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
