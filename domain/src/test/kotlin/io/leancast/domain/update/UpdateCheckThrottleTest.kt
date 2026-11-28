package io.leancast.domain.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec: app-update — Opt-in gating and 6-hour foreground auto-check throttle. */
class UpdateCheckThrottleTest {

    private val sixHours = UpdateCheckThrottle.DEFAULT_INTERVAL_MS

    @Test
    fun `disabled toggle always blocks auto-check`() {
        assertFalse(UpdateCheckThrottle.shouldAutoCheck(enabled = false, lastCheckMs = 0L, nowMs = 1_000_000L))
        assertFalse(UpdateCheckThrottle.shouldAutoCheck(enabled = false, lastCheckMs = 100L, nowMs = 100L + sixHours))
    }

    @Test
    fun `enabled toggle allows first check and checks after interval`() {
        assertTrue(UpdateCheckThrottle.shouldAutoCheck(enabled = true, lastCheckMs = 0L, nowMs = 1_000L))
        assertFalse(UpdateCheckThrottle.shouldAutoCheck(enabled = true, lastCheckMs = 1_000L, nowMs = 1_000L + sixHours - 1L))
        assertTrue(UpdateCheckThrottle.shouldAutoCheck(enabled = true, lastCheckMs = 1_000L, nowMs = 1_000L + sixHours))
    }

    @Test
    fun `clock moving backwards does not permanently lock out auto-check`() {
        assertTrue(UpdateCheckThrottle.shouldAutoCheck(enabled = true, lastCheckMs = 10_000L, nowMs = 5_000L))
    }
}
