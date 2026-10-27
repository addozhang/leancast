package io.leancast.domain.ssdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec: ssdp-discovery — Requirements: Advertise aliveness on startup,
 * Announce departure on shutdown.
 */
class SsdpNotifyTest {

    private val identity = SsdpIdentity(
        uuid = "uuid:2fac1234-31f8-11b4-a222-08002b34c003",
        location = "http://192.168.31.47:49152/desc.xml",
        serverToken = "Android/9 UPnP/1.0 TestHarness/1",
        maxAgeSeconds = 1800,
    )
    private val responder = SsdpResponder(identity, clockMs = { 0L })

    @Test
    fun `alive set covers every target with ssdp-alive NTS`() {
        val messages = responder.aliveMessages()
        assertEquals(5, messages.size)
        val text = messages.joinToString("\n---\n") { it.decodeToString() }
        assertTrue(text.contains("NT: upnp:rootdevice"))
        assertTrue(text.contains("NT: urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertTrue(text.contains("NT: urn:schemas-upnp-org:service:AVTransport:1"))
        assertTrue(text.contains("NT: urn:schemas-upnp-org:service:RenderingControl:1"))
        assertTrue(text.contains("NT: urn:schemas-upnp-org:service:ConnectionManager:1"))
        assertEquals(5, Regex("NTS: ssdp:alive").findAll(text).count())
        assertEquals(5, Regex("LOCATION: http://192\\.168\\.31\\.47:49152/desc\\.xml").findAll(text).count())
        assertEquals(5, Regex("HOST: 239\\.255\\.255\\.250:1900").findAll(text).count())
        assertTrue(text.contains("USN: uuid:2fac1234-31f8-11b4-a222-08002b34c003::upnp:rootdevice"))
    }

    @Test
    fun `byebye set mirrors alive set with ssdp-byebye NTS`() {
        val messages = responder.byebyeMessages()
        assertEquals(5, messages.size)
        val text = messages.joinToString("\n---\n") { it.decodeToString() }
        assertEquals(5, Regex("NTS: ssdp:byebye").findAll(text).count())
        assertTrue(text.contains("NT: upnp:rootdevice"))
        assertTrue(text.contains("NT: urn:schemas-upnp-org:service:ConnectionManager:1"))
        assertEquals(5, Regex("HOST: 239\\.255\\.255\\.250:1900").findAll(text).count())
    }
}
