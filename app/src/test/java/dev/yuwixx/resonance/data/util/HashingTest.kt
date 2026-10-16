package dev.yuwixx.resonance.data.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class HashingTest {
    @Test
    fun `md5 matches the known digest`() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", md5Hex("hello"))
    }

    @Test
    fun `hex stays ASCII in locales with other digits`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("5d41402abc4b2a76b9719d911017c592", md5Hex("hello"))
            assertEquals("00ff10", byteArrayOf(0, -1, 16).toHex())
        } finally {
            Locale.setDefault(previous)
        }
    }
}
