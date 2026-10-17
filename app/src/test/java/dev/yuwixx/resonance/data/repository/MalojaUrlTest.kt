package dev.yuwixx.resonance.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MalojaUrlTest {
    @Test
    fun `bare host and port get http`() {
        assertEquals("http://192.168.1.5:42010/", MalojaRepository.normalizeUrl(" 192.168.1.5:42010 "))
    }

    @Test
    fun `full urls are kept with one trailing slash`() {
        assertEquals("https://maloja.example.com/", MalojaRepository.normalizeUrl("https://maloja.example.com//"))
    }

    @Test
    fun `unusable addresses are rejected`() {
        assertNull(MalojaRepository.normalizeUrl(""))
        assertNull(MalojaRepository.normalizeUrl("http://"))
        assertNull(MalojaRepository.normalizeUrl("not a url at all"))
    }
}
