package io.leancast.domain.upnp

import io.leancast.domain.cast.TransportActions
import io.leancast.domain.cast.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** Spec: upnp-renderer — Requirement: Deliver GENA LastChange events. */
class LastChangeEventTest {

    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))

    @Test
    fun `propertyset is well formed and carries an escaped LastChange`() {
        val body = LastChangeEvent.avTransport("PLAYING", "http://h/v.mp4", "1:00:00")
        val doc = parse(body)
        val props = doc.getElementsByTagNameNS("urn:schemas-upnp-org:event-1-0", "property")
        assertEquals(1, props.length)

        val lastChange = doc.getElementsByTagName("LastChange").item(0).textContent
        // The inner document must arrive as text, i.e. escaped in the wire form.
        assertTrue(lastChange.contains("<TransportState val=\"PLAYING\"/>"))
        assertFalse(body.contains("<Event xmlns"))
    }

    @Test
    fun `inner event parses as its own document`() {
        val body = LastChangeEvent.avTransport("PAUSED_PLAYBACK", "http://h/v.mp4", "0:01:30")
        val inner = parse(body).getElementsByTagName("LastChange").item(0).textContent
        val event = parse(inner)
        assertEquals("InstanceID", event.documentElement.firstChild.nodeName)
        assertTrue(inner.contains("PAUSED_PLAYBACK"))
        assertTrue(inner.contains("0:01:30"))
    }

    @Test
    fun `uri with special characters does not break the payload`() {
        val body = LastChangeEvent.avTransport("PLAYING", "http://h/a.mp4?x=1&y=2", "0:00:00")
        val inner = parse(body).getElementsByTagName("LastChange").item(0).textContent
        parse(inner) // must not throw
        assertTrue(inner.contains("&amp;") || inner.contains("&"))
    }

    @Test
    fun `rendering control event carries volume and mute`() {
        val body = LastChangeEvent.renderingControl(volume = 60, mute = true)
        val inner = parse(body).getElementsByTagName("LastChange").item(0).textContent
        assertTrue(inner.contains("<Volume channel=\"Master\" val=\"60\"/>"))
        assertTrue(inner.contains("<Mute channel=\"Master\" val=\"1\"/>"))
    }

    @Test
    fun `advertised actions track the transport state`() {
        assertEquals("Pause,Stop,Seek", TransportActions.forState(TransportState.PLAYING, true))
        assertEquals("Play,Stop,Seek", TransportActions.forState(TransportState.PAUSED_PLAYBACK, true))
        assertEquals("Stop", TransportActions.forState(TransportState.TRANSITIONING, true))
        assertEquals("Play,Seek", TransportActions.forState(TransportState.STOPPED, true))
        assertEquals("", TransportActions.forState(TransportState.NO_MEDIA_PRESENT, false))
    }

    @Test
    fun `live streams do not advertise seek`() {
        assertEquals("Pause,Stop", TransportActions.forState(TransportState.PLAYING, true, isLive = true))
        assertEquals("Play,Stop", TransportActions.forState(TransportState.PAUSED_PLAYBACK, true, isLive = true))
        assertEquals("Play", TransportActions.forState(TransportState.STOPPED, true, isLive = true))
    }

    @Test
    fun `live event omits seek from CurrentTransportActions`() {
        val body = LastChangeEvent.avTransport(
            "PLAYING", "http://live/x.m3u8", "0:00:00", isLive = true,
        )
        val inner = parse(body).getElementsByTagName("LastChange").item(0).textContent
        assertTrue(inner.contains("CurrentTransportActions val=\"Pause,Stop\""))
    }
}
