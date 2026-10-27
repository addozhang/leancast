package io.leancast.domain.cast

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec: media-playback — Requirement: Accelerated seeking on key hold.
 * Ladder: 10s base, escalating to 30/60/120/300s as repeats accumulate.
 */
class SeekStepPolicyTest {

    @Test
    fun `escalating steps while held`() {
        // repeatCount -> expected ms, straight from the spec scenario
        val expected = mapOf(
            0 to 10_000L,
            4 to 10_000L,
            5 to 30_000L,
            9 to 30_000L,
            10 to 60_000L,
            14 to 60_000L,
            15 to 120_000L,
            30 to 300_000L,
            1000 to 300_000L,
        )
        expected.forEach { (repeatCount, ms) ->
            assertEquals("repeatCount=$repeatCount", ms, SeekStepPolicy.stepFor(repeatCount))
        }
    }

    @Test
    fun `direction multiplies sign`() {
        assertEquals(-30_000L, SeekStepPolicy.stepFor(5, forward = false))
        assertEquals(30_000L, SeekStepPolicy.stepFor(5, forward = true))
    }

    @Test
    fun `monotonic non-decreasing over all counts`() {
        (0..200).fold(0L) { prev, rc ->
            val step = SeekStepPolicy.stepFor(rc)
            assert(step >= prev && step <= 300_000L) { "rc=$rc step=$step prev=$prev" }
            step
        }
    }
}
