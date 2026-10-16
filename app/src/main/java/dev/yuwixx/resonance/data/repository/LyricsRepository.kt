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
    private val navidromeApiProvider: dev.yuwixx.resonance.data.network.NavidromeApiProvider,
) {
    /**
     * Lyrics for a song, from the first source that has them:
     *  1. a manual edit (always wins, never overwritten),
     *  2. the cache (including cached misses, retried after [MISS_TTL_MS]),
     *  3. tags embedded in the file (MediaStore items and downloaded copies),
     *  4. a `.lrc` file next to the audio file,
     *  5. the Navidrome server (OpenSubsonic structured lyrics, then classic getLyrics),
     *  6. LRCLIB, if online lookup is enabled: exact match, then a fuzzy-scored search.
     * [forceRefresh] skips the cache (2) but never a manual edit (1).
     */
    suspend fun getLyrics(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        songUri: Uri? = null,
        forceRefresh: Boolean = false,
        songPath: String? = null,
        navidromeId: String? = null,
    ): LyricsResult = withContext(Dispatchers.IO) {
        val cached = lyricsDao.getLyrics(songId)
        if (cached != null) {
            val fresh = when (cached.source) {
                SOURCE_MANUAL -> true
                SOURCE_NONE -> System.currentTimeMillis() - cached.fetchedAt < MISS_TTL_MS
                else -> !forceRefresh
            }
            if (fresh) return@withContext parseCached(cached)
        }

        // Local sources first: no network, and they reflect the user's own files.
        localLyrics(songUri, songPath)?.let { (text, source) -> return@withContext store(songId, text, source) }
        navidromeId?.let { id -> navidromeLyrics(id, artist, title) }
            ?.let { return@withContext store(songId, it, SOURCE_NAVIDROME) }

        if (!prefs.fetchLyrics.first()) return@withContext rememberMiss(songId)

        try {
            val response = lrclibExact(title, artist, album, durationMs) ?: lrclibSearch(title, artist, durationMs)
            when {
                response == null -> rememberMiss(songId)
                response.instrumental == true &&
                    response.syncedLyrics.isNullOrBlank() && response.plainLyrics.isNullOrBlank() -> {
                    lyricsDao.upsertLyrics(LyricsEntity(songId, null, null, SOURCE_INSTRUMENTAL))
                    LyricsResult.Instrumental
                }
                !response.syncedLyrics.isNullOrBlank() -> store(songId, response.syncedLyrics, SOURCE_LRCLIB)
                !response.plainLyrics.isNullOrBlank() -> store(songId, response.plainLyrics, SOURCE_LRCLIB)
                else -> rememberMiss(songId)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Network trouble isn't a "no lyrics" answer: don't cache it, offer a retry.
            LyricsResult.Error(e.message ?: "Unknown error")
        }
    }

    private suspend fun localLyrics(songUri: Uri?, songPath: String?): Pair<String, String>? {
        if (songUri != null && songUri.scheme != "http" && songUri.scheme != "https") {
            tagEditor.readEmbeddedLyrics(songUri)?.let { return it to SOURCE_EMBEDDED }
        }
        val path = songPath?.takeIf { it.startsWith("/") } ?: songUri?.takeIf { it.scheme == "file" }?.path
        if (path != null) {
            val sidecar = java.io.File(path.substringBeforeLast('.') + ".lrc")
            if (sidecar.canRead()) {
                runCatching { sidecar.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { return it to SOURCE_SIDECAR }
            }
        }
        return null
    }

    private suspend fun navidromeLyrics(id: String, artist: String, title: String): String? {
        val api = navidromeApiProvider.currentApi() ?: return null
        runCatching { api.getLyricsBySongId(id).response.lyricsList?.structuredLyrics }.getOrNull()
            ?.let { list ->
                val best = list.firstOrNull { it.synced && it.line.isNotEmpty() } ?: list.firstOrNull { it.line.isNotEmpty() }
                if (best != null) {
                    return if (best.synced) {
                        val offset = best.offset ?: 0L
                        best.line.joinToString("\n") { line ->
                            val t = ((line.start ?: 0L) + offset).coerceAtLeast(0L)
                            "[%02d:%02d.%02d]%s".format(t / 60_000, (t / 1000) % 60, (t / 10) % 100, line.value)
                        }
                    } else best.line.joinToString("\n") { it.value }
                }
            }
        return runCatching { api.getLyrics(artist, title).response.lyrics?.value }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private suspend fun lrclibExact(title: String, artist: String, album: String, durationMs: Long): LrclibResponse? =
        try {
            lrclibApi.getLyrics(
                trackName = title,
                artistName = artist,
                albumName = album.ifEmpty { null },
                durationSeconds = (durationMs / 1000).toInt().takeIf { it > 0 },
            )
        } catch (e: HttpException) {
            // 404 = no exact match: the common case, handled by the search fallback.
            if (e.code() == 404) null else throw e
        }

    /** LRCLIB's search is fuzzy; pick the candidate that best matches title/artist/duration. */
    private suspend fun lrclibSearch(title: String, artist: String, durationMs: Long): LrclibResponse? {
        val cleanTitle = normalize(title)
        val primaryArtist = normalize(artist.split(Regex("""\s*(,|&|;|/| feat\.? | ft\.? | x )\s*""", RegexOption.IGNORE_CASE)).first())
        val candidates = try {
            lrclibApi.searchLyrics(trackName = cleanTitle, artistName = primaryArtist)
        } catch (e: HttpException) {
            if (e.code() == 404) emptyList() else throw e
        }
        val durationSec = durationMs / 1000.0
        return candidates
            .filter { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() || it.instrumental == true }
            .mapNotNull { c ->
                val titleOk = normalize(c.trackName) == cleanTitle
                val artistOk = normalize(c.artistName).let { it.contains(primaryArtist) || primaryArtist.contains(it) }
                if (!titleOk || !artistOk) return@mapNotNull null
                val drift = c.duration?.let { kotlin.math.abs(it - durationSec) } ?: 0.0
                if (durationMs > 0 && drift > 5.0) return@mapNotNull null
                // Prefer synced lyrics, then the closest duration.
                val score = (if (!c.syncedLyrics.isNullOrBlank()) 100.0 else 0.0) - drift
                c to score
            }
            .maxByOrNull { it.second }?.first
    }

    private suspend fun store(songId: Long, text: String, source: String): LyricsResult {
        val synced = LrcParser.isSynced(text)
        lyricsDao.upsertLyrics(
            LyricsEntity(songId = songId, synced = text.takeIf { synced }, plain = text.takeIf { !synced }, source = source)
        )
        return if (synced) LyricsResult.Synced(parseLrc(text)) else LyricsResult.Plain(text.lines())
    }

    private suspend fun rememberMiss(songId: Long): LyricsResult {
        lyricsDao.upsertLyrics(LyricsEntity(songId = songId, synced = null, plain = null, source = SOURCE_NONE))
        return LyricsResult.NotFound
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

    /** Applies a search result the user picked; treated like a manual edit so it sticks. */
    suspend fun selectLyrics(songId: Long, response: LrclibResponse): LyricsResult {
        val text = response.syncedLyrics?.takeIf { it.isNotBlank() } ?: response.plainLyrics ?: return LyricsResult.NotFound
        return store(songId, text, SOURCE_MANUAL)
    }

    private fun parseCached(entity: LyricsEntity): LyricsResult {
        if (entity.source == SOURCE_INSTRUMENTAL) return LyricsResult.Instrumental
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
        store(songId, lrcText, SOURCE_MANUAL)
    }

    private companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_EMBEDDED = "embedded"
        const val SOURCE_SIDECAR = "lrc_file"
        const val SOURCE_NAVIDROME = "navidrome"
        const val SOURCE_LRCLIB = "lrclib"
        const val SOURCE_INSTRUMENTAL = "instrumental"
        /** A remembered "nothing found", so songs without lyrics don't hit every source on every play. */
        const val SOURCE_NONE = "none"
        const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000

        private val bracketed = Regex("""\s*[(\[][^)\]]*[)\]]""")
        private val dashSuffix = Regex("""\s+-\s+.*(remaster|version|edit|live|mix|mono|stereo).*$""", RegexOption.IGNORE_CASE)
        private val featuring = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)

        /** Lowercased title/artist without "(Remastered 2011)", " - Live", "feat. X" and punctuation noise. */
        fun normalize(s: String): String = s
            .replace(bracketed, "")
            .replace(dashSuffix, "")
            .replace(featuring, "")
            .lowercase()
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
    }
}

sealed class LyricsResult {
    data object Loading : LyricsResult()
    data class Synced(val lines: List<LyricLine>) : LyricsResult()
    data class Plain(val lines: List<String>) : LyricsResult()
    data object NotFound : LyricsResult()
    /** The track is known to have no vocals. */
    data object Instrumental : LyricsResult()
    data class Error(val message: String) : LyricsResult()
}
