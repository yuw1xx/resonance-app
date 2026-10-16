package dev.yuwixx.resonance.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongMatcherTest {

    private val library = listOf(
        MatchCandidate(
            1,
            "/storage/emulated/0/Music/Daft Punk/Discovery/01 One More Time.flac",
            "One More Time",
            "Daft Punk",
            "Discovery",
            320_000,
        ),
        MatchCandidate(
            2,
            "/storage/emulated/0/Music/Daft Punk/Discovery/02 Aerodynamic.flac",
            "Aerodynamic",
            "Daft Punk",
            "Discovery",
            212_000,
        ),
        MatchCandidate(3, "/storage/emulated/0/Music/Other/Intro.mp3", "Intro", "Band A", "First", 60_000),
        MatchCandidate(4, "/storage/emulated/0/Music/Another/Intro.mp3", "Intro", "Band B", "Second", 90_000),
        MatchCandidate(5, "/storage/1234-ABCD/Music/Sigur Rós/Hoppípolla.flac", "Hoppípolla", "Sigur Rós", "Takk", 268_000),
        MatchCandidate(6, "/storage/emulated/0/Music/misc/track.mp3", "Midnight City", "M83, Zola Jesus", "Hurry Up", 243_000),
    )
    private val matcher = SongMatcher(library)

    private fun match(location: String, baseDir: String? = null, artist: String? = null, title: String? = null, duration: Int? = null) =
        matcher.match(M3uEntry(location, duration, artist, title), baseDir)

    @Test
    fun `exact absolute path`() {
        val m = match("/storage/emulated/0/Music/Daft Punk/Discovery/02 Aerodynamic.flac")
        assertEquals(2L, m?.candidate?.id)
        assertEquals(SongMatcher.Method.EXACT_PATH, m?.method)
    }

    @Test
    fun `same file via a different storage root`() {
        assertEquals(1L, match("/sdcard/Music/Daft Punk/Discovery/01 One More Time.flac")?.candidate?.id)
        assertEquals(5L, match("/storage/emulated/0/Music/Sigur Rós/Hoppípolla.flac")?.candidate?.id)
    }

    @Test
    fun `windows path from a PC matches by trailing folders`() {
        val m = match("D:/Music/Daft Punk/Discovery/01 One More Time.flac")
        assertEquals(1L, m?.candidate?.id)
        assertEquals(SongMatcher.Method.PATH_SUFFIX, m?.method)
    }

    @Test
    fun `ambiguous file name is resolved by parent folder`() {
        assertEquals(4L, match("E:/backup/Another/Intro.mp3")?.candidate?.id)
    }

    @Test
    fun `ambiguous file name without folder context does not guess`() {
        assertNull(match("Intro.mp3"))
    }

    @Test
    fun `relative entries resolve against the playlist folder`() {
        val m = match("../Daft Punk/Discovery/02 Aerodynamic.flac", baseDir = "/storage/emulated/0/Music/Playlists")
        assertEquals(2L, m?.candidate?.id)
        assertEquals(SongMatcher.Method.EXACT_PATH, m?.method)
    }

    @Test
    fun `falls back to EXTINF artist and title when the path is unknown`() {
        val m = match("/old/phone/x.mp3", artist = "daft punk", title = "One More Time", duration = 321)
        assertEquals(1L, m?.candidate?.id)
        assertEquals(SongMatcher.Method.TAGS, m?.method)
    }

    @Test
    fun `tag matching tolerates accents, case and punctuation`() {
        assertEquals(5L, match("/nope.flac", artist = "Sigur Ros", title = "hoppipolla")?.candidate?.id)
    }

    @Test
    fun `primary artist matches a multi-artist library entry`() {
        assertEquals(6L, match("/nope.mp3", artist = "M83", title = "Midnight City")?.candidate?.id)
    }

    @Test
    fun `file name stem gives artist and title when there is no EXTINF`() {
        assertEquals(2L, match("C:/rips/07 - Daft Punk - Aerodynamic.mp3")?.candidate?.id)
    }

    @Test
    fun `ambiguous title needs a duration to decide`() {
        assertNull(match("/nope.mp3", title = "Intro"))
        assertEquals(4L, match("/nope.mp3", title = "Intro", duration = 91)?.candidate?.id)
    }

    @Test
    fun `remote entries never match`() {
        assertNull(match("https://example.com/One More Time.flac", artist = "Daft Punk", title = "One More Time"))
    }
}
