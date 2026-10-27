package io.leancast.domain.upnp

import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec: upnp-renderer — Requirements: dispatch faults, position/transport/media info, volume, protocol info. */
class SoapResponsesTest {

    @Test
    fun `empty action response carries service namespace`() {
        val xml = SoapResponses.empty("Play", UpnpService.AVT)
        assertTrue(xml.contains("<u:PlayResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(xml.contains("</u:PlayResponse>"))
        assertTrue(xml.contains("<SOAP-ENV:Envelope"))
        assertTrue(xml.contains("</SOAP-ENV:Envelope>"))
    }

    @Test
    fun `fault renders UPnP error code and description`() {
        val xml = SoapResponses.fault(401, "Invalid Action")
        assertTrue(xml.contains("SOAP-ENV:Client"))
        assertTrue(xml.contains("<errorCode>401</errorCode>"))
        assertTrue(xml.contains("<errorDescription>Invalid Action</errorDescription>"))
        assertTrue(xml.contains("<UPnPError"))
    }

    @Test
    fun `position info renders times and metadata`() {
        val xml = SoapResponses.getPositionInfo(
            track = 1,
            relTimeMs = 5_492_000L,
            durationMs = 7_200_000L,
            trackMetaData = "<DIDL-Lite><item><dc:title>T &amp; T</dc:title></item></DIDL-Lite>",
            currentUri = "http://x/v.m3u8",
        )
        assertTrue(xml.contains("<Track>1</Track>"))
        assertTrue(xml.contains("<TrackDuration>2:00:00</TrackDuration>"))
        assertTrue(xml.contains("<RelTime>1:31:32</RelTime>"))
        assertTrue(xml.contains("<AbsTime>1:31:32</AbsTime>"))
        // metadata must be XML-escaped inside the response envelope
        assertTrue(xml.contains("&lt;DIDL-Lite&gt;"))
        assertTrue(xml.contains("<TrackURI>http://x/v.m3u8</TrackURI>"))
    }

    @Test
    fun `position info reports NOT_IMPLEMENTED metadata when absent`() {
        val xml = SoapResponses.getPositionInfo(1, 0L, 0L, null, null)
        assertTrue(xml.contains("<TrackMetaData>NOT_IMPLEMENTED</TrackMetaData>"))
        assertTrue(xml.contains("<RelTime>0:00:00</RelTime>"))
        assertTrue(xml.contains("<TrackDuration>0:00:00</TrackDuration>"))
    }

    @Test
    fun `transport info renders state`() {
        val xml = SoapResponses.getTransportInfo("PLAYING")
        assertTrue(xml.contains("<CurrentTransportState>PLAYING</CurrentTransportState>"))
        assertTrue(xml.contains("<CurrentTransportStatus>OK</CurrentTransportStatus>"))
        assertTrue(xml.contains("<CurrentSpeed>1</CurrentSpeed>"))
    }

    @Test
    fun `media info renders tracks duration and uri`() {
        val xml = SoapResponses.getMediaInfo(
            nrTracks = 1,
            durationMs = 7_200_000L,
            currentUri = "http://x/v.mp4",
        )
        assertTrue(xml.contains("<NrTracks>1</NrTracks>"))
        assertTrue(xml.contains("<MediaDuration>2:00:00</MediaDuration>"))
        assertTrue(xml.contains("<CurrentURI>http://x/v.mp4</CurrentURI>"))
    }

    @Test
    fun `volume responses render current value`() {
        val xml = SoapResponses.getVolume(37)
        assertTrue(xml.contains("<CurrentVolume>37</CurrentVolume>"))
    }

    @Test
    fun `mute responses render state`() {
        assertTrue(SoapResponses.getMute(false).contains("<CurrentMute>0</CurrentMute>"))
    }

    @Test
    fun `protocol info lists minimum sink formats`() {
        val xml = SoapResponses.getProtocolInfo()
        assertTrue(xml.contains("http-get:*:video/mp4:*"))
        assertTrue(xml.contains("http-get:*:application/vnd.apple.mpegurl:*"))
        assertTrue(xml.contains("http-get:*:audio/mpeg:*"))
        assertTrue(xml.contains("<Source></Source>"))
    }
}
