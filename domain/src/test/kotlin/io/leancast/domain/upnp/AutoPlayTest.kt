package io.leancast.domain.upnp

import io.leancast.domain.cast.CastSession
import io.leancast.domain.cast.PlayerPort
import io.leancast.domain.cast.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec: upnp-renderer — SetAVTransportURI auto-starts playback (B站-style
 * senders set the URI and poll state without ever sending Play).
 */
class AutoPlayTest {

    private class Port : PlayerPort {
        var prepares = 0
        override fun prepareAndPlay(uri: String) { prepares++ }
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(ms: Long) = Unit
        override fun stop() = Unit
        override fun currentPositionMs() = 0L
        override fun currentDurationMs() = 0L
    }

    private val port = Port()
    private val session = CastSession(port)
    private val router = SoapRouter(session)

    private fun req(action: String, vararg args: Pair<String, String>) =
        SoapRequest(UpnpService.AVT, action, args.toMap())

    @Test
    fun `SetAVTransportURI with a URI auto-starts playback`() {
        router.route(req("SetAVTransportURI", "CurrentURI" to "http://x/v.m3u8"))
        assertEquals(TransportState.TRANSITIONING, session.state)
        assertEquals(1, port.prepares)
    }

    @Test
    fun `SetAVTransportURI with empty URI clears without starting`() {
        session.setUri("http://x/v.mp4", null)
        session.play()
        router.route(req("SetAVTransportURI", "CurrentURI" to ""))
        assertEquals(TransportState.NO_MEDIA_PRESENT, session.state)
    }

    @Test
    fun `explicit Play after auto-start does not restart media`() {
        router.route(req("SetAVTransportURI", "CurrentURI" to "http://x/v.mp4"))
        session.onPlayerPrepared() // auto-started playback reaches PLAYING
        router.route(req("Play"))
        assertEquals(TransportState.PLAYING, session.state)
        assertEquals("prepare must run exactly once", 1, port.prepares)
    }

    @Test
    fun `Play during TRANSITIONING is a no-op`() {
        router.route(req("SetAVTransportURI", "CurrentURI" to "http://x/v.mp4"))
        assertTrue(session.state == TransportState.TRANSITIONING)
        router.route(req("Play"))
        assertEquals(1, port.prepares)
    }
}
