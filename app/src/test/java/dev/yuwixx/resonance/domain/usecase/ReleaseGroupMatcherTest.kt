package dev.yuwixx.resonance.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGroupMatcherTest {

    @Test
    fun `exact artist and album match is an exact match`() {
        val result = ReleaseGroupMatcher.isExactMatch(
            artistCreditNames = listOf("Daft Punk"),
            releaseGroupTitle = "Discovery",
            songArtist = "Daft Punk",
            songAlbum = "Discovery",
        )
        assertTrue(result)
    }

    @Test
    fun `case is ignored on both artist and album`() {
        val result = ReleaseGroupMatcher.isExactMatch(
            artistCreditNames = listOf("daft punk"),
            releaseGroupTitle = "DISCOVERY",
            songArtist = "Daft Punk",
            songAlbum = "Discovery",
        )
        assertTrue(result)
    }

    @Test
    fun `artist matches any credited artist, not just the first`() {
        val result = ReleaseGroupMatcher.isExactMatch(
            artistCreditNames = listOf("Some Other Artist", "Daft Punk"),
            releaseGroupTitle = "Discovery",
            songArtist = "Daft Punk",
            songAlbum = "Discovery",
        )
        assertTrue(result)
    }

    @Test
    fun `mismatched album title is not an exact match`() {
        val result = ReleaseGroupMatcher.isExactMatch(
            artistCreditNames = listOf("Daft Punk"),
            releaseGroupTitle = "Random Access Memories",
            songArtist = "Daft Punk",
            songAlbum = "Discovery",
        )
        assertFalse(result)
    }

    @Test
    fun `mismatched artist is not an exact match`() {
        val result = ReleaseGroupMatcher.isExactMatch(
            artistCreditNames = listOf("Justice"),
            releaseGroupTitle = "Discovery",
            songArtist = "Daft Punk",
            songAlbum = "Discovery",
        )
        assertFalse(result)
    }

    @Test
    fun `no credited artists is never an exact match`() {
        val result = ReleaseGroupMatcher.isExactMatch(
            artistCreditNames = emptyList(),
            releaseGroupTitle = "Discovery",
            songArtist = "Daft Punk",
            songAlbum = "Discovery",
        )
        assertFalse(result)
    }
}
