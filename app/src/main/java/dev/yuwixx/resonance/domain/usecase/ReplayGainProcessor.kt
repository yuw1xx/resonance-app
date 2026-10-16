package dev.yuwixx.resonance.domain.usecase

import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ReplayGainProcessor @Inject constructor() {

    enum class Mode { TRACK, ALBUM, OFF }

    data class ReplayGainInfo(
        val trackGainDb: Float?,
        val trackPeak: Float?,
        val albumGainDb: Float?,
        val albumPeak: Float?,
    )

    // ReplayGain values by song ID. Unbounded, since each entry is two floats.
    private val tagCache = ConcurrentHashMap<Long, Pair<Float?, Float?>>()

    fun peekCachedGain(songId: Long): Pair<Float?, Float?>? = tagCache[songId]

    // Reads the ReplayGain tags of one file. Called when a song is queued rather than during the
    // library scan, which would have to open every file.
    suspend fun readAndCacheGain(songId: Long, path: String): Pair<Float?, Float?>? =
        withContext(Dispatchers.IO) {
            tagCache[songId]?.let { return@withContext it }
            try {
                val file = File(path)
                if (!file.exists()) return@withContext null
                val tag = AudioFileIO.read(file).tag ?: return@withContext null
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
