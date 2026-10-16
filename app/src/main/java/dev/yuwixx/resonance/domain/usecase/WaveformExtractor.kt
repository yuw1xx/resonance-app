package dev.yuwixx.resonance.domain.usecase

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.model.WaveformData
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Singleton
class WaveformExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val memCache: MutableMap<Long, WaveformData> = Collections.synchronizedMap(
        object: LinkedHashMap<Long, WaveformData>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Long, WaveformData>) = size > 100
        }
    )

    private val diskDir = File(context.cacheDir, "waveforms").also { it.mkdirs() }

    /** Synchronous, in-memory-only lookup — lets callers show already-precomputed data
     *  immediately instead of nulling the UI out while a redundant (but cheap) re-extract
     *  round-trips through a coroutine. Does not touch disk; a disk-cache hit still goes
     *  through [extract]. */
    fun peekCached(songId: Long): WaveformData? = memCache[songId]

    /** In-flight decodes, so the current/next prefetch and the transition handler never
     *  decode the same file twice in parallel. */
    private val inFlight = ConcurrentHashMap<Long, Deferred<WaveformData?>>()
    private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun extract(songId: Long, uri: Uri, resolution: Int = 200): WaveformData? {
        memCache[songId]?.let { return it }
        // Streams would be downloaded a second time just to draw bars — not worth the data
        // and battery. The seekbar falls back to its plain style for them.
        if (uri.scheme == "http" || uri.scheme == "https") return null
        val job = inFlight.getOrPut(songId) {
            decodeScope.async { decode(songId, uri, resolution) }.also { d ->
                d.invokeOnCompletion { inFlight.remove(songId, d) }
            }
        }
        return job.await()
    }

    private suspend fun decode(songId: Long, uri: Uri, resolution: Int): WaveformData? =
        withContext(Dispatchers.Default) {
            memCache[songId]?.let { return@withContext it }

            val diskFile = File(diskDir, "$songId.wf")
            if (diskFile.exists()) {
                readFromDisk(diskFile, resolution)?.also { memCache[songId] = it }
                    ?.let { return@withContext it }
            }

            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(context, uri, null)

                var audioTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) { audioTrack = i; break }
                }
                if (audioTrack == -1) return@withContext null

                extractor.selectTrack(audioTrack)
                val format = extractor.getTrackFormat(audioTrack)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null
                val totalUs = format.getLong(MediaFormat.KEY_DURATION)
                var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val amplitudes = FloatArray(resolution)
                var samplesPerBucket = ((totalUs / 1_000_000f) * sampleRate / resolution).toLong()
                    .coerceAtLeast(1L)

                var currentBucket = 0
                var sampleSum = 0.0
                var sampleCount = 0L
                var inputDone = false
                var outputDone = false
                val bufferInfo = MediaCodec.BufferInfo()

                while (!outputDone && currentBucket < resolution) {
                    ensureActive()
                    if (!inputDone) {
                        val inputIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (inputIdx >= 0) {
                            val buf = codec.getInputBuffer(inputIdx)!!
                            val sampleSize = extractor.readSampleData(buf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outputIdx = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                    if (outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // The decoder's real output can differ from the container's description
                        // (HE-AAC doubles the rate and may change the channel count).
                        val out = codec.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        samplesPerBucket = ((totalUs / 1_000_000f) * sampleRate / resolution).toLong().coerceAtLeast(1L)
                    } else if (outputIdx >= 0) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        val outBuf = codec.getOutputBuffer(outputIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.order(ByteOrder.nativeOrder())
                            val shortBuf = outBuf.asShortBuffer()
                            val frameCount = shortBuf.remaining() / channelCount
                            for (f in 0 until frameCount) {
                                var sample = 0L
                                for (c in 0 until channelCount) sample += abs(shortBuf.get().toLong())
                                sample /= channelCount
                                sampleSum += sample.toDouble()
                                sampleCount++

                                if (sampleCount >= samplesPerBucket) {
                                    if (currentBucket < resolution) {
                                        amplitudes[currentBucket] =
                                            (sampleSum / sampleCount / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
                                        currentBucket++
                                    }
                                    sampleSum = 0.0
                                    sampleCount = 0L
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputIdx, false)
                    }
                }

                codec.stop()

                val max = amplitudes.max().coerceAtLeast(0.001f)
                for (i in amplitudes.indices) amplitudes[i] = (amplitudes[i] / max).coerceIn(0f, 1f)

                val result = WaveformData(amplitudes = amplitudes, resolution = resolution)
                memCache[songId] = result
                writeToDisk(diskFile, result)
                result
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { null }
            finally {
                codec?.release()
                extractor.release()
            }
        }

    private fun readFromDisk(file: File, resolution: Int): WaveformData? = try {
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val storedRes = buf.int
        if (storedRes != resolution) null
        else WaveformData(FloatArray(storedRes) { buf.float }, storedRes)
    } catch (_: Exception) { null }

    private fun writeToDisk(file: File, data: WaveformData) = try {
        val buf = ByteBuffer.allocate(Int.SIZE_BYTES + data.amplitudes.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(data.resolution)
        data.amplitudes.forEach(buf::putFloat)
        file.writeBytes(buf.array())
    } catch (_: Exception) {}

    private companion object {
        /** Block briefly for codec buffers instead of spinning a core at 100%. */
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
