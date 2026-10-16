package dev.yuwixx.resonance.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses standard timestamped lines in order`() {
        val lrc = """
            [00:12.34]First line
            [00:01.00]Second line
        """.trimIndent()

        val lines = LrcParser.parse(lrc)

        assertEquals(2, lines.size)
        // Output must be sorted by time even though the input wasn't.
        assertEquals(1_000L, lines[0].timeMs)
        assertEquals("Second line", lines[0].text)
        assertEquals(12_340L, lines[1].timeMs)
        assertEquals("First line", lines[1].text)
    }

    @Test
    fun `two-digit centiseconds are scaled to milliseconds`() {
        // [mm:ss.xx] (2-digit) means hundredths of a second (x10 for ms);
        // [mm:ss.xxx] (3-digit) is already milliseconds.
        val lines = LrcParser.parse("[00:00.50]half a second\n[00:01.500]one and a half")

        assertEquals(2, lines.size)
        assertEquals(500L, lines[0].timeMs)
        assertEquals(1_500L, lines[1].timeMs)
    }

    @Test
    fun `lines without a leading timestamp are ignored`() {
        val lrc = """
            [ti:Some Title]
            [ar:Some Artist]
            [00:05.00]Only this line counts
        """.trimIndent()

        val lines = LrcParser.parse(lrc)

        assertEquals(1, lines.size)
        assertEquals("Only this line counts", lines[0].text)
    }

    @Test
    fun `blank lyric lines are dropped`() {
        val lrc = "[00:01.00]\n[00:02.00]Real line"

        val lines = LrcParser.parse(lrc)

        assertEquals(1, lines.size)
        assertEquals("Real line", lines[0].text)
    }

    @Test
    fun `word-level karaoke timing is parsed and joined back into text`() {
        val lrc = "[00:10.00]<00:10.00>Hello <00:10.50>world"

        val lines = LrcParser.parse(lrc)

        assertEquals(1, lines.size)
        val line = lines[0]
        assertEquals("Hello world", line.text)
        assertEquals(2, line.wordTimings.size)
        assertEquals(10_000L, line.wordTimings[0].startMs)
        assertEquals(10_500L, line.wordTimings[0].endMs) // ends where the next word starts
        assertEquals("Hello", line.wordTimings[0].word)
        assertEquals(10_500L, line.wordTimings[1].startMs)
        assertEquals(10_500L + 500L, line.wordTimings[1].endMs) // last word: start + 500ms fallback
        assertEquals("world", line.wordTimings[1].word)
    }

    @Test
    fun `empty input produces no lines`() {
        assertTrue(LrcParser.parse("").isEmpty())
    }

    @Test
    fun `one line with several timestamps repeats the text at each time`() {
        val lines = LrcParser.parse("[00:12.00][00:45.00]Chorus\n[00:20.00]Verse")

        assertEquals(listOf(12_000L, 20_000L, 45_000L), lines.map { it.timeMs })
        assertEquals("Chorus", lines[0].text)
        assertEquals("Chorus", lines[2].text)
    }

    @Test
    fun `short and long timestamp forms are accepted`() {
        val lines = LrcParser.parse("[1:23]a\n[01:24.5]b\n[01:25.125]c\n[100:00.00]d")

        assertEquals(listOf(83_000L, 84_500L, 85_125L, 6_000_000L), lines.map { it.timeMs })
    }

    @Test
    fun `offset tag shifts every line`() {
        // Positive offset = lyrics come earlier.
        val lines = LrcParser.parse("[offset:+500]\n[00:10.00]Line")

        assertEquals(9_500L, lines.single().timeMs)
    }

    @Test
    fun `empty timestamp between lines becomes a single instrumental gap`() {
        val lines = LrcParser.parse("[00:01.00]Sung\n[00:05.00]\n[00:06.00]\n[00:20.00]Again\n[00:30.00]")

        assertEquals(3, lines.size)
        assertTrue(lines[1].isGap)
        assertEquals(5_000L, lines[1].timeMs)
        assertEquals("Again", lines[2].text) // trailing end marker dropped
    }

    @Test
    fun `second line with the same timestamp is a translation`() {
        val lines = LrcParser.parse("[00:10.00]Hola\n[00:10.00]Hello")

        assertEquals(1, lines.size)
        assertEquals("Hola", lines[0].text)
        assertEquals("Hello", lines[0].translation)
    }

    @Test
    fun `word timings keep text before the first word tag and end at the next line`() {
        val lines = LrcParser.parse("[00:10.00]Oh <00:11.00>yeah\n[00:13.00]Next")

        val words = lines[0].wordTimings
        assertEquals("Oh yeah", lines[0].text)
        assertEquals("Oh", words[0].word)
        assertEquals(10_000L, words[0].startMs)
        assertEquals(11_000L, words[0].endMs)
        assertEquals(13_000L, words[1].endMs)
    }

    @Test
    fun `isSynced detects timestamps in any accepted form`() {
        assertTrue(LrcParser.isSynced("[ti:x]\n[1:02]hi"))
        assertTrue(!LrcParser.isSynced("just words\nno timing"))
    }
}
