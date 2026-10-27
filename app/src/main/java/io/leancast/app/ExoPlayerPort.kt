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

    var session: CastSession? = null

    @Volatile private var cachedPositionMs = 0L
    @Volatile private var cachedDurationMs = 0L
    @Volatile private var lastUri: String? = null

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private val poller = object : Runnable {
        override fun run() {
            player?.let { p ->
                cachedPositionMs = p.currentPosition.coerceAtLeast(0)
                cachedDurationMs =
                    if (p.duration > 0) p.duration else 0L
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    val currentPlayer: Player? get() = player

    override fun prepareAndPlay(uri: String) {
        lastUri = uri
        main.post {
            val p = obtain()
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
            p.prepare()
            p.playWhenReady = true
        }
    }

    override fun play() {
        main.post { player?.play() }
    }

    override fun pause() {
        main.post { player?.pause() }
    }

    override fun seekTo(ms: Long) {
        main.post { player?.seekTo(ms) }
    }

    override fun stop() {
        main.post { player?.stop() }
    }

    override fun currentPositionMs(): Long = cachedPositionMs
    override fun currentDurationMs(): Long = cachedDurationMs

    fun release() {
        main.post {
            player?.release()
            player = null
        }
        main.removeCallbacks(poller)
    }

    private fun obtain(): ExoPlayer =
        player ?: buildPlayer().also { created ->
            player = created
            cachedPositionMs = 0
            cachedDurationMs = 0
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
                        if (playbackState == Player.STATE_READY) session?.onPlayerPrepared()
                        if (playbackState == Player.STATE_ENDED) session?.stop()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(
                            TAG,
                            "playback error ${error.errorCodeName} uri=$lastUri",
                            error,
                        )
                        session?.onPlayerError()
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
