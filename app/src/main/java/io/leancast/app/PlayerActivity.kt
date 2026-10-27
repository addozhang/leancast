package io.leancast.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.PlayerView
import io.leancast.domain.cast.CastSession
import io.leancast.domain.cast.TransportState

/** Fullscreen playback surface driven by the cast session; TV remote controlled. */
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView

    private val finishOnEnd = CastSession.Listener { state ->
        runOnUiThread {
            if (state == TransportState.STOPPED || state == TransportState.NO_MEDIA_PRESENT) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = PlayerView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            useController = false
        }
        setContentView(playerView)
        CastBus.session?.addListener(finishOnEnd)
    }

    override fun onDestroy() {
        CastBus.session?.removeListener(finishOnEnd)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        playerView.player = CastBus.port?.currentPlayer
    }

    override fun onStop() {
        playerView.player = null
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val session = CastBus.session ?: return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (session.state == TransportState.PLAYING) session.pause() else session.resume()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                session.seekTo(session.positionMs() + SEEK_STEP_MS)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                session.seekTo((session.positionMs() - SEEK_STEP_MS).coerceAtLeast(0))
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                session.stop()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    companion object {
        private const val SEEK_STEP_MS = 10_000L
    }
}
