package dev.yuwixx.resonance.data.playlist

import java.text.Normalizer
import kotlin.math.abs

/** The library-side fields matching needs; kept separate from the domain Song so the matcher
 *  is testable without Android's Uri. */
data class MatchCandidate(
    val id: Long,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

/**
 * Matches playlist entries to library songs. Paths from another device rarely exist here, so
 * matching falls back to progressively looser strategies.
 */
class SongMatcher(candidates: List<MatchCandidate>) {

    enum class Method { EXACT_PATH, STORAGE_PATH, PATH_SUFFIX, FILE_NAME, TAGS }

    data class Match(val candidate: MatchCandidate, val method: Method)

    private val byPath = HashMap<String, MatchCandidate>(candidates.size * 2)
    private val byPathLower = HashMap<String, MatchCandidate>(candidates.size * 2)
    private val byStoragePath = HashMap<String, MatchCandidate>(candidates.size * 2)
    private val byFileName = HashMap<String, MutableList<MatchCandidate>>(candidates.size * 2)
    private val byTags = HashMap<String, MutableList<MatchCandidate>>(candidates.size * 2)
    private val byTitle = HashMap<String, MutableList<MatchCandidate>>(candidates.size * 2)

    init {
        candidates.forEach { c ->
            val path = c.path.replace('\\', '/')
            byPath.putIfAbsent(path, c)
            byPathLower.putIfAbsent(path.lowercase(), c)
            byStoragePath.putIfAbsent(storageRelative(path).lowercase(), c)
            byFileName.getOrPut(path.substringAfterLast('/').lowercase()) { mutableListOf() } += c
            byTags.getOrPut(tagKey(c.artist, c.title)) { mutableListOf() } += c
            byTitle.getOrPut(normalise(c.title)) { mutableListOf() } += c
        }
    }

    /** [baseDir] is the absolute directory of the playlist file when known, used to resolve
     *  relative entries ("../Music/song.flac"). */
    fun match(entry: M3uEntry, baseDir: String? = null): Match? {
        if (entry.isRemote) return null
        val location = resolve(entry.location, baseDir)

        byPath[location]?.let { return Match(it, Method.EXACT_PATH) }
        byPathLower[location.lowercase()]?.let { return Match(it, Method.EXACT_PATH) }
        byStoragePath[storageRelative(location).lowercase()]?.let { return Match(it, Method.STORAGE_PATH) }

        val segments = location.lowercase().split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        val fileName = segments.lastOrNull()
        if (fileName != null) {
            val sameName = byFileName[fileName].orEmpty()
            if (sameName.isNotEmpty()) {
                // Prefer the candidate sharing the most trailing folders ("Artist/Album/01.flac"
                // from D:\Music\… matches /storage/…/Music/Artist/Album/01.flac).
                val scored = sameName.map { it to trailingSegmentsInCommon(segments, it.path) }
                val best = scored.maxOf { it.second }
                val top = scored.filter { it.second == best }
                if (best >= 2 && top.size == 1) return Match(top[0].first, Method.PATH_SUFFIX)
                if (sameName.size == 1) return Match(sameName[0], Method.FILE_NAME)
                pickByTags(top.map { it.first }, entry)?.let { return Match(it, Method.PATH_SUFFIX) }
            }
        }

        val artist = entry.artist ?: fileName?.let { stemArtistTitle(it)?.first }
        val title = entry.title ?: fileName?.let { stemArtistTitle(it)?.second }
        if (title != null) {
            if (artist != null) {
                pickByDuration(byTags[tagKey(artist, title)].orEmpty(), entry.durationSec)
                    ?.let { return Match(it, Method.TAGS) }
            }
            // Title alone only when it's unambiguous (or the duration settles it).
            val sameTitle = byTitle[normalise(title)].orEmpty()
            val byArtist = if (artist != null) {
                val a = normalise(artist)
                sameTitle.filter { normalise(it.artist).contains(a) || a.contains(normalise(it.artist)) }
            } else sameTitle
            if (byArtist.size == 1) return Match(byArtist[0], Method.TAGS)
            pickByDuration(byArtist, entry.durationSec, requireDuration = true)
                ?.let { return Match(it, Method.TAGS) }
        }
        return null
    }

    private fun pickByTags(candidates: List<MatchCandidate>, entry: M3uEntry): MatchCandidate? {
        val title = entry.title ?: return null
        val matching = candidates.filter { normalise(it.title) == normalise(title) }
        return matching.singleOrNull()
    }

    private fun pickByDuration(
        candidates: List<MatchCandidate>,
        durationSec: Int?,
        requireDuration: Boolean = false,
    ): MatchCandidate? {
        if (candidates.isEmpty()) return null
        if (durationSec == null) return if (requireDuration) null else candidates.first()
        val close = candidates.filter { abs(it.durationMs / 1000 - durationSec) <= DURATION_TOLERANCE_SEC }
        return close.minByOrNull { abs(it.durationMs / 1000 - durationSec) }
            ?: if (requireDuration) null else candidates.first()
    }

    companion object {
        private const val DURATION_TOLERANCE_SEC = 3

        private val STORAGE_ROOT = Regex(
            "^(?:/storage/emulated/\\d+|/storage/self/primary|/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}|" +
                "/sdcard|/mnt/sdcard|/mnt/media_rw/[^/]+|/data/media/\\d+)(?=/)"
        )
        private val WINDOWS_DRIVE = Regex("^[A-Za-z]:/")
        private val LEADING_TRACK_NO = Regex("^\\d{1,3}[ ._-]+")
        private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
        private val DIACRITICS = Regex("\\p{Mn}+")

        /** Strips the storage-volume prefix, so the same file reached via /sdcard,
         *  /storage/emulated/0 or a different SD card id compares equal. */
        fun storageRelative(path: String): String = STORAGE_ROOT.replace(path, "")

        internal fun resolve(location: String, baseDir: String?): String {
            val isAbsolute = location.startsWith("/") || WINDOWS_DRIVE.containsMatchIn(location)
            val joined = if (!isAbsolute && baseDir != null) "${baseDir.trimEnd('/')}/$location" else location
            if (!joined.startsWith("/")) return joined
            val out = ArrayDeque<String>()
            joined.split('/').forEach { seg ->
                when (seg) {
                    "", "." -> Unit
                    ".." -> out.removeLastOrNull()
                    else -> out.addLast(seg)
                }
            }
            return "/" + out.joinToString("/")
        }

        internal fun trailingSegmentsInCommon(entrySegments: List<String>, candidatePath: String): Int {
            val cand = candidatePath.lowercase().split('/').filter { it.isNotEmpty() }
            var n = 0
            while (n < entrySegments.size && n < cand.size &&
                entrySegments[entrySegments.size - 1 - n] == cand[cand.size - 1 - n]
            ) n++
            return n
        }

        // "03 - Artist - Title.flac" / "Artist - Title.mp3" → (Artist, Title)
        internal fun stemArtistTitle(fileName: String): Pair<String, String>? {
            val stem = fileName.substringBeforeLast('.').replace(LEADING_TRACK_NO, "")
            val parts = stem.split(" - ")
            return if (parts.size >= 2) parts[0].trim() to parts.drop(1).joinToString(" - ").trim() else null
        }

        fun normalise(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
                .replace(DIACRITICS, "")
                .replace(NON_ALNUM, " ")
                .trim()

        private fun tagKey(artist: String, title: String) = normalise(artist) + "\u0000" + normalise(title)
    }
}
