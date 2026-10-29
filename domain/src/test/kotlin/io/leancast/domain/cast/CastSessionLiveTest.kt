package io.leancast.domain.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec: cast-session — Requirement: Report live streams without a programme duration.
 *
 * A live player hands back its sliding-window length (e.g. 15 s for a 3×5 s
 * playlist) and a position that resets every time the window advances. Senders
 * rendered that as a progress bar that looped, and could conclude the playback
 * had finished. Live items now report "unknown duration" plus the time actually
 * watched; on-demand items keep the player's real values.
 */
class CastSessionLiveTest {

    private class Port(var live: Boolean) : PlayerPort {
        var preparedUri: String? = null
        var seekMs: Long? = null
        var positionMs = 7_000L
        var durationMs = 60_000L

        override fun prepareAndPlay(uri: String) { preparedUri = uri }
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(ms: Long) { seekMs = ms }
        override fun stop() = Unit
        override fun currentPositionMs() = positionMs
        override fun currentDurationMs() = durationMs
        override fun isLive() = live
    }

    private val port = Port(live = true)
    private var now = 1_000_000L
    private val session = CastSession(port, clockMs = { now })

    private fun start() {
        session.setUri("http://live/channel.m3u8", null)
        session.play()
        session.onPlayerPrepared()
    }

    @Test
    fun `live stream reports unknown duration regardless of the window`() {
        port.durationMs = 15_000 // 3 x 5 s window
        start()
        assertEquals(0L, session.durationMs())
    }

    @Test
    fun `live position counts time watched and keeps increasing`() {
        start()
        assertEquals(0L, session.positionMs())
        now += 5_000
        assertEquals(5_000L, session.positionMs())
        now += 5_000
        assertEquals(10_000L, session.positionMs())
    }

    @Test
    fun `live position freezes while paused and continues after resume`() {
        start()
        now += 3_000
        session.pause()
        now += 30_000 // time passes while paused
        assertEquals(3_000L, session.positionMs())
        session.resume()
        now += 2_000
        assertEquals(5_000L, session.positionMs())
    }

    @Test
    fun `seek is ignored on a live stream`() {
        start()
        session.seekTo(120_000)
        assertNull(port.seekMs)
    }

    @Test
    fun `on-demand item still reports the player duration and position`() {
        val vod = Port(live = false)
        val s = CastSession(vod, clockMs = { now })
        s.setUri("http://cdn/movie.mp4", null)
        s.play()
        s.onPlayerPrepared()
        assertEquals(60_000L, s.durationMs())
        assertEquals(7_000L, s.positionMs())
        s.seekTo(30_000)
        assertEquals(30_000L, vod.seekMs)
    }

    @Test
    fun `stop clears the watched time`() {
        start()
        now += 7_000
        session.stop()
        assertEquals(0L, session.positionMs())
        session.play()
        assertEquals(0L, session.positionMs())
    }
}
