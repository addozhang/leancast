package io.leancast.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.PlayerView
import io.leancast.domain.cast.CastSession
import io.leancast.domain.cast.TransportState

/**
 * Fullscreen playback surface driven by the cast session.
 * HUD = PlayerView controller (seekbar/times/play-pause) + transient center
 * indicator on pause/resume/seek. TV remote controlled.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var centerIndicator: ImageView
    private val ui = Handler(Looper.getMainLooper())
    private val hideIndicator = Runnable { centerIndicator.visibility = ImageView.GONE }

    private val finishOnEnd = CastSession.Listener { state ->
        runOnUiThread {
            when (state) {
                TransportState.STOPPED, TransportState.NO_MEDIA_PRESENT -> finish()
                TransportState.PAUSED_PLAYBACK -> showIndicator(android.R.drawable.ic_media_pause)
                TransportState.PLAYING -> showIndicator(android.R.drawable.ic_media_play)
                else -> Unit
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            useController = true
            controllerShowTimeoutMs = CONTROLLER_TIMEOUT_MS
            controllerAutoShow = true
        }
        val indicatorSize = (resources.displayMetrics.density * INDICATOR_DP).toInt()
        centerIndicator = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(indicatorSize, indicatorSize, Gravity.CENTER)
            visibility = ImageView.GONE
            alpha = 0.85f
            setImageResource(android.R.drawable.ic_media_play)
        }
        setContentView(FrameLayout(this).apply {
            addView(playerView)
            addView(centerIndicator)
        })

        CastBus.session?.addListener(finishOnEnd)
    }

    override fun onDestroy() {
        CastBus.session?.removeListener(finishOnEnd)
        ui.removeCallbacks(hideIndicator)
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
                if (session.state == TransportState.PLAYING) session.pause()
                else session.resume()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                session.seekTo(session.positionMs() + SEEK_STEP_MS)
                showIndicator(android.R.drawable.ic_media_ff)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                session.seekTo((session.positionMs() - SEEK_STEP_MS).coerceAtLeast(0))
                showIndicator(android.R.drawable.ic_media_rew)
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                session.stop()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun showIndicator(iconRes: Int) {
        centerIndicator.setImageResource(iconRes)
        centerIndicator.visibility = ImageView.VISIBLE
        ui.removeCallbacks(hideIndicator)
        ui.postDelayed(hideIndicator, INDICATOR_VISIBLE_MS)
    }

    companion object {
        private const val SEEK_STEP_MS = 10_000L
        private const val CONTROLLER_TIMEOUT_MS = 2_500
        private const val INDICATOR_DP = 96
        private const val INDICATOR_VISIBLE_MS = 1_200L
    }
}
