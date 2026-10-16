package dev.yuwixx.resonance.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SubsonicResponseTest {
    @Test
    fun `ok returns the body for a successful response`() {
        val body = SubsonicResponseBody(status = "ok", version = "1.16.1")
        assertEquals(body, SubsonicResponse(body).ok)
    }

    @Test
    fun `a failed response throws instead of looking empty`() {
        val failed = SubsonicResponse(
            SubsonicResponseBody(status = "failed", version = "1.16.1", error = SubsonicError(40, "Wrong username or password")),
        )
        val e = assertThrows(SubsonicException::class.java) { failed.ok }
        assertEquals(40, e.code)
        assertEquals("Wrong username or password", e.message)
    }
}
