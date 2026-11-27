package io.leancast.domain.cast

import io.leancast.domain.upnp.DidlParser
import java.util.concurrent.CopyOnWriteArrayList

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

    /** Current buffered position in ms for progressive/HLS VOD progress bars. */
    fun currentBufferedPositionMs(): Long = 0L

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

/** Notified when the UPnP-visible mute state changes. */
fun interface MuteSink {
    fun onMuteChanged(muted: Boolean)
}

/** Notified when the active VOD playback speed changes. */
fun interface SpeedSink {
    fun onSpeedChanged(speed: Float)
}

/**
 * The cast session: the AVTransport state machine plus current item bookkeeping.
 * Pure domain object; driven concurrently by SOAP HTTP worker threads, the TV UI
 * thread, and player callbacks.
 */
class CastSession(
    private val port: PlayerPort,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    fun interface Listener {
        fun onStateChanged(state: TransportState)
    }

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile private var volumeSink: VolumeSink? = null
    @Volatile private var muteSink: MuteSink? = null
    @Volatile private var speedSink: SpeedSink? = null

    @Volatile var state: TransportState = TransportState.NO_MEDIA_PRESENT
        private set
    @Volatile var currentUri: String? = null
        private set
    @Volatile var currentTitle: String? = null
        private set
    @Volatile var currentMetaData: String? = null
        private set
    @Volatile var volume: Int = 100
        private set
    @Volatile var muted: Boolean = false
        private set
    @Volatile var playbackSpeed: Float = PlaybackSpeedPolicy.DEFAULT_SPEED
        private set
    @Volatile var lastError: String? = null
        private set

    /** Monotonically incremented on every [setUri] so stale async player callbacks are ignored. */
    private var generation: Long = 0L

    /** Live-only: wall-clock time spent playing, with paused intervals excluded. */
    private var elapsedAccumulatedMs = 0L
    private var elapsedRunningSinceMs: Long? = null

    /** Current media generation token; async player callbacks pass this back to guard against races. */
    fun mediaGeneration(): Long = synchronized(lock) { generation }

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

    fun setMuteSink(sink: MuteSink) {
        muteSink = sink
    }

    fun setSpeedSink(sink: SpeedSink) {
        speedSink = sink
    }

    /** SetAVTransportURI: stop anything playing, store the new item. Null URI clears. */
    fun setUri(uri: String?, metaData: String?) {
        val next = synchronized(lock) {
            if (state != TransportState.NO_MEDIA_PRESENT && state != TransportState.STOPPED) {
                port.stop()
            }
            generation++
            currentUri = uri?.takeIf { it.isNotBlank() }
            currentMetaData = currentUri?.let { metaData }
            currentTitle = currentMetaData?.let { DidlParser.extractTitle(it) }
            lastError = null
            resetPlaybackSpeedLocked()
            resetElapsedLocked()
            state = TransportState.NO_MEDIA_PRESENT
            state
        }
        notifyListeners(next)
    }

    /** Play: resumes when paused; starts stored media otherwise; idempotent. */
    fun play() {
        val next: TransportState? = synchronized(lock) {
            when (state) {
                TransportState.PAUSED_PLAYBACK -> {
                    port.play()
                    markElapsedRunningLocked()
                    state = TransportState.PLAYING
                    state
                }
                TransportState.PLAYING, TransportState.TRANSITIONING -> null
                else -> {
                    val uri = currentUri ?: return
                    lastError = null
                    port.prepareAndPlay(uri)
                    resetElapsedLocked()
                    markElapsedRunningLocked()
                    state = TransportState.TRANSITIONING
                    state
                }
            }
        }
        next?.let(::notifyListeners)
    }

    /** Pause: only meaningful while playing or already paused. */
    fun pause() {
        val next: TransportState? = synchronized(lock) {
            when (state) {
                TransportState.PLAYING, TransportState.PAUSED_PLAYBACK -> {
                    port.pause()
                    markElapsedPausedLocked()
                    state = TransportState.PAUSED_PLAYBACK
                    state
                }
                else -> null
            }
        }
        next?.let(::notifyListeners)
    }

    fun resume() {
        val next: TransportState? = synchronized(lock) {
            when (state) {
                TransportState.PAUSED_PLAYBACK -> {
                    port.play()
                    markElapsedRunningLocked()
                    state = TransportState.PLAYING
                    state
                }
                TransportState.TRANSITIONING -> null // already starting
                TransportState.PLAYING -> null
                else -> {
                    val uri = currentUri ?: return
                    lastError = null
                    port.prepareAndPlay(uri)
                    resetElapsedLocked()
                    markElapsedRunningLocked()
                    state = TransportState.TRANSITIONING
                    state
                }
            }
        }
        next?.let(::notifyListeners)
    }

    fun stop() {
        val next = synchronized(lock) {
            port.stop()
            resetElapsedLocked()
            state = TransportState.STOPPED
            state
        }
        notifyListeners(next)
    }

    fun seekTo(ms: Long) {
        synchronized(lock) {
            // A live stream has no meaningful absolute position; seeking into the
            // window would just force a rebuffer.
            if (isLive()) return
            when (state) {
                TransportState.PLAYING, TransportState.PAUSED_PLAYBACK, TransportState.TRANSITIONING ->
                    port.seekTo(ms)
                else -> Unit
            }
        }
    }

    /**
     * Cycles to the next playback speed in [PlaybackSpeedPolicy.STEPS] when VOD
     * media is active; returns the new speed, or `null` when ignored (live or idle).
     */
    fun cyclePlaybackSpeed(): Float? {
        val next = synchronized(lock) {
            if (!canAdjustSpeedLocked()) return null
            val candidate = PlaybackSpeedPolicy.nextSpeed(playbackSpeed)
            playbackSpeed = candidate
            port.setPlaybackSpeed(candidate)
            candidate
        }
        speedSink?.onSpeedChanged(next)
        return next
    }

    /**
     * Applies an explicit supported speed (e.g. from a UPnP `Play` action with
     * a non-default `Speed` argument). Ignored for live streams or unsupported values.
     */
    fun setPlaybackSpeed(speed: Float): Boolean {
        val applied = synchronized(lock) {
            if (!canAdjustSpeedLocked()) return false
            val matched = PlaybackSpeedPolicy.STEPS.firstOrNull { kotlin.math.abs(it - speed) < 0.001f }
                ?: return false
            playbackSpeed = matched
            port.setPlaybackSpeed(matched)
            matched
        }
        speedSink?.onSpeedChanged(applied)
        return true
    }

    private fun canAdjustSpeedLocked(): Boolean {
        if (isLive()) return false
        return when (state) {
            TransportState.PLAYING, TransportState.PAUSED_PLAYBACK, TransportState.TRANSITIONING -> true
            else -> false
        }
    }

    private fun resetPlaybackSpeedLocked() {
        playbackSpeed = PlaybackSpeedPolicy.DEFAULT_SPEED
        port.setPlaybackSpeed(PlaybackSpeedPolicy.DEFAULT_SPEED)
    }

    /** Player callback: media prepared and rendering; duration now known. */
    @JvmOverloads
    fun onPlayerPrepared(expectedGeneration: Long? = null) {
        val next: TransportState? = synchronized(lock) {
            if (expectedGeneration != null && expectedGeneration != generation) return
            lastError = null
            if (isLive() && playbackSpeed != PlaybackSpeedPolicy.DEFAULT_SPEED) {
                resetPlaybackSpeedLocked()
            }
            if (state == TransportState.TRANSITIONING) {
                state = TransportState.PLAYING
                state
            } else {
                null
            }
        }
        next?.let(::notifyListeners)
    }

    /** Player callback: playback reached natural end of stream. */
    @JvmOverloads
    fun onPlayerEnded(expectedGeneration: Long? = null) {
        val next: TransportState? = synchronized(lock) {
            if (expectedGeneration != null && expectedGeneration != generation) return
            if (state != TransportState.STOPPED && state != TransportState.NO_MEDIA_PRESENT) {
                port.stop()
                resetElapsedLocked()
                state = TransportState.STOPPED
                state
            } else {
                null
            }
        }
        next?.let(::notifyListeners)
    }

    /** Player callback: fatal error; park in STOPPED, keep serving control actions. */
    @JvmOverloads
    fun onPlayerError(expectedGeneration: Long? = null, reason: String? = null) {
        val next: TransportState? = synchronized(lock) {
            if (expectedGeneration != null && expectedGeneration != generation) return
            lastError = reason?.takeIf { it.isNotBlank() } ?: "PLAYBACK_ERROR"
            if (state != TransportState.STOPPED) {
                port.stop()
                resetElapsedLocked()
                state = TransportState.STOPPED
                state
            } else {
                null
            }
        }
        next?.let(::notifyListeners)
    }

    fun bufferedPositionMs(): Long = synchronized(lock) {
        when (state) {
            TransportState.PLAYING, TransportState.PAUSED_PLAYBACK, TransportState.TRANSITIONING ->
                if (isLive()) 0L else port.currentBufferedPositionMs().coerceAtLeast(0L)
            else -> 0L
        }
    }

    fun positionMs(): Long = synchronized(lock) {
        when (state) {
            TransportState.PLAYING, TransportState.PAUSED_PLAYBACK, TransportState.TRANSITIONING ->
                if (isLive()) elapsedMsLocked() else port.currentPositionMs().coerceAtLeast(0)
            else -> 0L
        }
    }

    /**
     * Duration for senders. Live streams have no programme duration — the
     * player would otherwise hand back the length of its sliding window (a
     * 15-second number that keeps resetting), which senders render as a
     * progress bar that loops. Report "unknown" instead, like other renderers.
     */
    fun durationMs(): Long = synchronized(lock) {
        when (state) {
            TransportState.PLAYING, TransportState.PAUSED_PLAYBACK, TransportState.TRANSITIONING ->
                if (isLive()) 0L else port.currentDurationMs().coerceAtLeast(0)
            else -> 0L
        }
    }

    private fun elapsedMsLocked(): Long =
        elapsedAccumulatedMs + (elapsedRunningSinceMs?.let { (clockMs() - it).coerceAtLeast(0) } ?: 0L)

    private fun markElapsedRunningLocked() {
        if (elapsedRunningSinceMs == null) elapsedRunningSinceMs = clockMs()
    }

    private fun markElapsedPausedLocked() {
        elapsedRunningSinceMs?.let { elapsedAccumulatedMs += (clockMs() - it).coerceAtLeast(0) }
        elapsedRunningSinceMs = null
    }

    private fun resetElapsedLocked() {
        elapsedAccumulatedMs = 0L
        elapsedRunningSinceMs = null
    }

    fun setVolume(percent: Int) {
        val clamped = VolumeMath.clampPercent(percent)
        synchronized(lock) {
            volume = clamped
        }
        volumeSink?.onVolumeChanged(clamped)
    }

    fun setMute(mute: Boolean) {
        synchronized(lock) {
            muted = mute
        }
        muteSink?.onMuteChanged(mute)
    }

    private fun notifyListeners(next: TransportState) {
        listeners.forEach { it.onStateChanged(next) }
    }
}
