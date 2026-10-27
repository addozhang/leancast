package io.leancast.domain.upnp

import io.leancast.domain.cast.CastSession
import io.leancast.domain.cast.PlayerPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec: upnp-renderer — Requirement: Dispatch SOAP control actions.
 * Drives a real CastSession through the router with a scripted fake player.
 */
class SoapRouterTest {

    private class Port : PlayerPort {
        var uri: String? = null
        var playing = false
        var seekMs: Long? = null
        override fun prepareAndPlay(uri: String) { this.uri = uri; playing = true }
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun seekTo(ms: Long) { seekMs = ms }
        override fun stop() { playing = false }
        override fun currentPositionMs() = 5_492_000L
        override fun currentDurationMs() = 7_200_000L
    }

    private val port = Port()
    private val session = CastSession(port)
    private val router = SoapRouter(session)

    private fun req(service: UpnpService, action: String, vararg args: Pair<String, String>) =
        SoapRequest(service, action, args.toMap())

    @Test
    fun `malformed request yields HTTP 400 fault`() {
        val result = router.route(null)
        assertEquals(400, result.httpStatus)
        assertTrue(result.body.contains("<errorCode>402</errorCode>"))
    }

    @Test
    fun `unknown action yields HTTP 401 Invalid Action`() {
        val result = router.route(req(UpnpService.AVT, "Record"))
        assertEquals(401, result.httpStatus)
        assertTrue(result.body.contains("<errorCode>401</errorCode>"))
        assertTrue(result.body.contains("Invalid Action"))
    }

    @Test
    fun `SetAVTransportURI then Play drives the session`() {
        val set = router.route(
            req(
                UpnpService.AVT, "SetAVTransportURI",
                "CurrentURI" to "http://x/v.m3u8",
                "CurrentURIMetaData" to "<DIDL-Lite><item><dc:title>T</dc:title></item></DIDL-Lite>",
            ),
        )
        assertEquals(200, set.httpStatus)
        assertEquals("http://x/v.m3u8", session.currentUri)

        val play = router.route(req(UpnpService.AVT, "Play", "InstanceID" to "0", "Speed" to "1"))
        assertEquals(200, play.httpStatus)
        assertEquals(io.leancast.domain.cast.TransportState.TRANSITIONING, session.state)
        session.onPlayerPrepared()
        assertEquals(io.leancast.domain.cast.TransportState.PLAYING, session.state)
    }

    @Test
    fun `Seek REL_TIME converts and forwards, other units ignored`() {
        session.setUri("http://x/v.mp4", null)
        session.play(); session.onPlayerPrepared()

        router.route(req(UpnpService.AVT, "Seek", "Unit" to "REL_TIME", "Target" to "0:01:30.500"))
        assertEquals(90_500L, port.seekMs)

        port.seekMs = null
        router.route(req(UpnpService.AVT, "Seek", "Unit" to "TRACK_NR", "Target" to "1"))
        assertEquals(null, port.seekMs)
    }

    @Test
    fun `GetPositionInfo renders live session values`() {
        session.setUri("http://x/v.mp4", null)
        session.play(); session.onPlayerPrepared()

        val result = router.route(req(UpnpService.AVT, "GetPositionInfo"))
        assertEquals(200, result.httpStatus)
        assertTrue(result.body.contains("<RelTime>1:31:32</RelTime>"))
        assertTrue(result.body.contains("<TrackDuration>2:00:00</TrackDuration>"))
        assertTrue(result.body.contains("<TrackURI>http://x/v.mp4</TrackURI>"))
    }

    @Test
    fun `GetTransportInfo renders current state`() {
        session.setUri("http://x/v.mp4", null)
        val result = router.route(req(UpnpService.AVT, "GetTransportInfo"))
        assertTrue(result.body.contains("NO_MEDIA_PRESENT"))
    }

    @Test
    fun `GetMediaInfo reports one track while media stored`() {
        session.setUri("http://x/v.mp4", null)
        val result = router.route(req(UpnpService.AVT, "GetMediaInfo"))
        assertTrue(result.body.contains("<NrTracks>1</NrTracks>"))
        session.setUri(null, null)
        val cleared = router.route(req(UpnpService.AVT, "GetMediaInfo"))
        assertTrue(cleared.body.contains("<NrTracks>0</NrTracks>"))
    }

    @Test
    fun `volume actions round-trip`() {
        router.route(req(UpnpService.RCS, "SetVolume", "Channel" to "Master", "DesiredVolume" to "37"))
        assertEquals(37, session.volume)
        val get = router.route(req(UpnpService.RCS, "GetVolume", "Channel" to "Master"))
        assertTrue(get.body.contains("<CurrentVolume>37</CurrentVolume>"))
    }

    @Test
    fun `GetProtocolInfo routes to CM`() {
        val result = router.route(req(UpnpService.CM, "GetProtocolInfo"))
        assertEquals(200, result.httpStatus)
        assertTrue(result.body.contains("http-get:*:video/mp4:*"))
    }

    @Test
    fun `Pause Stop and empty SetAVTransportURI succeed`() {
        session.setUri("http://x/v.mp4", null)
        session.play(); session.onPlayerPrepared()
        assertEquals(200, router.route(req(UpnpService.AVT, "Pause")).httpStatus)
        assertEquals(200, router.route(req(UpnpService.AVT, "Stop")).httpStatus)
        assertEquals(200, router.route(req(UpnpService.AVT, "SetAVTransportURI", "CurrentURI" to "")).httpStatus)
    }
}
