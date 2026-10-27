package io.leancast.domain.ssdp

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec: ssdp-discovery — Requirement: Parse M-SEARCH requests. */
class SsdpMessageParserTest {

    private val sender = InetAddress.getByName("192.168.31.10")

    @Test
    fun `valid M-SEARCH parsed with ST MX and sender`() {
        val wire = ("M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "MX: 3\r\n" +
            "\r\n").toByteArray()

        val msg = SsdpMessageParser.parse(wire, sender)

        assertTrue(msg is SsdpInbound.MSearch)
        msg as SsdpInbound.MSearch
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", msg.st)
        assertEquals(3, msg.mxSeconds)
        assertEquals(sender, msg.from)
    }

    @Test
    fun `header keys are case insensitive`() {
        val wire = ("M-SEARCH * HTTP/1.1\r\n" +
            "host: 239.255.255.250:1900\r\n" +
            "man: \"ssdp:discover\"\r\n" +
            "st: ssdp:all\r\n" +
            "mx: 2\r\n" +
            "\r\n").toByteArray()

        val msg = SsdpMessageParser.parse(wire, sender)

        assertTrue(msg is SsdpInbound.MSearch)
        msg as SsdpInbound.MSearch
        assertEquals("ssdp:all", msg.st)
        assertEquals(2, msg.mxSeconds)
    }

    @Test
    fun `garbage datagram is ignored without exception`() {
        val msg = SsdpMessageParser.parse("not-http-at-all".toByteArray(), sender)
        assertTrue(msg is SsdpInbound.Ignored)
    }

    @Test
    fun `missing ST is ignored`() {
        val wire = ("M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "\r\n").toByteArray()

        assertTrue(SsdpMessageParser.parse(wire, sender) is SsdpInbound.Ignored)
    }

    @Test
    fun `missing MAN is ignored`() {
        val wire = ("M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "ST: ssdp:all\r\n" +
            "\r\n").toByteArray()

        assertTrue(SsdpMessageParser.parse(wire, sender) is SsdpInbound.Ignored)
    }

    @Test
    fun `inbound NOTIFY is ignored`() {
        val wire = ("NOTIFY * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "NT: upnp:rootdevice\r\n" +
            "NTS: ssdp:alive\r\n" +
            "\r\n").toByteArray()

        assertTrue(SsdpMessageParser.parse(wire, sender) is SsdpInbound.Ignored)
    }

    @Test
    fun `unparseable MX yields null`() {
        val wire = ("M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "ST: ssdp:all\r\n" +
            "MX: banana\r\n" +
            "\r\n").toByteArray()

        val msg = SsdpMessageParser.parse(wire, sender)
        assertTrue(msg is SsdpInbound.MSearch)
        assertEquals(null, (msg as SsdpInbound.MSearch).mxSeconds)
    }
}
