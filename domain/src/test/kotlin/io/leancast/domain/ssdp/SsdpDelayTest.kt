package io.leancast.domain.ssdp

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec: ssdp-discovery — Requirement: Respect MX response delay bound. */
class SsdpDelayTest {

    private val responder = SsdpResponder(
        SsdpIdentity("uuid:x", "http://x/", "srv"),
        clockMs = { 0L },
    )

    private fun samples(mx: Int?, count: Int = 2_000): List<Long> =
        (0 until count).map { responder.responseDelayMs(mxSeconds = mx, random = Random(it)) }

    @Test
    fun `delays for MX=2 stay within 0 to 2000 ms`() {
        val delays = samples(2)
        assertTrue(delays.all { it in 0..2_000 })
        assertTrue("expected variety", delays.distinct().size > 10)
    }

    @Test
    fun `delays for MX=10 are capped at 5 seconds bound`() {
        val delays = samples(10)
        assertTrue(delays.all { it in 0..5_000 })
        assertTrue("cap should be reachable in distribution", delays.any { it > 4_500 })
    }

    @Test
    fun `missing MX defaults to 1 second bound`() {
        val delays = samples(null)
        assertTrue(delays.all { it in 0..1_000 })
        assertTrue("default bound should be reachable", delays.any { it > 900 })
    }
}
