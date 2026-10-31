package io.leancast.domain.cast

import kotlin.math.abs

/** Pure formatting and progress calculation helpers for the TV playback OSD. */
object OsdFormatter {

    /**
     * Renders a signed seek step such as `+10s`, `-30s`, `+1:00`, `-5:00`.
     */
    fun formatSeekDelta(deltaMs: Long): String {
        val sign = if (deltaMs >= 0) "+" else "-"
        val totalSec = abs(deltaMs) / 1000L
        return if (totalSec < 60L) {
            "$sign${totalSec}s"
        } else {
            val min = totalSec / 60L
            val sec = totalSec % 60L
            "$sign%d:%02d".format(min, sec)
        }
    }

    /**
     * Renders milliseconds as `MM:SS` under 1 hour, or `H:MM:SS` at 1 hour and above.
     */
    fun formatClock(ms: Long): String {
        val totalSec = ms.coerceAtLeast(0L) / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%02d:%02d".format(m, s)
        }
    }

    /**
     * Computes progress on a `0..1000` scale for smooth TV progress bar rendering.
     */
    fun progressPermille(positionMs: Long, durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        val ratio = (positionMs.coerceAtLeast(0L) * 1000L) / durationMs
        return ratio.toInt().coerceIn(0, 1000)
    }

    /**
     * Renders a playback speed multiplier as `0.75x`, `1.0x`, `1.25x`, `1.5x`, `2.0x`.
     */
    fun formatSpeed(speed: Float): String {
        val scaled = kotlin.math.round(speed * 100f).toInt()
        return if (scaled % 10 == 0) {
            "%.1fx".format(java.util.Locale.US, scaled / 100.0)
        } else {
            "%.2fx".format(java.util.Locale.US, scaled / 100.0)
        }
    }
}
