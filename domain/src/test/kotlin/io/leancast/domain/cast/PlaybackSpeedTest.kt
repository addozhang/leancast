package io.leancast.domain.cast

import io.leancast.domain.upnp.SoapRequest
import io.leancast.domain.upnp.SoapRouter
import io.leancast.domain.upnp.UpnpService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec: media-playback — Requirement: Playback speed cycling and reset.
 */
class PlaybackSpeedTest {

    private class FakePort(var live: Boolean = false) : PlayerPort {
        var appliedSpeed: Float = 1.0f
        val speedHistory = mutableListOf<Float>()

        override fun prepareAndPlay(uri: String) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(ms: Long) = Unit
        override fun stop() = Unit
        override fun currentPositionMs() = 30_000L
        override fun currentDurationMs() = 120_000L
        override fun isLive() = live
        override fun setPlaybackSpeed(speed: Float) {
            appliedSpeed = speed
            speedHistory += speed
        }
    }

    @Test
    fun `policy cycles 1_0 to 1_25 to 1_5 to 2_0 to 0_75 and back to 1_0`() {
        var speed = PlaybackSpeedPolicy.DEFAULT_SPEED
        val visited = (1..5).map {
            speed = PlaybackSpeedPolicy.nextSpeed(speed)
            speed
        }
        assertEquals(listOf(1.25f, 1.5f, 2.0f, 0.75f, 1.0f), visited)
    }

    @Test
    fun `cyclePlaybackSpeed advances through steps and updates PlayerPort`() {
        val port = FakePort()
        val session = CastSession(port)
        session.setUri("http://cdn/video.mp4", null)
        session.play()
        session.onPlayerPrepared()

        assertEquals(1.0f, session.playbackSpeed, 0.001f)
        assertEquals(1.25f, session.cyclePlaybackSpeed() ?: -1f, 0.001f)
        assertEquals(1.25f, port.appliedSpeed, 0.001f)
        assertEquals(1.5f, session.cyclePlaybackSpeed() ?: -1f, 0.001f)
        assertEquals(2.0f, session.cyclePlaybackSpeed() ?: -1f, 0.001f)
        assertEquals(0.75f, session.cyclePlaybackSpeed() ?: -1f, 0.001f)
        assertEquals(1.0f, session.cyclePlaybackSpeed() ?: -1f, 0.001f)
        assertEquals(1.0f, port.appliedSpeed, 0.001f)
    }

    @Test
    fun `new SetAVTransportURI resets playback speed to 1_0`() {
        val port = FakePort()
        val session = CastSession(port)
        session.setUri("http://cdn/ep1.mp4", null)
        session.play()
        session.onPlayerPrepared()
        session.cyclePlaybackSpeed() // 1.25x
        session.cyclePlaybackSpeed() // 1.5x
        assertEquals(1.5f, session.playbackSpeed, 0.001f)

        session.setUri("http://cdn/ep2.mp4", null)
        assertEquals(1.0f, session.playbackSpeed, 0.001f)
        assertEquals(1.0f, port.appliedSpeed, 0.001f)
    }

    @Test
    fun `live stream ignores speed cycling`() {
        val port = FakePort(live = true)
        val session = CastSession(port)
        session.setUri("http://live/stream.m3u8", null)
        session.play()
        session.onPlayerPrepared()

        assertNull(session.cyclePlaybackSpeed())
        assertEquals(1.0f, session.playbackSpeed, 0.001f)
        assertEquals(1.0f, port.appliedSpeed, 0.001f)
    }

    @Test
    fun `idle or stopped session ignores speed cycling`() {
        val port = FakePort()
        val session = CastSession(port)
        assertNull(session.cyclePlaybackSpeed())

        session.setUri("http://cdn/video.mp4", null)
        session.play()
        session.onPlayerPrepared()
        session.stop()
        assertNull(session.cyclePlaybackSpeed())
    }

    @Test
    fun `routine SOAP Play with Speed 1 preserves TV-selected speed, explicit non-1 Speed applies`() {
        val port = FakePort()
        val session = CastSession(port)
        val router = SoapRouter(session)

        router.route(SoapRequest(UpnpService.AVT, "SetAVTransportURI", mapOf("CurrentURI" to "http://cdn/v.mp4")))
        session.onPlayerPrepared()
        session.cyclePlaybackSpeed() // 1.25x
        session.cyclePlaybackSpeed() // 1.5x

        // Sender pauses and resumes with standard <Speed>1</Speed>; must not wipe 1.5x
        router.route(SoapRequest(UpnpService.AVT, "Pause", emptyMap()))
        router.route(SoapRequest(UpnpService.AVT, "Play", mapOf("InstanceID" to "0", "Speed" to "1")))
        assertEquals(1.5f, session.playbackSpeed, 0.001f)

        // Sender explicitly requests 2x in Play
        router.route(SoapRequest(UpnpService.AVT, "Play", mapOf("InstanceID" to "0", "Speed" to "2")))
        assertEquals(2.0f, session.playbackSpeed, 0.001f)
        assertEquals(2.0f, port.appliedSpeed, 0.001f)
    }
}
