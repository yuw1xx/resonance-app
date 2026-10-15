package dev.yuwixx.resonance.data.repository

import android.net.Uri
import dev.yuwixx.resonance.data.database.dao.LyricsDao
import dev.yuwixx.resonance.data.database.entity.LyricsEntity
import dev.yuwixx.resonance.data.model.LyricLine
import dev.yuwixx.resonance.data.network.LrclibApi
import dev.yuwixx.resonance.data.network.LrclibResponse
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.domain.usecase.LrcParser
import dev.yuwixx.resonance.domain.usecase.TagEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import retrofit2.HttpException
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
        forceRefresh: Boolean = false,
    ): LyricsResult = withContext(Dispatchers.IO) {
        // Cache first — this runs on every song change (and is prefetched for the next queued
        // song too), so a repeat play must be a single fast DB read, not a jaudiotagger file
        // parse on every call. This does mean lyrics embedded into a file by an external tool
        // after it's already been cached won't be picked up automatically; reloadLyrics()
        // (wired to the lyrics editor's Save action) passes forceRefresh to bypass this when
        // that matters.
        if (!forceRefresh) {
            val cached = lyricsDao.getLyrics(songId)
            if (cached != null) {
                return@withContext parseCached(cached)
            }
        }

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
        } catch (e: HttpException) {
            // LRCLIB returns a plain 404 for "no match", which is the common, expected case
            // for most tracks — that's not a failure worth surfacing as an error with a retry.
            if (e.code() == 404) LyricsResult.NotFound else LyricsResult.Error("HTTP ${e.code()}")
        } catch (e: Exception) {
            LyricsResult.Error(e.message ?: "Unknown error")
        }
    }

    // Lets the user pick a different match when the auto-fetched lyrics are missing or out of
    // sync — LRCLIB's search is fuzzy, so this can surface a better-timed submission for the
    // same track that the exact-match /api/get lookup in getLyrics() didn't find.
    suspend fun searchLyrics(trackName: String, artistName: String): List<LrclibResponse> =
        withContext(Dispatchers.IO) {
            try {
                lrclibApi.searchLyrics(trackName = trackName, artistName = artistName)
            } catch (e: Exception) {
                emptyList()
            }
        }

    suspend fun selectLyrics(songId: Long, response: LrclibResponse) {
        val entity = LyricsEntity(
            songId = songId,
            synced = response.syncedLyrics,
            plain = response.plainLyrics,
            source = "lrclib",
        )
        lyricsDao.upsertLyrics(entity)
    }

    private fun parseCached(entity: LyricsEntity): LyricsResult {
        return when {
            !entity.synced.isNullOrBlank() -> LyricsResult.Synced(parseLrc(entity.synced))
            !entity.plain.isNullOrBlank() -> LyricsResult.Plain(entity.plain.lines())
            else -> LyricsResult.NotFound
        }
    }

    fun parseLrc(lrc: String): List<LyricLine> = LrcParser.parse(lrc)

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
