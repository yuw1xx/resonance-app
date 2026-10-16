package dev.yuwixx.resonance.data.playlist

import android.net.Uri
import dev.yuwixx.resonance.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmartPlaylistMatcherTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun song(
        id: Long,
        title: String = "Song $id",
        artist: String = "Artist",
        genre: String = "",
        year: Int = 2000,
        plays: Int = 0,
        addedDaysAgo: Long = 1,
        lastPlayedDaysAgo: Long? = null,
        durationMin: Long = 3,
    ) = Song(
        id = id, uri = Uri.EMPTY, title = title, artist = artist, artists = listOf(artist),
        albumArtist = artist, album = "Album", albumId = 1, genre = genre,
        duration = durationMin * 60_000, size = 0, bitrate = 0, sampleRate = 0,
        trackNumber = id.toInt(), discNumber = 1, year = year,
        dateAdded = now - addedDaysAgo * day, dateModified = 0, path = "/music/$id.flac", folder = "/music",
        mimeType = "audio/flac", replayGainTrack = null, replayGainAlbum = null, artworkUri = null,
        listenCount = plays, lastListened = lastPlayedDaysAgo?.let { now - it * day } ?: 0L,
    )

    @Test
    fun `match all requires every rule`() {
        val songs = listOf(song(1, genre = "Rock", plays = 10), song(2, genre = "Rock", plays = 1), song(3, genre = "Jazz", plays = 10))
        val rules = SmartRules(
            matchAll = true,
            rules = listOf(SmartRule(SmartField.GENRE, SmartOp.IS, "rock"), SmartRule(SmartField.PLAY_COUNT, SmartOp.GREATER, "5")),
        )
        assertEquals(listOf(1L), SmartPlaylistMatcher.apply(rules, songs, emptySet(), now).map { it.id })
    }

    @Test
    fun `match any accepts either rule`() {
        val songs = listOf(song(1, genre = "Rock"), song(2, genre = "Jazz"), song(3, genre = "Pop"))
        val rules = SmartRules(
            matchAll = false,
            rules = listOf(SmartRule(SmartField.GENRE, SmartOp.IS, "Rock"), SmartRule(SmartField.GENRE, SmartOp.CONTAINS, "jaz")),
        )
        assertEquals(setOf(1L, 2L), SmartPlaylistMatcher.apply(rules, songs, emptySet(), now).map { it.id }.toSet())
    }

    @Test
    fun `liked, recency and length rules`() {
        val songs = listOf(
            song(1, addedDaysAgo = 3, lastPlayedDaysAgo = 40, durationMin = 8),
            song(2, addedDaysAgo = 30, lastPlayedDaysAgo = 2, durationMin = 3),
        )
        fun ids(rule: SmartRule, liked: Set<Long> = emptySet()) =
            SmartPlaylistMatcher.apply(SmartRules(rules = listOf(rule)), songs, liked, now).map { it.id }

        assertEquals(listOf(2L), ids(SmartRule(SmartField.LIKED, SmartOp.IS, "true"), liked = setOf(2L)))
        assertEquals(listOf(1L), ids(SmartRule(SmartField.ADDED_DAYS_AGO, SmartOp.LESS, "7")))
        assertEquals(listOf(1L), ids(SmartRule(SmartField.PLAYED_DAYS_AGO, SmartOp.GREATER, "30")))
        assertEquals(listOf(1L), ids(SmartRule(SmartField.DURATION_MIN, SmartOp.GREATER, "5")))
        assertEquals(listOf(1L, 2L), ids(SmartRule(SmartField.FORMAT, SmartOp.IS, "flac")))
    }

    @Test
    fun `sort and limit are applied after matching`() {
        val songs = (1L..5L).map { song(it, plays = it.toInt()) }
        val rules = SmartRules(sort = SmartSort.MOST_PLAYED, limit = 2)
        assertEquals(listOf(5L, 4L), SmartPlaylistMatcher.apply(rules, songs, emptySet(), now).map { it.id })
    }

    @Test
    fun `random order is stable for the same seed`() {
        val songs = (1L..20L).map { song(it) }
        val rules = SmartRules(sort = SmartSort.RANDOM)
        val a = SmartPlaylistMatcher.apply(rules, songs, emptySet(), now, seed = 7).map { it.id }
        val b = SmartPlaylistMatcher.apply(rules, songs, emptySet(), now, seed = 7).map { it.id }
        assertEquals(a, b)
    }
}
