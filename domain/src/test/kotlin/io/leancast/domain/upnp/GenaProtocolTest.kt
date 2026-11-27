package io.leancast.domain.upnp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for GENA timeout parsing, callback validation, and SEQ arithmetic. */
class GenaProtocolTest {

    @Test
    fun `parseTimeout handles mixed case Second prefix and infinite`() {
        assertEquals(300, GenaProtocol.parseTimeout("Second-300"))
        assertEquals(300, GenaProtocol.parseTimeout("second-300"))
        assertEquals(300, GenaProtocol.parseTimeout("SECOND-300"))
        assertEquals(600, GenaProtocol.parseTimeout("sEcOnD-600"))
        assertEquals(GenaProtocol.DEFAULT_TIMEOUT, GenaProtocol.parseTimeout("Second-infinite"))
        assertEquals(GenaProtocol.DEFAULT_TIMEOUT, GenaProtocol.parseTimeout("SECOND-INFINITE"))
        assertEquals(60, GenaProtocol.parseTimeout("Second-5"))
        assertEquals(86_400, GenaProtocol.parseTimeout("Second-999999"))
        assertEquals(GenaProtocol.DEFAULT_TIMEOUT, GenaProtocol.parseTimeout(null))
    }

    @Test
    fun `parseCallbacks accepts valid http LAN URLs and rejects loopback or non-http schemes`() {
        val raw = listOf(
            "<http://192.168.1.20:8080/event>",
            "<https://192.168.1.20:8443/event>",
            "<http://127.0.0.1:49152/ctl/avt>",
            "<http://localhost:8080/cb>",
            "<http://0.0.0.0:8080/cb>",
            "<file:///etc/passwd>",
            "<http://192.168.1.21:9000/notify?id=1>",
        ).joinToString("")

        assertEquals(
            listOf(
                "http://192.168.1.20:8080/event",
                "http://192.168.1.21:9000/notify?id=1",
            ),
            GenaProtocol.parseCallbacks(raw),
        )
    }

    @Test
    fun `parseCallbacks rejects control characters and caps list length`() {
        assertFalse(GenaProtocol.isAllowedCallbackUrl("http://192.168.1.20:8080/a\r\nX-Inject: 1"))
        val many = (1..10).joinToString("") { "<http://192.168.1.$it:8080/ev>" }
        val parsed = GenaProtocol.parseCallbacks(many)
        assertEquals(GenaProtocol.MAX_CALLBACKS_PER_SUB, parsed.size)
        assertTrue(parsed.first().startsWith("http://192.168.1.1:"))
    }

    @Test
    fun `nextSeq increments unsigned 32-bit counter and wraps to 1 skipping 0`() {
        assertEquals(1L, GenaProtocol.nextSeq(0L))
        assertEquals(2L, GenaProtocol.nextSeq(1L))
        assertEquals(0x8000_0000L, GenaProtocol.nextSeq(Int.MAX_VALUE.toLong()))
        assertEquals(1L, GenaProtocol.nextSeq(0xFFFF_FFFFL))
    }
}
