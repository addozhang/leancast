package io.leancast.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.leancast.domain.cast.CastSession
import io.leancast.domain.cast.PlayerPort

/**
 * [PlayerPort] backed by ExoPlayer. All player access is marshalled to the main
 * thread; position/duration are cached by a main-thread poller so HTTP threads
 * can read them synchronously.
 */
class ExoPlayerPort(private val context: Context) : PlayerPort {

    var session: CastSession? = null

    @Volatile private var cachedPositionMs = 0L
    @Volatile private var cachedDurationMs = 0L

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
        main.post {
            val p = obtain()
            val mime = when {
                uri.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ->
                    MimeTypes.APPLICATION_M3U8
                uri.substringBefore('?').endsWith(".mpd", ignoreCase = true) ->
                    MimeTypes.APPLICATION_MPD
                else -> null
            }
            p.setMediaItem(MediaItem.Builder().setUri(uri).setMimeType(mime).build())
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
        player ?: ExoPlayer.Builder(context).build().also { created ->
            created.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) session?.onPlayerPrepared()
                    if (playbackState == Player.STATE_ENDED) session?.stop()
                }

                override fun onPlayerError(error: PlaybackException) {
                    session?.onPlayerError()
                }
            })
            player = created
            cachedPositionMs = 0
            cachedDurationMs = 0
            main.post(poller)
        }

    companion object {
        private const val POLL_MS = 500L
    }
}
