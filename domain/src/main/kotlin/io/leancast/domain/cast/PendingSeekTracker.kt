package io.leancast.domain.cast

import kotlin.math.abs

/**
 * Tracks an in-flight D-pad seek target so rapid key repeats accumulate on top
 * of the last requested target rather than the stale 500 ms position cache,
 * while automatically expiring if the player snaps to a distant keyframe or is
 * paused so the OSD clock never freezes permanently.
 */
class PendingSeekTracker(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val toleranceMs: Long = DEFAULT_TOLERANCE_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private var pendingTargetMs: Long = -1L
    private var lastSeekAtMs: Long = 0L

    @Synchronized
    fun recordSeek(targetMs: Long, nowMs: Long = clockMs()) {
        pendingTargetMs = targetMs.coerceAtLeast(0L)
        lastSeekAtMs = nowMs
    }

    @Synchronized
    fun basePosition(reportedPositionMs: Long, nowMs: Long = clockMs()): Long {
        if (pendingTargetMs < 0L) return reportedPositionMs
        if (nowMs - lastSeekAtMs >= timeoutMs) {
            pendingTargetMs = -1L
            return reportedPositionMs
        }
        return pendingTargetMs
    }

    @Synchronized
    fun resolveDisplayPosition(reportedPositionMs: Long, nowMs: Long = clockMs()): Long {
        val target = pendingTargetMs
        if (target < 0L) return reportedPositionMs
        if (abs(reportedPositionMs - target) < toleranceMs || (nowMs - lastSeekAtMs) >= timeoutMs) {
            pendingTargetMs = -1L
            return reportedPositionMs
        }
        return target
    }

    @Synchronized
    fun clear() {
        pendingTargetMs = -1L
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
        const val DEFAULT_TOLERANCE_MS = 1_500L
    }
}
