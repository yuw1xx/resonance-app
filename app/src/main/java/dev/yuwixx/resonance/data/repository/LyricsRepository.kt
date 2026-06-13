package dev.yuwixx.resonance.data.repository

import android.net.Uri
import dev.yuwixx.resonance.data.database.dao.LyricsDao
import dev.yuwixx.resonance.data.database.entity.LyricsEntity
import dev.yuwixx.resonance.data.model.LyricLine
import dev.yuwixx.resonance.data.model.WordTiming
import dev.yuwixx.resonance.data.network.LrclibApi
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.domain.usecase.TagEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LyricsRepository @Inject constructor(
    private val lrclibApi: LrclibApi,
    private val lyricsDao: LyricsDao,
    private val prefs: ResonancePreferences,
    private val tagEditor: TagEditor,
) {
        suspend fun getLyrics(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        songUri: Uri? = null,
    ): LyricsResult = withContext(Dispatchers.IO) {
        // Embedded lyrics take priority over cache — re-read on every call so tag
        // edits are reflected immediately without clearing the lyrics DB.
        if (songUri != null) {
            val embedded = tagEditor.readEmbeddedLyrics(songUri)
            if (embedded != null) {
                val isSynced = embedded.contains(Regex("""^\[\d{2}:\d{2}""", RegexOption.MULTILINE))
                val entity = LyricsEntity(
                    songId = songId,
                    synced = if (isSynced) embedded else null,
                    plain  = if (!isSynced) embedded else null,
                    source = "embedded",
                )
                lyricsDao.upsertLyrics(entity)
                return@withContext if (isSynced)
                    LyricsResult.Synced(parseLrc(embedded))
                else
                    LyricsResult.Plain(embedded.lines())
            }
        }

        val cached = lyricsDao.getLyrics(songId)
        if (cached != null) {
            return@withContext parseCached(cached)
        }

        if (!prefs.fetchLyrics.first()) return@withContext LyricsResult.NotFound

        try {
            val response = lrclibApi.getLyrics(
                trackName = title,
                artistName = artist,
                albumName = album.ifEmpty { null },
                durationSeconds = (durationMs / 1000).toInt().takeIf { it > 0 },
            )

            if (response != null) {
                val entity = LyricsEntity(
                    songId = songId,
                    synced = response.syncedLyrics,
                    plain = response.plainLyrics,
                    source = "lrclib",
                )
                lyricsDao.upsertLyrics(entity)

                if (!response.syncedLyrics.isNullOrBlank()) {
                    LyricsResult.Synced(parseLrc(response.syncedLyrics))
                } else if (!response.plainLyrics.isNullOrBlank()) {
                    LyricsResult.Plain(response.plainLyrics.lines())
                } else {
                    LyricsResult.NotFound
                }
            } else {
                LyricsResult.NotFound
            }
        } catch (e: Exception) {
            LyricsResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun parseCached(entity: LyricsEntity): LyricsResult {
        return when {
            !entity.synced.isNullOrBlank() -> LyricsResult.Synced(parseLrc(entity.synced))
            !entity.plain.isNullOrBlank() -> LyricsResult.Plain(entity.plain.lines())
            else -> LyricsResult.NotFound
        }
    }

        fun parseLrc(lrc: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        val timePattern = Regex("""^\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)$""")
        val wordPattern = Regex("""<(\d{2}):(\d{2})\.(\d{2,3})>([^<]*)""")

        for (raw in lrc.lines()) {
            val match = timePattern.matchEntire(raw.trim()) ?: continue
            val (min, sec, centis, rest) = match.destructured
            val timeMs = min.toLong() * 60_000 +
                         sec.toLong() * 1_000 +
                         centis.toLong() * if (centis.length == 2) 10L else 1L

            val wordMatches = wordPattern.findAll(rest).toList()
            val wordTimings = if (wordMatches.isNotEmpty()) {
                wordMatches.mapIndexed { i, wm ->
                    val (wMin, wSec, wCs, word) = wm.destructured
                    val startMs = wMin.toLong() * 60_000 +
                                  wSec.toLong() * 1_000 +
                                  wCs.toLong() * if (wCs.length == 2) 10L else 1L
                    val endMs = wordMatches.getOrNull(i + 1)?.let { next ->
                        val (nm, ns, nc, _) = next.destructured
                        nm.toLong() * 60_000 + ns.toLong() * 1_000 +
                                nc.toLong() * if (nc.length == 2) 10L else 1L
                    } ?: (startMs + 500L)
                    WordTiming(startMs = startMs, endMs = endMs, word = word.trim())
                }
            } else emptyList()

            val text = if (wordTimings.isEmpty()) {
                rest.trim()
            } else {
                wordTimings.joinToString(" ") { it.word }
            }

            if (text.isNotBlank() || wordTimings.isNotEmpty()) {
                lines.add(LyricLine(timeMs = timeMs, text = text, wordTimings = wordTimings))
            }
        }

        return lines.sortedBy { it.timeMs }
    }

    suspend fun getRawLyrics(songId: Long): String? = withContext(Dispatchers.IO) {
        val entity = lyricsDao.getLyrics(songId) ?: return@withContext null
        entity.synced ?: entity.plain
    }

        suspend fun saveManualLyrics(songId: Long, lrcText: String) {
        val lrcTimestampRegex = Regex("""^\[\d{2}:\d{2}\.\d{2}""", RegexOption.MULTILINE)
        val isSynced = lrcTimestampRegex.containsMatchIn(lrcText)
        val entity = LyricsEntity(
            songId = songId,
            synced = if (isSynced) lrcText else null,
            plain  = if (!isSynced) lrcText else null,
            source = "manual",
        )
        lyricsDao.upsertLyrics(entity)
    }
}

sealed class LyricsResult {
    data object Loading : LyricsResult()
    data class Synced(val lines: List<LyricLine>) : LyricsResult()
    data class Plain(val lines: List<String>) : LyricsResult()
    data object NotFound : LyricsResult()
    data class Error(val message: String) : LyricsResult()
}
