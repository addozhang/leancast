package io.leancast.domain.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec: media-playback — Requirement: Map volume to the device stream. */
class VolumeTest {

    @Test
    fun `volume clamps to 0-100`() {
        assertEquals(100, VolumeMath.clampPercent(150))
        assertEquals(100, VolumeMath.clampPercent(100))
        assertEquals(0, VolumeMath.clampPercent(-3))
        assertEquals(37, VolumeMath.clampPercent(37))
    }

    @Test
    fun `percent maps onto device steps with rounding`() {
        assertEquals(6, VolumeMath.mapPercentToSteps(37, maxSteps = 15))
        assertEquals(0, VolumeMath.mapPercentToSteps(0, maxSteps = 15))
        assertEquals(15, VolumeMath.mapPercentToSteps(100, maxSteps = 15))
        assertEquals(2, VolumeMath.mapPercentToSteps(50, maxSteps = 3))
    }

    @Test
    fun `session set and get volume is clamped`() {
        class VolumeSinkRecorder : VolumeSink {
            var last = -1
            override fun onVolumeChanged(percent: Int) { last = percent }
        }
        val sink = VolumeSinkRecorder()
        val session = CastSession(FakeQuietPort())
        session.setVolumeSink(sink)

        session.setVolume(150)
        assertEquals(100, session.volume)
        assertEquals(100, sink.last)

        session.setVolume(37)
        assertEquals(37, session.volume)
        assertEquals(37, sink.last)
    }

    @Test
    fun `volume stays within bounds over many odd inputs`() {
        (-50..150).forEach { v ->
            val clamped = VolumeMath.clampPercent(v)
            assertTrue(clamped in 0..100)
        }
    }

    private class FakeQuietPort : PlayerPort {
        override fun prepareAndPlay(uri: String) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(ms: Long) = Unit
        override fun stop() = Unit
        override fun currentPositionMs() = 0L
        override fun currentDurationMs() = 0L
    }
}
