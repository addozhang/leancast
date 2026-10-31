package io.leancast.domain.cast

import io.leancast.domain.upnp.DidlParser

/** UPnP AV transport states. */
enum class TransportState { NO_MEDIA_PRESENT, TRANSITIONING, PLAYING, PAUSED_PLAYBACK, STOPPED }

/** Minimal surface the session needs from a media player. Implemented by ExoPlayer on device. */
interface PlayerPort {
    fun prepareAndPlay(uri: String)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun stop()
    fun currentPositionMs(): Long
    fun currentDurationMs(): Long

    /**
     * True when the current item is a live stream. For live items the player's
     * duration/position describe a sliding window rather than the programme, so
     * the session reports them differently (see [CastSession.durationMs]).
     */
    fun isLive(): Boolean = false

    /** Applies [speed] (e.g. `0.75f`..`2.0f`) with pitch correction enabled. */
    fun setPlaybackSpeed(speed: Float) = Unit
}

/** Notified when the UPnP-visible volume changes (0..100). */
fun interface VolumeSink {
    fun onVolumeChanged(percent: Int)
}

/**
 * The cast session: the AVTransport state machine plus current item bookkeeping.
 * Pure domain object; driven by SOAP actions and player callbacks.
 */
class CastSession(
    private val port: PlayerPort,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    fun interface Listener {
        fun onStateChanged(state: TransportState)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    private var volumeSink: VolumeSink? = null

    var state: TransportState = TransportState.NO_MEDIA_PRESENT
        private set
    var currentUri: String? = null
        private set
    var currentTitle: String? = null
        private set
    var currentMetaData: String? = null
        private set
    var volume: Int = 100
        private set
    var playbackSpeed: Float = PlaybackSpeedPolicy.DEFAULT_SPEED
        private set

    /** Live-only: wall-clock time spent playing, with paused intervals excluded. */
    private var elapsedAccumulatedMs = 0L
    private var elapsedRunningSinceMs: Long? = null

    /** True when the current item is a live stream; see [durationMs]. */
    fun isLive(): Boolean = port.isLive()

    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    fun setVolumeSink(sink: VolumeSink) {
        volumeSink = sink
    }

    /** SetAVTransportURI: stop anything playing, store the new item. Null URI clears. */
    fun setUri(uri: String?, metaData: String?) {
        if (state != TransportState.NO_MEDIA_PRESENT && state != TransportState.STOPPED) {
            port.stop()
        }
        currentUri = uri?.takeIf { it.isNotBlank() }
        currentMetaData = currentUri?.let { metaData }
        currentTitle = currentMetaData?.let { DidlParser.extractTitle(it) }
        resetPlaybackSpeed()
        resetElapsed()
        enter(TransportState.NO_MEDIA_PRESENT)
    }

    /** Play: resumes when paused; starts stored media otherwise; idempotent. */
    fun play() {
        when (state) {
            TransportState.PAUSED_PLAYBACK -> {
                port.play()
                markElapsedRunning()
                enter(TransportState.PLAYING)
            }
            TransportState.PLAYING, TransportState.TRANSITIONING -> Unit
            else -> {
                val uri = currentUri ?: return
                port.prepareAndPlay(uri)
                resetElapsed()
                markElapsedRunning()
                enter(TransportState.TRANSITIONING)
            }
        }
    }

    /** Pause: only meaningful while playing or already paused. */
    fun pause() {
        when (state) {
            TransportState.PLAYING, TransportState.PAUSED_PLAYBACK -> {
                port.pause()
                markElapsedPaused()
                enter(TransportState.PAUSED_PLAYBACK)
            }
            else -> Unit
        }
    }

    fun resume() {
        when (state) {
            TransportState.PAUSED_PLAYBACK -> {
                port.play()
                markElapsedRunning()
                enter(TransportState.PLAYING)
            }
            TransportState.TRANSITIONING -> Unit // already starting
            else -> play()
        }
    }

    fun stop() {
        port.stop()
        resetElapsed()
        enter(TransportState.STOPPED)
    }

    fun seekTo(ms: Long) {
        // A live stream has no meaningful absolute position; seeking into the
        // window would just force a rebuffer.
        if (isLive()) return
        when (state) {
            TransportState.PLAYING, TransportState.PAUSED_PLAYBACK, TransportState.TRANSITIONING ->
                port.seekTo(ms)
            else -> Unit
        }
    }

    /**
     * Cycles to the next playback speed in [PlaybackSpeedPolicy.STEPS] when VOD
     * media is active; returns the new speed, or `null` when ignored (live or idle).
     */
    fun cyclePlaybackSpeed(): Float? {
        if (!canAdjustSpeed()) return null
        val next = PlaybackSpeedPolicy.nextSpeed(playbackSpeed)
        playbackSpeed = next
        port.setPlaybackSpeed(next)
        return next
    }

    /**
     * Applies an explicit supported speed (e.g. from a UPnP `Play` action with
     * a non-default `Speed` argument). Ignored for live streams or unsupported values.
     */
    fun setPlaybackSpeed(speed: Float): Boolean {
        if (!canAdjustSpeed()) return false
        val matched = PlaybackSpeedPolicy.STEPS.firstOrNull { kotlin.math.abs(it - speed) < 0.001f }
            ?: return false
        playbackSpeed = matched
        port.setPlaybackSpeed(matched)
        return true
    }

    private fun canAdjustSpeed(): Boolean {
        if (isLive()) return false
        return when (state) {
            TransportState.PLAYING, TransportState.PAUSED_PLAYBACK, TransportState.TRANSITIONING -> true
            else -> false
        }
    }

    private fun resetPlaybackSpeed() {
        playbackSpeed = PlaybackSpeedPolicy.DEFAULT_SPEED
        port.setPlaybackSpeed(PlaybackSpeedPolicy.DEFAULT_SPEED)
    }

    /** Player callback: media prepared and rendering; duration now known. */
    fun onPlayerPrepared() {
        if (isLive() && playbackSpeed != PlaybackSpeedPolicy.DEFAULT_SPEED) {
            resetPlaybackSpeed()
        }
        if (state == TransportState.TRANSITIONING) enter(TransportState.PLAYING)
    }

    /** Player callback: fatal error; park in STOPPED, keep serving control actions. */
    fun onPlayerError() {
        if (state != TransportState.STOPPED) {
            port.stop()
            enter(TransportState.STOPPED)
        }
    }

    fun positionMs(): Long =
        when (state) {
            TransportState.PLAYING, TransportState.PAUSED_PLAYBACK, TransportState.TRANSITIONING ->
                if (isLive()) elapsedMs() else port.currentPositionMs().coerceAtLeast(0)
            else -> 0L
        }

    /**
     * Duration for senders. Live streams have no programme duration — the
     * player would otherwise hand back the length of its sliding window (a
     * 15-second number that keeps resetting), which senders render as a
     * progress bar that loops. Report "unknown" instead, like other renderers.
     */
    fun durationMs(): Long =
        when (state) {
            TransportState.PLAYING, TransportState.PAUSED_PLAYBACK, TransportState.TRANSITIONING ->
                if (isLive()) 0L else port.currentDurationMs().coerceAtLeast(0)
            else -> 0L
        }

    private fun elapsedMs(): Long =
        elapsedAccumulatedMs + (elapsedRunningSinceMs?.let { (clockMs() - it).coerceAtLeast(0) } ?: 0L)

    private fun markElapsedRunning() {
        if (elapsedRunningSinceMs == null) elapsedRunningSinceMs = clockMs()
    }

    private fun markElapsedPaused() {
        elapsedRunningSinceMs?.let { elapsedAccumulatedMs += (clockMs() - it).coerceAtLeast(0) }
        elapsedRunningSinceMs = null
    }

    private fun resetElapsed() {
        elapsedAccumulatedMs = 0L
        elapsedRunningSinceMs = null
    }

    fun setVolume(percent: Int) {
        val clamped = VolumeMath.clampPercent(percent)
        volume = clamped
        volumeSink?.onVolumeChanged(clamped)
    }

    private fun enter(next: TransportState) {
        state = next
        listeners.forEach { it.onStateChanged(next) }
    }
}
