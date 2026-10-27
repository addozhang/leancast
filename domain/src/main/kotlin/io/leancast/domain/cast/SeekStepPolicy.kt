package io.leancast.domain.cast

/**
 * Escalating seek ladder for held keys: 10s -> 30s -> 60s -> 120s -> 300s (cap).
 * Pure function of the key event's repeat count so it is unit-testable and
 * remote-agnostic (DPAD repeats and FF/REW keycodes both feed it).
 */
object SeekStepPolicy {

    private val LADDER_MS = longArrayOf(10_000L, 30_000L, 60_000L, 120_000L, 300_000L)

    /** Repeat counts 0-4 -> 10s, 5-9 -> 30s, 10-14 -> 60s, 15-19 -> 120s, 20+ -> 300s. */
    fun stepFor(repeatCount: Int, forward: Boolean = true): Long {
        val index = (repeatCount / 5).coerceAtMost(LADDER_MS.size - 1)
        val magnitude = LADDER_MS[index]
        return if (forward) magnitude else -magnitude
    }
}
