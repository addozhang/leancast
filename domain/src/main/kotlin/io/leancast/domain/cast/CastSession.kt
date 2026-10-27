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
}

/** Notified when the UPnP-visible volume changes (0..100). */
fun interface VolumeSink {
    fun onVolumeChanged(percent: Int)
}

/**
 * The cast session: the AVTransport state machine plus current item bookkeeping.
 * Pure domain object; driven by SOAP actions and player callbacks.
 */
class CastSession(private val port: PlayerPort) {

    interface Listener {
        fun onStateChanged(state: TransportState)
    }

    private var listener: Listener? = null
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

    fun setListener(l: Listener) {
        listener = l
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
        enter(TransportState.NO_MEDIA_PRESENT)
    }

    /** Play: resumes when paused; otherwise starts the stored URI via TRANSITIONING. */
    fun play() {
        when (state) {
            TransportState.PAUSED_PLAYBACK -> {
                port.play()
                enter(TransportState.PLAYING)
            }
            else -> {
                val uri = currentUri ?: return
                port.prepareAndPlay(uri)
                enter(TransportState.TRANSITIONING)
            }
        }
    }

    /** Pause: only meaningful while playing or already paused. */
    fun pause() {
        when (state) {
            TransportState.PLAYING, TransportState.PAUSED_PLAYBACK -> {
                port.pause()
                enter(TransportState.PAUSED_PLAYBACK)
            }
            else -> Unit
        }
    }

    fun resume() {
        when (state) {
            TransportState.PAUSED_PLAYBACK -> {
                port.play()
                enter(TransportState.PLAYING)
            }
            TransportState.TRANSITIONING -> Unit // already starting
            else -> play()
        }
    }

    fun stop() {
        port.stop()
        enter(TransportState.STOPPED)
    }

    fun seekTo(ms: Long) {
        when (state) {
            TransportState.PLAYING, TransportState.PAUSED_PLAYBACK, TransportState.TRANSITIONING ->
                port.seekTo(ms)
            else -> Unit
        }
    }

    /** Player callback: media prepared and rendering; duration now known. */
    fun onPlayerPrepared() {
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
                port.currentPositionMs().coerceAtLeast(0)
            else -> 0L
        }

    fun durationMs(): Long =
        when (state) {
            TransportState.PLAYING, TransportState.PAUSED_PLAYBACK, TransportState.TRANSITIONING ->
                port.currentDurationMs().coerceAtLeast(0)
            else -> 0L
        }

    fun setVolume(percent: Int) {
        val clamped = VolumeMath.clampPercent(percent)
        volume = clamped
        volumeSink?.onVolumeChanged(clamped)
    }

    private fun enter(next: TransportState) {
        state = next
        listener?.onStateChanged(next)
    }
}
