package io.leancast.domain.upnp

import io.leancast.domain.cast.CastSession
import io.leancast.domain.cast.PlayerPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec: upnp-renderer — Requirement: Every SCPD-advertised action is routable.
 *
 * Regression guard for the sender-side "playback error" reports: common control
 * actions used to answer 401 Invalid Action, which many clients treat as a
 * broken renderer even while the media keeps playing.
 */
class SoapRouterCapabilitiesTest {

    private class Port : PlayerPort {
        override fun prepareAndPlay(uri: String) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(ms: Long) = Unit
        override fun stop() = Unit
        override fun currentPositionMs() = 0L
        override fun currentDurationMs() = 0L
    }

    private val router = SoapRouter(CastSession(Port()))

    private fun route(service: UpnpService, action: String, vararg args: Pair<String, String>) =
        router.route(SoapRequest(service, action, args.toMap()))

    @Test
    fun `GetTransportSettings returns NORMAL play mode`() {
        val r = route(UpnpService.AVT, "GetTransportSettings")
        assertEquals(200, r.httpStatus)
        assertTrue(r.body.contains("<PlayMode>NORMAL</PlayMode>"))
    }

    @Test
    fun `GetDeviceCapabilities advertises network playback`() {
        val r = route(UpnpService.AVT, "GetDeviceCapabilities")
        assertEquals(200, r.httpStatus)
        assertTrue(r.body.contains("<PlayMedia>NETWORK,NONE</PlayMedia>"))
    }

    @Test
    fun `GetCurrentTransportActions reports controls for the current state`() {
        val idle = route(UpnpService.AVT, "GetCurrentTransportActions")
        assertEquals(200, idle.httpStatus)
        assertTrue(idle.body.contains("<Actions></Actions>"))
    }

    @Test
    fun `ConnectionManager connection queries answer instead of faulting`() {
        assertEquals(200, route(UpnpService.CM, "GetCurrentConnectionIDs").httpStatus)
        assertEquals(
            200,
            route(UpnpService.CM, "GetCurrentConnectionInfo", "ConnectionID" to "0").httpStatus,
        )
    }

    @Test
    fun `RenderingControl preset actions answer instead of faulting`() {
        val list = route(UpnpService.RCS, "ListPresets")
        assertEquals(200, list.httpStatus)
        assertTrue(list.body.contains("<CurrentPresetNameList>FactoryDefaults</CurrentPresetNameList>"))
        assertEquals(200, route(UpnpService.RCS, "SelectPreset", "PresetName" to "FactoryDefaults").httpStatus)
    }

    @Test
    fun `device description advertises DLNA compliance`() {
        val doc = DeviceDescriptionDoc(
            RendererDescription("LeanCast", "uuid:abc", "LeanCast", "LeanCast Receiver", "http://h/"),
        ).deviceXml()
        assertTrue(doc.contains("xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\""))
        assertTrue(doc.contains("<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>"))
    }
}
