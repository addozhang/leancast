package io.leancast.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.PlayerView
import io.leancast.domain.cast.CastSession
import io.leancast.domain.cast.OsdFormatter
import io.leancast.domain.cast.SeekStepPolicy
import io.leancast.domain.cast.TransportState

/**
 * Fullscreen playback surface with a dedicated 10-foot Android TV OSD:
 * - Top gradient bar: live status pill + video title
 * - Center glassmorphic pill: state (`⏸ Paused` / `▶ Playing`) or signed seek
 *   delta + target timestamp (`⏩ +30s → 01:45`)
 * - Bottom gradient bar: elapsed/total time, high-contrast progress bar, and D-pad legend
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var topTitleView: TextView
    private lateinit var topBadgeView: TextView
    private lateinit var centerPill: LinearLayout
    private lateinit var centerPillTitle: TextView
    private lateinit var centerPillSub: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var elapsedView: TextView
    private lateinit var durationView: TextView
    private lateinit var progressBar: ProgressBar

    private val ui = Handler(Looper.getMainLooper())
    private val hideCenterPill = Runnable {
        if (CastBus.session?.state == TransportState.PLAYING) {
            centerPill.animate().alpha(0f).setDuration(200).withEndAction {
                centerPill.visibility = View.GONE
            }.start()
        }
    }
    private val hideBars = Runnable {
        if (CastBus.session?.state == TransportState.PLAYING) {
            topBar.animate().alpha(0f).setDuration(250).withEndAction {
                topBar.visibility = View.GONE
            }.start()
            bottomBar.animate().alpha(0f).setDuration(250).withEndAction {
                bottomBar.visibility = View.GONE
            }.start()
        }
    }
    private val tickProgress = object : Runnable {
        override fun run() {
            refreshProgressUi()
            ui.postDelayed(this, 250L)
        }
    }

    private val sessionListener = CastSession.Listener { state ->
        runOnUiThread {
            when (state) {
                TransportState.STOPPED, TransportState.NO_MEDIA_PRESENT -> finish()
                TransportState.PAUSED_PLAYBACK -> {
                    showOsdBars(persistent = true)
                    showCenterMessage(
                        mainText = getString(R.string.osd_paused),
                        subText = currentClockSummary(),
                        persistent = true,
                    )
                }
                TransportState.PLAYING -> {
                    pendingSeekMs = -1L
                    showOsdBars(persistent = false)
                    showCenterMessage(
                        mainText = getString(R.string.osd_playing),
                        subText = currentClockSummary(),
                        persistent = false,
                    )
                }
                TransportState.TRANSITIONING -> {
                    showOsdBars(persistent = true)
                    showCenterMessage(
                        mainText = getString(R.string.osd_loading),
                        subText = CastBus.session?.currentTitle ?: "",
                        persistent = true,
                    )
                }
            }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(Settings.wrapLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // Disable Media3's touch-centric controller; we render a dedicated TV OSD.
            useController = false
            isFocusable = false
        }
        root.addView(playerView)

        topBar = buildTopBar()
        centerPill = buildCenterPill()
        bottomBar = buildBottomBar()

        root.addView(
            topBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )
        root.addView(
            centerPill,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        root.addView(
            bottomBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        setContentView(root)
        CastBus.session?.addListener(sessionListener)
        showOsdBars(persistent = false)
    }

    override fun onDestroy() {
        CastBus.session?.removeListener(sessionListener)
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        playerView.player = CastBus.port?.currentPlayer
        ui.post(tickProgress)
    }

    override fun onStop() {
        ui.removeCallbacks(tickProgress)
        playerView.player = null
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val session = CastBus.session ?: return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (session.state == TransportState.PLAYING) session.pause()
                else session.resume()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                showOsdBars(persistent = session.state != TransportState.PLAYING)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(SeekStepPolicy.stepFor(event?.repeatCount ?: 0, forward = true))
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(SeekStepPolicy.stepFor(event?.repeatCount ?: 0, forward = false))
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                session.stop()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /**
     * Seeks relative to the freshest known position. Key repeats arrive faster
     * than the 500 ms position cache updates, so consecutive seeks accumulate
     * on the last requested target instead of the stale cache.
     */
    @Volatile private var pendingSeekMs: Long = -1L

    private fun seekBy(deltaMs: Long) {
        val session = CastBus.session ?: return
        val cached = session.positionMs()
        val duration = session.durationMs()
        val base = if (pendingSeekMs >= 0L) pendingSeekMs else cached
        val unclamped = (base + deltaMs).coerceAtLeast(0L)
        val target = if (duration > 0L) unclamped.coerceAtMost(duration) else unclamped
        pendingSeekMs = target
        session.seekTo(target)

        val arrow = if (deltaMs >= 0) "⏩" else "⏪"
        val deltaText = OsdFormatter.formatSeekDelta(deltaMs)
        val targetClock = OsdFormatter.formatClock(target)
        val durClock = if (duration > 0L) " / ${OsdFormatter.formatClock(duration)}" else ""

        showOsdBars(persistent = session.state != TransportState.PLAYING)
        showCenterMessage(
            mainText = "$arrow  $deltaText",
            subText = "$targetClock$durClock",
            persistent = false,
        )
        refreshProgressUi(overridePosMs = target)
    }

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#CC050811"), Color.parseColor("#00050811")),
            )
            setPadding(dp(48), dp(28), dp(48), dp(36))
        }

        topBadgeView = TextView(this).apply {
            text = "DLNA CAST"
            setTextColor(Color.parseColor("#082F49"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#38BDF8"))
            }
        }
        bar.addView(topBadgeView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(14) })

        topTitleView = TextView(this).apply {
            text = CastBus.session?.currentTitle ?: getString(R.string.app_name)
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        bar.addView(topTitleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return bar
    }

    private fun buildCenterPill(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(32), dp(20), dp(32), dp(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#D90F172A"))
                setStroke(dp(2), Color.parseColor("#38BDF8"))
            }
        }
        centerPillTitle = TextView(this).apply {
            setTextColor(Color.parseColor("#FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        centerPillSub = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        box.addView(centerPillTitle)
        box.addView(centerPillSub, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })
        return box
    }

    private fun buildBottomBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.parseColor("#E6050811"), Color.parseColor("#99050811"), Color.parseColor("#00050811")),
            )
            setPadding(dp(48), dp(40), dp(48), dp(32))
        }

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        elapsedView = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.MONOSPACE
        }
        durationView = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.MONOSPACE
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressDrawable = buildProgressDrawable()
        }

        timeRow.addView(elapsedView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(16) })
        timeRow.addView(progressBar, LinearLayout.LayoutParams(0, dp(8), 1f))
        timeRow.addView(durationView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(16) })

        bar.addView(timeRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val legend = TextView(this).apply {
            text = getString(R.string.osd_legend)
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        bar.addView(legend, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })

        return bar
    }

    private fun buildProgressDrawable(): LayerDrawable {
        val track = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(99).toFloat()
            setColor(Color.parseColor("#334155"))
        }
        val fill = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#38BDF8"), Color.parseColor("#60A5FA")),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(99).toFloat()
        }
        val clip = ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL)
        return LayerDrawable(arrayOf(track, clip)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun refreshProgressUi(overridePosMs: Long? = null) {
        val session = CastBus.session ?: return
        val pos = overridePosMs ?: if (pendingSeekMs >= 0L) pendingSeekMs else session.positionMs()
        val dur = session.durationMs()
        if (pendingSeekMs >= 0L && kotlin.math.abs(session.positionMs() - pendingSeekMs) < 1_500L) {
            pendingSeekMs = -1L
        }
        topTitleView.text = session.currentTitle?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        elapsedView.text = OsdFormatter.formatClock(pos)
        durationView.text = OsdFormatter.formatClock(dur)
        progressBar.progress = OsdFormatter.progressPermille(pos, dur)
    }

    private fun currentClockSummary(): String {
        val session = CastBus.session ?: return ""
        val pos = OsdFormatter.formatClock(session.positionMs())
        val dur = OsdFormatter.formatClock(session.durationMs())
        return "$pos / $dur"
    }

    private fun showOsdBars(persistent: Boolean) {
        topBar.animate().cancel()
        bottomBar.animate().cancel()
        topBar.alpha = 1f
        bottomBar.alpha = 1f
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        refreshProgressUi()
        ui.removeCallbacks(hideBars)
        if (!persistent) {
            ui.postDelayed(hideBars, BAR_TIMEOUT_MS)
        }
    }

    private fun showCenterMessage(mainText: String, subText: String, persistent: Boolean) {
        centerPillTitle.text = mainText
        centerPillSub.text = subText
        centerPillSub.visibility = if (subText.isBlank()) View.GONE else View.VISIBLE
        centerPill.animate().cancel()
        centerPill.alpha = 1f
        centerPill.visibility = View.VISIBLE
        ui.removeCallbacks(hideCenterPill)
        if (!persistent) {
            ui.postDelayed(hideCenterPill, PILL_TIMEOUT_MS)
        }
    }

    private fun dp(value: Int): Int =
        (resources.displayMetrics.density * value).toInt()

    companion object {
        private const val BAR_TIMEOUT_MS = 3_000L
        private const val PILL_TIMEOUT_MS = 1_300L
    }
}
