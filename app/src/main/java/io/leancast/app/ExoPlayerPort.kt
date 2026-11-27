package io.leancast.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.leancast.domain.cast.CastSession
import io.leancast.domain.cast.MediaHttpHeaders
import io.leancast.domain.cast.PlayerPort
import io.leancast.domain.upnp.MimeInference

/**
 * [PlayerPort] backed by ExoPlayer. All player access is marshalled to the main
 * thread; position/duration are cached by a main-thread poller so HTTP threads
 * can read them synchronously.
 */
class ExoPlayerPort(private val context: Context) : PlayerPort {

    @Volatile var session: CastSession? = null

    @Volatile private var cachedPositionMs = 0L
    @Volatile private var cachedDurationMs = 0L
    @Volatile private var cachedIsLive = false
    @Volatile private var lastUri: String? = null
    /** Latest generation requested from any thread; skips superseded queued prepares. */
    @Volatile private var latestRequestedGeneration: Long = -1L
    /** Generation actually bound to ExoPlayer on the main thread; stamped onto Player.Listener callbacks. */
    private var boundPlayerGeneration: Long = -1L
    @Volatile private var released = false

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var player: ExoPlayer? = null
    private val poller = object : Runnable {
        override fun run() {
            val p = player
            if (released || p == null) return
            refreshCacheFrom(p)
            main.postDelayed(this, POLL_MS)
        }
    }

    val currentPlayer: Player? get() = player

    override fun prepareAndPlay(uri: String) {
        if (released) return
        val gen = session?.mediaGeneration() ?: 0L
        latestRequestedGeneration = gen
        lastUri = uri
        // Clear previous item's cached metrics immediately so senders/OSD never
        // observe stale duration or live-stream flags while the new item prepares.
        cachedPositionMs = 0L
        cachedDurationMs = 0L
        cachedIsLive = false
        main.post {
            if (released || gen != latestRequestedGeneration) return@post
            val p = obtain()
            boundPlayerGeneration = gen
            val mime = MimeInference.guessMimeType(uri)
            val item = MediaItem.Builder().setUri(uri).setMimeType(mime).build()
            // Route explicitly: DefaultMediaSourceFactory's inference misses
            // CN CDNs whose manifests hide "m3u8" mid-path (youku et al.).
            val source = when (mime) {
                MimeInference.HLS ->
                    androidx.media3.exoplayer.hls.HlsMediaSource
                        .Factory(dataSources()).createMediaSource(item)
                else ->
                    androidx.media3.exoplayer.source.ProgressiveMediaSource
                        .Factory(dataSources()).createMediaSource(item)
            }
            p.setMediaSource(source)
            p.setPlaybackSpeed(session?.playbackSpeed ?: 1.0f)
            p.prepare()
            p.playWhenReady = true
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        main.post { if (!released) player?.setPlaybackSpeed(speed) }
    }

    override fun play() {
        main.post { if (!released) player?.play() }
    }

    override fun pause() {
        main.post { if (!released) player?.pause() }
    }

    override fun seekTo(ms: Long) {
        main.post { if (!released) player?.seekTo(ms) }
    }

    override fun stop() {
        main.post {
            if (!released) {
                boundPlayerGeneration = -1L
                player?.stop()
            }
        }
    }

    override fun currentPositionMs(): Long = cachedPositionMs
    override fun currentDurationMs(): Long = cachedDurationMs
    override fun isLive(): Boolean = cachedIsLive

    fun release() {
        released = true
        main.removeCallbacks(poller)
        main.post {
            main.removeCallbacks(poller)
            boundPlayerGeneration = -1L
            player?.release()
            player = null
        }
    }

    private fun refreshCacheFrom(p: ExoPlayer) {
        cachedPositionMs = p.currentPosition.coerceAtLeast(0)
        cachedDurationMs = if (p.duration > 0) p.duration else 0L
        // Live items report a sliding-window duration; the session turns
        // that into "unknown" instead of passing the window size on.
        cachedIsLive = p.isCurrentMediaItemLive
    }

    private fun obtain(): ExoPlayer =
        player ?: buildPlayer().also { created ->
            player = created
            cachedPositionMs = 0L
            cachedDurationMs = 0L
            cachedIsLive = false
            main.removeCallbacks(poller)
            main.post(poller)
        }

    /**
     * CN video CDNs frequently filter non-browser user agents and redirect
     * across protocols; wire both into the HTTP stack (see MediaHttpHeaders).
     */
    private fun dataSources(): DefaultDataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(MediaHttpHeaders.USER_AGENT)
            .setDefaultRequestProperties(mapOf("Accept" to "*/*"))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
        return DefaultDataSource.Factory(context, http)
    }

    private fun buildPlayer(): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources()))
            .build()
            .also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val gen = boundPlayerGeneration
                        if (gen < 0L) return
                        if (playbackState == Player.STATE_READY) {
                            refreshCacheFrom(p)
                            session?.onPlayerPrepared(gen)
                        }
                        if (playbackState == Player.STATE_ENDED) {
                            session?.onPlayerEnded(gen)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val gen = boundPlayerGeneration
                        Log.e(
                            TAG,
                            "playback error ${error.errorCodeName} uri=$lastUri",
                            error,
                        )
                        if (gen >= 0L) session?.onPlayerError(gen)
                    }

                    // Field diagnostics for "audio but no video" reports:
                    // tells apart codec-unsupported (no selected video track)
                    // from surface/renderer issues (track selected, no picture).
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        tracks.groups
                            .filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
                            .forEach { group ->
                                val selected = (0 until group.length)
                                    .map { group.getTrackFormat(it) to group.isTrackSelected(it) }
                                    .filter { it.second }
                                    .joinToString { "${it.first.sampleMimeType ?: "?"} ${it.first.width}x${it.first.height}" }
                                Log.i(
                                    TAG,
                                    "video track selected=[$selected] supported=${group.isSupported} size=${group.length}",
                                )
                            }
                    }
                })
            }
    }

    companion object {
        private const val TAG = "LeanCastPlayer"
        private const val POLL_MS = 500L
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000
    }
}
