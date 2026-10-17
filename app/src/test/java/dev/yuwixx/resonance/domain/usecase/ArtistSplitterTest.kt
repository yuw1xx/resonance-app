package dev.yuwixx.resonance.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistSplitterTest {

    private val defaultDelimiter = ",;/&"

    @Test
    fun `single artist with no delimiter is returned as-is`() {
        assertEquals(listOf("Daft Punk"), ArtistSplitter.split("Daft Punk", defaultDelimiter))
    }

    @Test
    fun `slash-separated collaborators are split into two artists`() {
        assertEquals(
            listOf("ArtistA", "ArtistB"),
            ArtistSplitter.split("ArtistA/ArtistB", defaultDelimiter),
        )
    }

    @Test
    fun `comma and ampersand delimiters both split`() {
        assertEquals(
            listOf("A", "B", "C"),
            ArtistSplitter.split("A, B & C", defaultDelimiter),
        )
    }

    @Test
    fun `whitespace around each split artist is trimmed`() {
        assertEquals(
            listOf("ArtistA", "ArtistB"),
            ArtistSplitter.split("ArtistA / ArtistB", defaultDelimiter),
        )
    }

    @Test
    fun `delimiter not present in the configured set does not split`() {
        // "-" isn't in the default delimiter set, so this is treated as one artist name.
        assertEquals(
            listOf("Artist - With Dash"),
            ArtistSplitter.split("Artist - With Dash", defaultDelimiter),
        )
    }

    @Test
    fun `empty segments from consecutive delimiters are dropped`() {
        assertEquals(
            listOf("A", "B"),
            ArtistSplitter.split("A,,B", defaultDelimiter),
        )
    }

    @Test
    fun `blank artist string falls back to itself rather than an empty list`() {
        assertEquals(listOf(""), ArtistSplitter.split("", defaultDelimiter))
    }

    @Test
    fun `custom single-character delimiter is respected`() {
        assertEquals(
            listOf("ArtistA", "ArtistB"),
            ArtistSplitter.split("ArtistA|ArtistB", "|"),
        )
    }
}
