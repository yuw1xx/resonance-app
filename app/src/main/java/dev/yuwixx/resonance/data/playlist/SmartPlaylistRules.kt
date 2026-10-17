package dev.yuwixx.resonance.data.playlist

import com.squareup.moshi.JsonClass
import dev.yuwixx.resonance.data.model.Song
import kotlin.math.floor
import kotlin.random.Random

/**
 * Smart playlist rules, stored as JSON and evaluated in memory so results follow changes to the
 * library, play counts and likes.
 */
@JsonClass(generateAdapter = true)
data class SmartRules(
    /** true = every rule must match, false = any rule. */
    val matchAll: Boolean = true,
    val rules: List<SmartRule> = emptyList(),
    val sort: SmartSort = SmartSort.TITLE,
    /** Maximum number of songs, or null for no limit. */
    val limit: Int? = null,
)

@JsonClass(generateAdapter = true)
data class SmartRule(
    val field: SmartField,
    val op: SmartOp,
    val value: String,
)

enum class SmartFieldKind { TEXT, NUMBER, BOOLEAN }

enum class SmartField(val label: String, val kind: SmartFieldKind) {
    TITLE("Title", SmartFieldKind.TEXT),
    ARTIST("Artist", SmartFieldKind.TEXT),
    ALBUM("Album", SmartFieldKind.TEXT),
    GENRE("Genre", SmartFieldKind.TEXT),
    FORMAT("Format", SmartFieldKind.TEXT),
    YEAR("Year", SmartFieldKind.NUMBER),
    PLAY_COUNT("Play count", SmartFieldKind.NUMBER),
    ADDED_DAYS_AGO("Added (days ago)", SmartFieldKind.NUMBER),
    PLAYED_DAYS_AGO("Last played (days ago)", SmartFieldKind.NUMBER),
    DURATION_MIN("Length (minutes)", SmartFieldKind.NUMBER),
    LIKED("Liked", SmartFieldKind.BOOLEAN),
}

enum class SmartOp(val label: String) {
    CONTAINS("contains"),
    NOT_CONTAINS("doesn't contain"),
    IS("is"),
    IS_NOT("is not"),
    GREATER("more than"),
    LESS("less than");

    companion object {
        fun forKind(kind: SmartFieldKind): List<SmartOp> = when (kind) {
            SmartFieldKind.TEXT -> listOf(CONTAINS, NOT_CONTAINS, IS, IS_NOT)
            SmartFieldKind.NUMBER -> listOf(IS, IS_NOT, GREATER, LESS)
            SmartFieldKind.BOOLEAN -> listOf(IS)
        }
    }
}

enum class SmartSort(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    YEAR("Year"),
    RECENTLY_ADDED("Recently added"),
    MOST_PLAYED("Most played"),
    RECENTLY_PLAYED("Recently played"),
    RANDOM("Random"),
}

object SmartPlaylistMatcher {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Songs matching [rules], sorted and limited. [seed] keeps RANDOM order stable between
     * re-evaluations (otherwise every library change would reshuffle the playlist).
     */
    fun apply(
        rules: SmartRules,
        songs: List<Song>,
        likedIds: Set<Long>,
        now: Long = System.currentTimeMillis(),
        seed: Long = 0L,
    ): List<Song> {
        val matching = songs.filter { song ->
            if (rules.rules.isEmpty()) return@filter true
            val results = rules.rules.asSequence().map { matches(it, song, likedIds, now) }
            if (rules.matchAll) results.all { it } else results.any { it }
        }
        val sorted = when (rules.sort) {
            SmartSort.TITLE -> matching.sortedBy { it.title.lowercase() }
            SmartSort.ARTIST -> matching.sortedWith(compareBy({ it.displayArtist.lowercase() }, { it.album.lowercase() }, { it.trackNumber }))
            SmartSort.ALBUM -> matching.sortedWith(compareBy({ it.album.lowercase() }, { it.discNumber }, { it.trackNumber }))
            SmartSort.YEAR -> matching.sortedByDescending { it.year }
            SmartSort.RECENTLY_ADDED -> matching.sortedByDescending { it.dateAdded }
            SmartSort.MOST_PLAYED -> matching.sortedByDescending { it.listenCount }
            SmartSort.RECENTLY_PLAYED -> matching.filter { it.lastListened > 0 }.sortedByDescending { it.lastListened } +
                matching.filter { it.lastListened <= 0 }
            SmartSort.RANDOM -> matching.shuffled(Random(seed))
        }
        return rules.limit?.takeIf { it > 0 }?.let { sorted.take(it) } ?: sorted
    }

    fun matches(rule: SmartRule, song: Song, likedIds: Set<Long>, now: Long): Boolean = when (rule.field.kind) {
        SmartFieldKind.TEXT -> {
            val actual = when (rule.field) {
                SmartField.TITLE -> song.title
                SmartField.ARTIST -> song.displayArtist
                SmartField.ALBUM -> song.album
                SmartField.GENRE -> song.genre
                SmartField.FORMAT -> song.mimeType.substringAfter('/') + " " + song.path.substringAfterLast('.', "")
                else -> ""
            }
            val expected = rule.value.trim()
            when (rule.op) {
                SmartOp.CONTAINS -> actual.contains(expected, ignoreCase = true)
                SmartOp.NOT_CONTAINS -> !actual.contains(expected, ignoreCase = true)
                SmartOp.IS -> actual.equals(expected, ignoreCase = true) ||
                    (rule.field == SmartField.FORMAT && actual.split(' ').any { it.equals(expected, ignoreCase = true) })
                SmartOp.IS_NOT -> !actual.equals(expected, ignoreCase = true)
                else -> false
            }
        }
        SmartFieldKind.NUMBER -> {
            val expected = rule.value.trim().toDoubleOrNull()
            val actual: Double? = when (rule.field) {
                SmartField.YEAR -> song.year.takeIf { it > 0 }?.toDouble()
                SmartField.PLAY_COUNT -> song.listenCount.toDouble()
                SmartField.ADDED_DAYS_AGO -> song.dateAdded.takeIf { it > 0 }?.let { (now - it).toDouble() / DAY_MS }
                SmartField.PLAYED_DAYS_AGO -> song.lastListened.takeIf { it > 0 }?.let { (now - it).toDouble() / DAY_MS }
                SmartField.DURATION_MIN -> song.duration / 60_000.0
                else -> null
            }
            if (expected == null || actual == null) false
            else when (rule.op) {
                // Whole units for "is": a song added 3.4 days ago "is 3 days ago".
                SmartOp.IS -> floor(actual) == floor(expected)
                SmartOp.IS_NOT -> floor(actual) != floor(expected)
                SmartOp.GREATER -> actual > expected
                SmartOp.LESS -> actual < expected
                else -> false
            }
        }
        SmartFieldKind.BOOLEAN -> {
            val want = rule.value.trim().equals("true", ignoreCase = true)
            (song.id in likedIds) == want
        }
    }
}
