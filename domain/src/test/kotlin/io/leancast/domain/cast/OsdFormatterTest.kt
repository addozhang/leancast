package io.leancast.domain.cast

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec: media-playback — Requirement: On-screen playback HUD (seek delta & progress formatting). */
class OsdFormatterTest {

    @Test
    fun `formats signed seek delta in seconds or minutes`() {
        assertEquals("+10s", OsdFormatter.formatSeekDelta(10_000L))
        assertEquals("-10s", OsdFormatter.formatSeekDelta(-10_000L))
        assertEquals("+30s", OsdFormatter.formatSeekDelta(30_000L))
        assertEquals("+1:00", OsdFormatter.formatSeekDelta(60_000L))
        assertEquals("-2:00", OsdFormatter.formatSeekDelta(-120_000L))
        assertEquals("+5:00", OsdFormatter.formatSeekDelta(300_000L))
    }

    @Test
    fun `formats compact playback clock as MM colon SS or H colon MM colon SS`() {
        assertEquals("00:00", OsdFormatter.formatClock(0L))
        assertEquals("01:05", OsdFormatter.formatClock(65_000L))
        assertEquals("24:45", OsdFormatter.formatClock(1_485_000L))
        assertEquals("1:31:32", OsdFormatter.formatClock(5_492_000L))
    }

    @Test
    fun `computes progress permille clamped to 0-1000`() {
        assertEquals(0, OsdFormatter.progressPermille(0L, 0L))
        assertEquals(0, OsdFormatter.progressPermille(-500L, 10_000L))
        assertEquals(250, OsdFormatter.progressPermille(30_000L, 120_000L))
        assertEquals(1000, OsdFormatter.progressPermille(150_000L, 120_000L))
    }
}
