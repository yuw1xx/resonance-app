package dev.yuwixx.resonance.presentation.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class StampNextLineTest {
    @Test
    fun `stamps the first unstamped line and skips tags and blanks`() {
        val text = "[ar:Someone]\n[00:01.00]Done\n\nNext line\nLater"

        assertEquals(
            "[ar:Someone]\n[00:01.00]Done\n\n[01:02.34]Next line\nLater",
            stampNextLine(text, 62_345L),
        )
    }

    @Test
    fun `leaves fully stamped text unchanged`() {
        val text = "[00:01.00]a\n[00:02.00]b"
        assertEquals(text, stampNextLine(text, 5_000L))
    }
}
