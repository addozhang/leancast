package io.leancast.domain.ssdp

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec: ssdp-discovery — Requirements: Respond to M-SEARCH for supported targets,
 * Discovery identity is configurable.
 */
class SsdpResponderTest {

    private val identity = SsdpIdentity(
        uuid = "uuid:2fac1234-31f8-11b4-a222-08002b34c003",
        location = "http://192.168.31.47:49152/desc.xml",
        serverToken = "Android/9 UPnP/1.0 TestHarness/1",
        maxAgeSeconds = 1800,
    )
    private val sender = InetAddress.getByName("192.168.31.10")
    private val responder = SsdpResponder(identity, clockMs = { 0L })

    private fun search(st: String): SsdpInbound.MSearch =
        SsdpMessageParser.parse(
            ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\nST: $st\r\nMX: 1\r\n\r\n").toByteArray(),
            sender,
        ) as SsdpInbound.MSearch

    private fun List<ByteArray>.asText() = joinToString("\n---\n") { it.decodeToString() }

    @Test
    fun `M-SEARCH for MediaRenderer device type yields exactly one response`() {
        val responses = responder.responsesFor(search("urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertEquals(1, responses.size)
        val text = responses.single().decodeToString()
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"))
        assertTrue(
            text.contains(
                "USN: uuid:2fac1234-31f8-11b4-a222-08002b34c003::urn:schemas-upnp-org:device:MediaRenderer:1\r\n",
            ),
        )
        assertTrue(text.contains("LOCATION: http://192.168.31.47:49152/desc.xml\r\n"))
        assertTrue(text.contains("CACHE-CONTROL: max-age=1800\r\n"))
        assertTrue(text.contains("SERVER: Android/9 UPnP/1.0 TestHarness/1\r\n"))
        assertTrue(text.contains("EXT:\r\n"))
        assertTrue(text.contains("DATE: "))
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test
    fun `M-SEARCH ssdp-all yields responses for root device, device type and services`() {
        val responses = responder.responsesFor(search("ssdp:all"))
        assertEquals(5, responses.size)
        val text = responses.asText()
        assertTrue(text.contains("ST: upnp:rootdevice"))
        assertTrue(text.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertTrue(text.contains("ST: urn:schemas-upnp-org:service:AVTransport:1"))
        assertTrue(text.contains("ST: urn:schemas-upnp-org:service:RenderingControl:1"))
        assertTrue(text.contains("ST: urn:schemas-upnp-org:service:ConnectionManager:1"))
    }

    @Test
    fun `M-SEARCH for root device yields one root response`() {
        val responses = responder.responsesFor(search("upnp:rootdevice"))
        assertEquals(1, responses.size)
        val text = responses.single().decodeToString()
        assertTrue(text.contains("ST: upnp:rootdevice\r\n"))
        assertTrue(text.contains("USN: uuid:2fac1234-31f8-11b4-a222-08002b34c003::upnp:rootdevice\r\n"))
    }

    @Test
    fun `M-SEARCH for a service yields the service USN`() {
        val responses =
            responder.responsesFor(search("urn:schemas-upnp-org:service:AVTransport:1"))
        assertEquals(1, responses.size)
        val text = responses.single().decodeToString()
        assertTrue(text.contains("ST: urn:schemas-upnp-org:service:AVTransport:1\r\n"))
        assertTrue(
            text.contains(
                "USN: uuid:2fac1234-31f8-11b4-a222-08002b34c003::urn:schemas-upnp-org:service:AVTransport:1\r\n",
            ),
        )
    }

    @Test
    fun `unsupported search target yields no response`() {
        val responses = responder.responsesFor(search("urn:schemas-upnp-org:device:MediaServer:1"))
        assertTrue(responses.isEmpty())
    }

    @Test
    fun `identity values are injected verbatim`() {
        val custom = SsdpIdentity(
            uuid = "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            location = "http://10.0.0.9:12345/desc.xml",
            serverToken = "CustomStack/9",
        )
        val customResponder = SsdpResponder(custom, clockMs = { 0L })
        val text = customResponder
            .responsesFor(search("urn:schemas-upnp-org:device:MediaRenderer:1"))
            .single().decodeToString()
        assertTrue(text.contains("uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        assertTrue(text.contains("http://10.0.0.9:12345/desc.xml"))
        assertTrue(text.contains("SERVER: CustomStack/9\r\n"))
    }
}
