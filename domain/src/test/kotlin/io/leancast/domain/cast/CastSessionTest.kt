package io.leancast.domain.cast

import io.leancast.domain.upnp.UpnpService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec: media-playback — Requirements: Transport state machine,
 * Track position and duration, Handle Seek (session side).
 * Spec: upnp-renderer — Requirement: Handle SetAVTransportURI.
 */
class CastSessionTest {

    private class FakePort : PlayerPort {
        var preparedUri: String? = null
        var playing = false
        var paused = false
        var seekTargetMs: Long? = null
        var stopCount = 0
        var overridePositionMs = 0L
        var overrideDurationMs = 0L

        override fun prepareAndPlay(uri: String) {
            preparedUri = uri; playing = true; paused = false
        }
        override fun play() { playing = true; paused = false }
        override fun pause() { paused = true }
        override fun seekTo(ms: Long) { seekTargetMs = ms }
        override fun stop() { stopCount++; playing = false; paused = false }
        override fun currentPositionMs() = overridePositionMs
        override fun currentDurationMs() = overrideDurationMs
    }

    private class RecordingListener : CastSession.Listener {
        val states = mutableListOf<TransportState>()
        override fun onStateChanged(state: TransportState) { states += state }
    }

    private val port = FakePort()
    private val listener = RecordingListener()
    private val session = CastSession(port).apply { addListener(listener) }

    @Test
    fun `full happy path walks the spec state sequence`() {
        session.setUri("http://x/v.m3u8", null)
        session.play()
        session.onPlayerPrepared()
        session.pause()
        session.play()
        session.stop()

        assertEquals(
            listOf(
                TransportState.NO_MEDIA_PRESENT,
                TransportState.TRANSITIONING,
                TransportState.PLAYING,
                TransportState.PAUSED_PLAYBACK,
                TransportState.PLAYING,
                TransportState.STOPPED,
            ),
            listener.states,
        )
    }

    @Test
    fun `play hands stored uri to the player`() {
        session.setUri("http://x/v.mp4", null)
        session.play()
        assertEquals("http://x/v.mp4", port.preparedUri)
        assertEquals(TransportState.TRANSITIONING, session.state)
    }

    @Test
    fun `set uri stops previous media and extracts title`() {
        session.setUri("http://a/v.mp4", null)
        session.play()
        session.onPlayerPrepared()
        session.setUri(
            "http://b/v.m3u8",
            "<DIDL-Lite><item><dc:title>Big Buck Bunny</dc:title></item></DIDL-Lite>",
        )
        assertEquals(1, port.stopCount)
        assertEquals("http://b/v.m3u8", session.currentUri)
        assertEquals("Big Buck Bunny", session.currentTitle)
        assertEquals(TransportState.NO_MEDIA_PRESENT, session.state)
    }

    @Test
    fun `empty uri clears the session`() {
        session.setUri("http://a/v.mp4", null)
        session.setUri(null, null)
        assertNull(session.currentUri)
        assertEquals(TransportState.NO_MEDIA_PRESENT, session.state)
    }

    @Test
    fun `illegal commands are ignored without error`() {
        session.pause() // no media
        assertEquals(TransportState.NO_MEDIA_PRESENT, session.state)
        session.play() // no uri stored -> still ignored
        assertEquals(TransportState.NO_MEDIA_PRESENT, session.state)
        assertNull(port.preparedUri)
    }

    @Test
    fun `playback error parks session in STOPPED and receiver keeps serving`() {
        session.setUri("http://x/v.m3u8", null)
        session.play()
        session.onPlayerError()
        assertEquals(TransportState.STOPPED, session.state)

        // next cycle still works
        session.setUri("http://x/v2.m3u8", null)
        session.play()
        session.onPlayerPrepared()
        assertEquals(TransportState.PLAYING, session.state)
    }

    @Test
    fun `seek forwards milliseconds only when media present`() {
        session.setUri("http://x/v.mp4", null)
        session.seekTo(90_500) // no media yet -> ignored
        assertNull(port.seekTargetMs)

        session.play()
        session.onPlayerPrepared()
        session.seekTo(90_500)
        assertEquals(90_500L, port.seekTargetMs)
    }

    @Test
    fun `stop resets reported position`() {
        session.setUri("http://x/v.mp4", null)
        session.play()
        session.onPlayerPrepared()
        port.overridePositionMs = 5_492_000
        assertEquals(5_492_000L, session.positionMs())
        session.stop()
        assertEquals(0L, session.positionMs())
    }

    @Test
    fun `unprepared media reports zero position and duration`() {
        session.setUri("http://x/v.mp4", null)
        port.overridePositionMs = 9_999
        port.overrideDurationMs = 9_999
        assertEquals(0L, session.positionMs())
        assertEquals(0L, session.durationMs())
    }

    @Test
    fun `prepared media reports port position and duration`() {
        session.setUri("http://x/v.mp4", null)
        session.play()
        session.onPlayerPrepared()
        port.overridePositionMs = 5_492_000
        port.overrideDurationMs = 7_200_000
        assertEquals(5_492_000L, session.positionMs())
        assertEquals(7_200_000L, session.durationMs())
    }
}
