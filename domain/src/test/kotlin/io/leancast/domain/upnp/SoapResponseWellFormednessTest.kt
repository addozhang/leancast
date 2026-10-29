package io.leancast.domain.upnp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Spec: upnp-renderer — Requirement: Every XML response is well-formed.
 *
 * Regression guard: SOAP bodies used to be emitted with indentation before the
 * XML declaration and with an undeclared `s:` prefix, so strict parsers rejected
 * *every* state response. Senders then never left their "connecting" state even
 * though the media was playing.
 */
class SoapResponseWellFormednessTest {

    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))

    private val responses: Map<String, String> = mapOf(
        "empty" to SoapResponses.empty("Play", UpnpService.AVT),
        "fault" to SoapResponses.fault(401, "Invalid Action"),
        "getPositionInfo" to SoapResponses.getPositionInfo(1, 5_000, 60_000, null, "http://h/v.mp4"),
        "getTransportInfo" to SoapResponses.getTransportInfo("PLAYING"),
        "getMediaInfo" to SoapResponses.getMediaInfo(1, 60_000, "http://h/v.mp4"),
        "getVolume" to SoapResponses.getVolume(42),
        "getMute" to SoapResponses.getMute(false),
        "getProtocolInfo" to SoapResponses.getProtocolInfo(),
        "getTransportSettings" to SoapResponses.getTransportSettings(),
        "getDeviceCapabilities" to SoapResponses.getDeviceCapabilities(),
        "getCurrentTransportActions" to SoapResponses.getCurrentTransportActions("Pause,Stop"),
        "getCurrentConnectionIDs" to SoapResponses.getCurrentConnectionIDs(),
        "getCurrentConnectionInfo" to SoapResponses.getCurrentConnectionInfo("http-get:*:video/mp4:*"),
        "listPresets" to SoapResponses.listPresets(),
        "getVolumeDbRange" to SoapResponses.getVolumeDbRange(),
    )

    @Test
    fun `no response begins with whitespace before the xml declaration`() {
        responses.forEach { (name, body) ->
            assertTrue("$name starts with '${body.take(12)}'", body.startsWith("<?xml"))
        }
    }

    @Test
    fun `every response parses as namespace-aware xml`() {
        responses.forEach { (name, body) ->
            try {
                parse(body)
            } catch (e: Exception) {
                throw AssertionError("$name is not valid XML: ${e.message}", e)
            }
        }
    }

    @Test
    fun `transport info carries the state senders poll for`() {
        val body = SoapResponses.getTransportInfo("PLAYING")
        val doc = parse(body)
        val state = doc.getElementsByTagName("CurrentTransportState").item(0).textContent
        assertEquals("PLAYING", state)
    }

    @Test
    fun `position info exposes time and duration as h m s`() {
        val doc = parse(SoapResponses.getPositionInfo(1, 65_000, 3_600_000, null, "http://h/v.mp4"))
        assertEquals("0:01:05", doc.getElementsByTagName("RelTime").item(0).textContent)
        assertEquals("1:00:00", doc.getElementsByTagName("TrackDuration").item(0).textContent)
    }
}
