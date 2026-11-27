package io.leancast.domain.cast

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for PendingSeekTracker OSD convergence and timeout expiry. */
class PendingSeekTrackerTest {

    @Test
    fun `holds pending target while player catches up then clears within tolerance`() {
        var now = 1_000L
        val tracker = PendingSeekTracker(timeoutMs = 2_000L, toleranceMs = 1_500L, clockMs = { now })

        tracker.recordSeek(60_000L)
        assertEquals(60_000L, tracker.resolveDisplayPosition(reportedPositionMs = 10_000L))

        // Player settles near target -> clears and reports actual position.
        now += 400L
        assertEquals(59_200L, tracker.resolveDisplayPosition(reportedPositionMs = 59_200L))
        // Subsequent ticks continue tracking actual position.
        now += 400L
        assertEquals(59_600L, tracker.resolveDisplayPosition(reportedPositionMs = 59_600L))
    }

    @Test
    fun `expires after timeout when player snaps to a distant keyframe or stays paused`() {
        var now = 5_000L
        val tracker = PendingSeekTracker(timeoutMs = 2_000L, toleranceMs = 1_500L, clockMs = { now })

        tracker.recordSeek(60_000L)
        // Player snapped to keyframe at 55s (5s away > 1.5s tolerance) and is paused.
        now += 1_000L
        assertEquals(60_000L, tracker.resolveDisplayPosition(reportedPositionMs = 55_000L))

        // After timeout, tracker yields the real player position instead of freezing forever.
        now += 1_000L
        assertEquals(55_000L, tracker.resolveDisplayPosition(reportedPositionMs = 55_000L))
    }

    @Test
    fun `rapid key repeats accumulate on pending target before timeout`() {
        var now = 1_000L
        val tracker = PendingSeekTracker(timeoutMs = 2_000L, clockMs = { now })

        tracker.recordSeek(20_000L)
        now += 200L
        assertEquals(20_000L, tracker.basePosition(reportedPositionMs = 10_000L))

        tracker.recordSeek(30_000L)
        now += 2_100L
        // After timeout, base position falls back to actual reported position.
        assertEquals(31_000L, tracker.basePosition(reportedPositionMs = 31_000L))
    }
}
