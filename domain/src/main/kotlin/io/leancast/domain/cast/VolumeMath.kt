package io.leancast.domain.cast

/** Pure volume math shared by the session and the device stream adapter. */
object VolumeMath {

    /** Clamps a requested percentage into 0..100. */
    fun clampPercent(percent: Int): Int = percent.coerceIn(0, 100)

    /** Maps a 0..100 percentage onto device stream steps (0..maxSteps), rounding half up. */
    fun mapPercentToSteps(percent: Int, maxSteps: Int): Int =
        Math.round(clampPercent(percent) * maxSteps / 100.0).toInt()
}
