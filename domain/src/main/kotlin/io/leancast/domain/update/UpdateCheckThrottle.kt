package io.leancast.domain.update

/**
 * Pure policy governing foreground-only automatic update checks.
 */
object UpdateCheckThrottle {

    const val DEFAULT_INTERVAL_MS: Long = 6L * 60L * 60L * 1000L

    fun shouldAutoCheck(
        enabled: Boolean,
        lastCheckMs: Long,
        nowMs: Long,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
    ): Boolean {
        if (!enabled) return false
        if (lastCheckMs <= 0L) return true
        if (nowMs < lastCheckMs) return true
        return (nowMs - lastCheckMs) >= intervalMs
    }
}
