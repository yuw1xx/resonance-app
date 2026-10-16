package dev.yuwixx.resonance.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    @Test
    fun `parses extended m3u with name, durations and artist-title`() {
        val text = """
            #EXTM3U
            #PLAYLIST:Road Trip
            #EXTINF:215,Daft Punk - One More Time
            /storage/emulated/0/Music/Daft Punk/Discovery/01 One More Time.flac
            #EXTINF:-1,Untitled stream
            https://radio.example/stream
        """.trimIndent()

        val playlist = M3uParser.parse(text)

        assertEquals("Road Trip", playlist.name)
        assertEquals(2, playlist.entries.size)
        val first = playlist.entries[0]
        assertEquals(215, first.durationSec)
        assertEquals("Daft Punk", first.artist)
        assertEquals("One More Time", first.title)
        assertEquals("/storage/emulated/0/Music/Daft Punk/Discovery/01 One More Time.flac", first.location)
        val second = playlist.entries[1]
        assertNull("negative duration means unknown", second.durationSec)
        assertNull(second.artist)
        assertEquals("Untitled stream", second.title)
        assertTrue(second.isRemote)
    }

    @Test
    fun `plain m3u without EXTINF and with comments and blank lines`() {
        val playlist = M3uParser.parse("# just a comment\n\nsong1.mp3\r\n  song2.mp3  \n")
        assertNull(playlist.name)
        assertEquals(listOf("song1.mp3", "song2.mp3"), playlist.entries.map { it.location })
        assertNull(playlist.entries[0].title)
    }

    @Test
    fun `windows paths are normalised to forward slashes`() {
        val playlist = M3uParser.parse("D:\\Music\\Artist\\Album\\01 - Song.mp3")
        assertEquals("D:/Music/Artist/Album/01 - Song.mp3", playlist.entries.single().location)
    }

    @Test
    fun `file URLs are percent-decoded`() {
        val playlist = M3uParser.parse(
            "file:///storage/emulated/0/Music/Sigur%20R%C3%B3s/Hopp%C3%ADpolla.flac\n" +
                "file:///C:/Users/me/Music/a%2Bb.mp3"
        )
        assertEquals("/storage/emulated/0/Music/Sigur Rós/Hoppípolla.flac", playlist.entries[0].location)
        assertEquals("C:/Users/me/Music/a+b.mp3", playlist.entries[1].location)
    }

    @Test
    fun `EXTINF attributes with quoted commas do not split the display title`() {
        val playlist = M3uParser.parse(
            "#EXTINF:180 tvg-name=\"a, b\" group-title=\"x\",Artist - Title, Part 2\nsong.mp3"
        )
        val entry = playlist.entries.single()
        assertEquals(180, entry.durationSec)
        assertEquals("Artist", entry.artist)
        assertEquals("Title, Part 2", entry.title)
    }

    @Test
    fun `utf8 BOM is stripped and latin1 files decode via windows-1252`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "#EXTM3U\nsong.mp3".toByteArray()
        assertEquals("#EXTM3U\nsong.mp3", M3uParser.decode(bom))

        // "Café.mp3" in Windows-1252: é = 0xE9, which is invalid as standalone UTF-8.
        val latin1 = "Caf".toByteArray() + byteArrayOf(0xE9.toByte()) + ".mp3".toByteArray()
        assertEquals("Café.mp3", M3uParser.decode(latin1))
    }

    @Test
    fun `writer produces extended m3u and relativizes paths under the target folder`() {
        val tracks = listOf(
            M3uWriter.Track("/storage/emulated/0/Music/A/Album/01.flac", 125_900, "A", "One"),
            M3uWriter.Track("/storage/emulated/0/Download/other.mp3", 60_000, "B", "Two"),
        )
        val text = M3uWriter.write("Mix", tracks, relativeToDir = "/storage/emulated/0/Music/Playlists")
        val lines = text.trimEnd().lines()
        assertEquals("#EXTM3U", lines[0])
        assertEquals("#PLAYLIST:Mix", lines[1])
        assertEquals("#EXTINF:125,A - One", lines[2])
        assertEquals("../A/Album/01.flac", lines[3])
        assertEquals("../../Download/other.mp3", lines[5])

        // Round-trip: what we write parses back to the same metadata.
        val parsed = M3uParser.parse(text)
        assertEquals("Mix", parsed.name)
        assertEquals("One", parsed.entries[0].title)
    }
}
