package io.leancast.domain.cast

import kotlin.math.abs

/**
 * Playback speed steps and cycling rules for the TV remote D-pad.
 *
 * Cycle order starts at 1.0x so the first press reaches 1.25x, then 1.5x, 2.0x,
 * wraps through 0.75x, and returns to 1.0x:
 * `1.0 -> 1.25 -> 1.5 -> 2.0 -> 0.75 -> 1.0`.
 */
object PlaybackSpeedPolicy {

    const val DEFAULT_SPEED = 1.0f

    val STEPS: List<Float> = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    /** Returns the next speed in the cycle after [current]. */
    fun nextSpeed(current: Float): Float {
        val index = STEPS.indexOfFirst { abs(it - current) < 0.001f }
        if (index < 0) return DEFAULT_SPEED
        return STEPS[(index + 1) % STEPS.size]
    }

    /**
     * Parses an optional UPnP `Speed` argument when it names an explicit
     * non-default supported step (`0.75`, `1.25`, `1.5`, `2` / `2.0`).
     *
     * Standard DLNA control points send `<Speed>1</Speed>` on every `Play`
     * action (including resume after pause), so `"1"` / `"1.0"` returns `null`
     * to avoid wiping out a speed chosen on the TV via remote.
     */
    fun parseExplicitUpnpSpeed(raw: String?): Float? {
        val value = raw?.trim()?.toFloatOrNull() ?: return null
        if (abs(value - DEFAULT_SPEED) < 0.001f) return null
        return STEPS.firstOrNull { abs(it - value) < 0.001f }
    }
}
