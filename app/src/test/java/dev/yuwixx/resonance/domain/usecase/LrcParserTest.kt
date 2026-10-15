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
        val lines = LrcParser.parse("[00:00.50]half a second\n[00:00.500]also half a second")

        assertEquals(2, lines.size)
        assertEquals(500L, lines[0].timeMs)
        assertEquals(500L, lines[1].timeMs)
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
}
