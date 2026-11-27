package io.leancast.app

import android.content.res.ColorStateList
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.PlayerView
import io.leancast.domain.cast.CastSession
import io.leancast.domain.cast.OsdFormatter
import io.leancast.domain.cast.PendingSeekTracker
import io.leancast.domain.cast.SeekStepPolicy
import io.leancast.domain.cast.TransportState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fullscreen playback surface with a calm 10-foot Android TV OSD:
 * - Top gradient scrim: video title + dynamic monochrome-vector status badges
 *   (LIVE, Paused, non-1x Speed, Muted) + living-room wall clock (`HH:mm`).
 * - Unobstructed Pause: pausing keeps the center frame & subtitles completely
 *   clear, indicating pause via the top-right badge and bottom timeline icon.
 * - Transient glass pill: pure monochrome vector icons (zero system emojis)
 *   for brief Seek / Speed / Resume feedback and Buffering spinner.
 * - Bottom high-contrast scrim: dual-layer progress bar (buffered + played +
 *   glowing thumb), live watched counter, and keycap D-pad legend.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var topLiveBadge: LinearLayout
    private lateinit var topTitleView: TextView
    private lateinit var topPausedBadge: LinearLayout
    private lateinit var topSpeedBadge: TextView
    private lateinit var topMuteBadge: LinearLayout
    private lateinit var topClockView: TextView

    private lateinit var centerPill: LinearLayout
    private lateinit var centerIcon: ImageView
    private lateinit var centerSpinner: ProgressBar
    private lateinit var centerPillTitle: TextView
    private lateinit var centerPillSub: TextView

    private lateinit var bottomBar: LinearLayout
    private lateinit var vodTimelineRow: LinearLayout
    private lateinit var vodStateIcon: ImageView
    private lateinit var elapsedView: TextView
    private lateinit var durationView: TextView
    private lateinit var progressTrackContainer: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressThumb: View

    private lateinit var liveTimelineRow: LinearLayout
    private lateinit var liveStateIcon: ImageView
    private lateinit var liveWatchedView: TextView

    private lateinit var legendVodRow: LinearLayout
    private lateinit var legendLiveRow: LinearLayout

    private val clockFormat = SimpleDateFormat("HH:mm", Locale.US)
    private val ui = Handler(Looper.getMainLooper())
    private val hideCenterPill = Runnable {
        if (CastBus.session?.state != TransportState.TRANSITIONING) {
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

    /** Leaves the player only if no new cast materialised within the grace window. */
    private val finishIfStillIdle = Runnable {
        when (CastBus.session?.state) {
            null, TransportState.NO_MEDIA_PRESENT -> finish()
            else -> Unit
        }
    }

    private val sessionListener = CastSession.Listener { state ->
        runOnUiThread { applyTransportState(state, fromLiveTransition = true) }
    }

    private fun applyTransportState(state: TransportState?, fromLiveTransition: Boolean) {
        when (state) {
            // A sender switching source does SetAVTransportURI + Play back to
            // back, so NO_MEDIA_PRESENT is transient. Finishing straight away
            // tore this activity down while audio kept playing.
            null, TransportState.STOPPED -> finish()
            TransportState.NO_MEDIA_PRESENT -> scheduleIdleFinish()
            TransportState.PAUSED_PLAYBACK -> {
                ui.removeCallbacks(finishIfStillIdle)
                attachPlayerIfMissing()
                hideCenterPillImmediately() // Unobstructed center frame while paused!
                showOsdBars(persistent = true)
            }
            TransportState.PLAYING -> {
                seekTracker.clear()
                ui.removeCallbacks(finishIfStillIdle)
                attachPlayerIfMissing()
                showOsdBars(persistent = false)
                if (fromLiveTransition) {
                    showCenterMessage(
                        iconRes = R.drawable.ic_osd_play,
                        mainText = getString(R.string.osd_playing),
                        subText = currentClockSummary(),
                        persistent = false,
                    )
                } else {
                    hideCenterPillImmediately()
                }
            }
            TransportState.TRANSITIONING -> {
                seekTracker.clear()
                ui.removeCallbacks(finishIfStillIdle)
                attachPlayerIfMissing()
                showOsdBars(persistent = true)
                showBufferingMessage(CastBus.session?.currentTitle ?: "")
            }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(Settings.wrapLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
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
    }

    override fun onDestroy() {
        CastBus.session?.removeListener(sessionListener)
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        CastBus.playerVisible = true
        attachPlayerIfMissing()
        applyTransportState(CastBus.session?.state, fromLiveTransition = false)
        ui.post(tickProgress)
    }

    /**
     * Surface-race guard: on fast TVs the player can be created after this
     * activity started, leaving the video with no surface (audio only, no
     * error). Re-attach whenever we notice the mismatch.
     */
    private fun attachPlayerIfMissing() {
        if (playerView.player == null) {
            playerView.player = CastBus.port?.currentPlayer
        }
    }

    /** Defers the teardown so an immediate re-cast can keep this activity alive. */
    private fun scheduleIdleFinish() {
        ui.removeCallbacks(finishIfStillIdle)
        ui.postDelayed(finishIfStillIdle, SWITCH_GRACE_MS)
    }

    override fun onStop() {
        CastBus.playerVisible = false
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
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                showOsdBars(persistent = session.state != TransportState.PLAYING)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if ((event?.repeatCount ?: 0) == 0 && !session.isLive()) {
                    session.cyclePlaybackSpeed()?.let { speed ->
                        showOsdBars(persistent = session.state != TransportState.PLAYING)
                        showCenterMessage(
                            iconRes = R.drawable.ic_osd_speed,
                            mainText = OsdFormatter.formatSpeed(speed),
                            subText = getString(R.string.osd_speed_label),
                            persistent = false,
                        )
                    }
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (session.isLive()) true
                else {
                    seekBy(SeekStepPolicy.stepFor(event?.repeatCount ?: 0, forward = true))
                    true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (session.isLive()) true
                else {
                    seekBy(SeekStepPolicy.stepFor(event?.repeatCount ?: 0, forward = false))
                    true
                }
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
     * on the last requested target instead of the stale cache, while expiring
     * automatically if paused or snapped to a distant keyframe.
     */
    private val seekTracker = PendingSeekTracker()

    private fun seekBy(deltaMs: Long) {
        val session = CastBus.session ?: return
        val cached = session.positionMs()
        val duration = session.durationMs()
        val base = seekTracker.basePosition(cached)
        val unclamped = (base + deltaMs).coerceAtLeast(0L)
        val target = if (duration > 0L) unclamped.coerceAtMost(duration) else unclamped
        seekTracker.recordSeek(target)
        session.seekTo(target)

        val deltaText = OsdFormatter.formatSeekDelta(deltaMs)
        val targetClock = OsdFormatter.formatClock(target)
        val durClock = if (duration > 0L) " / ${OsdFormatter.formatClock(duration)}" else ""

        showOsdBars(persistent = session.state != TransportState.PLAYING)
        showCenterMessage(
            iconRes = if (deltaMs >= 0) R.drawable.ic_osd_ff else R.drawable.ic_osd_rw,
            mainText = deltaText,
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
                intArrayOf(
                    Color.parseColor("#E004070E"),
                    Color.parseColor("#7304070E"),
                    Color.parseColor("#0004070E"),
                ),
            )
            setPadding(dp(44), dp(26), dp(44), dp(38))
        }

        // Left: Optional LIVE badge + Video Title
        topLiveBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = pillDrawable("#40EF4444", "#80F87171")
        }
        topLiveBadge.addView(View(this).apply {
            background = pillDrawable("#F87171", null)
        }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(6) })
        topLiveBadge.addView(TextView(this).apply {
            text = getString(R.string.osd_live)
            setTextColor(Color.parseColor("#FEE2E2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
        })
        bar.addView(topLiveBadge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(12) })

        topTitleView = TextView(this).apply {
            text = CastBus.session?.currentTitle ?: getString(R.string.app_name)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setShadowLayer(4f, 0f, 1f, Color.parseColor("#80000000"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        bar.addView(topTitleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(16)
        })

        // Right: Dynamic Status Badges (Paused, non-1x Speed, Muted) + Living-Room Clock
        topPausedBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = pillDrawable("#40F59E0B", "#80FBBF24")
        }
        topPausedBadge.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_osd_pause)
            setColorFilter(Color.parseColor("#FDE68A"))
        }, LinearLayout.LayoutParams(dp(13), dp(13)).apply { rightMargin = dp(5) })
        topPausedBadge.addView(TextView(this).apply {
            text = getString(R.string.osd_paused)
            setTextColor(Color.parseColor("#FDE68A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        })
        bar.addView(topPausedBadge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(8) })

        topSpeedBadge = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#BAE6FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = pillDrawable("#4038BDF8", "#8038BDF8")
        }
        bar.addView(topSpeedBadge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(8) })

        topMuteBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = pillDrawable("#BF0F172A", "#33FFFFFF")
        }
        topMuteBadge.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_osd_mute)
            setColorFilter(Color.parseColor("#E2E8F0"))
        }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(5) })
        topMuteBadge.addView(TextView(this).apply {
            text = getString(R.string.osd_muted)
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        })
        bar.addView(topMuteBadge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(8) })

        topClockView = TextView(this).apply {
            text = clockFormat.format(Date())
            setTextColor(Color.parseColor("#E6F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.MONOSPACE
            setShadowLayer(4f, 0f, 1f, Color.parseColor("#80000000"))
        }
        bar.addView(topClockView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(4) })

        return bar
    }

    private fun buildCenterPill(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(26), dp(16), dp(28), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#D60A0F1C"))
                setStroke(dp(1), Color.parseColor("#24FFFFFF"))
            }
        }
        centerIcon = ImageView(this).apply {
            setColorFilter(Color.parseColor("#38BDF8"))
        }
        box.addView(centerIcon, LinearLayout.LayoutParams(dp(26), dp(26)).apply {
            rightMargin = dp(14)
        })
        centerSpinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
            visibility = View.GONE
        }
        box.addView(centerSpinner, LinearLayout.LayoutParams(dp(26), dp(26)).apply {
            rightMargin = dp(14)
        })

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        centerPillTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        centerPillSub = TextView(this).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.MONOSPACE
        }
        textCol.addView(centerPillTitle)
        textCol.addView(centerPillSub, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(2) })
        box.addView(textCol)
        return box
    }

    private fun buildBottomBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(
                    Color.parseColor("#F204070E"),
                    Color.parseColor("#B804070E"),
                    Color.parseColor("#0004070E"),
                ),
            )
            setPadding(dp(44), dp(46), dp(44), dp(28))
        }

        // VOD timeline row: State vector icon + Elapsed + Dual-layer Progress + Thumb + Duration
        vodTimelineRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        vodStateIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_osd_play)
            setColorFilter(Color.WHITE)
        }
        vodTimelineRow.addView(vodStateIcon, LinearLayout.LayoutParams(dp(20), dp(20)).apply {
            rightMargin = dp(12)
        })

        elapsedView = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        vodTimelineRow.addView(elapsedView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(14) })

        progressTrackContainer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateThumbPosition(progressBar.progress)
            }
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            secondaryProgress = 0
            progressDrawable = buildProgressDrawable()
        }
        progressTrackContainer.addView(
            progressBar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7), Gravity.CENTER_VERTICAL),
        )
        progressThumb = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(2), Color.parseColor("#38BDF8"))
            }
        }
        progressTrackContainer.addView(
            progressThumb,
            FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER_VERTICAL or Gravity.START),
        )
        vodTimelineRow.addView(progressTrackContainer, LinearLayout.LayoutParams(0, dp(20), 1f))

        durationView = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.MONOSPACE
        }
        vodTimelineRow.addView(durationView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(14) })

        bar.addView(vodTimelineRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // Live timeline row: State vector icon + Watched MM:SS + right LIVE label
        liveTimelineRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        liveStateIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_osd_play)
            setColorFilter(Color.parseColor("#38BDF8"))
        }
        liveTimelineRow.addView(liveStateIcon, LinearLayout.LayoutParams(dp(20), dp(20)).apply {
            rightMargin = dp(10)
        })
        liveWatchedView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE
        }
        liveTimelineRow.addView(liveWatchedView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        liveTimelineRow.addView(TextView(this).apply {
            text = "● ${getString(R.string.osd_live)}"
            setTextColor(Color.parseColor("#FCA5A5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        })
        bar.addView(liveTimelineRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // Keycap Legend Rows (VOD vs Live)
        legendVodRow = buildKeycapLegend(
            listOf(
                getString(R.string.key_ok) to getString(R.string.legend_play_pause),
                getString(R.string.key_seek) to getString(R.string.legend_seek),
                getString(R.string.key_speed) to getString(R.string.legend_speed),
                getString(R.string.key_back) to getString(R.string.legend_exit),
            ),
        )
        legendLiveRow = buildKeycapLegend(
            listOf(
                getString(R.string.key_ok) to getString(R.string.legend_play_pause),
                getString(R.string.key_back) to getString(R.string.legend_exit),
            ),
        ).apply { visibility = View.GONE }

        bar.addView(legendVodRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) })
        bar.addView(legendLiveRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) })

        return bar
    }

    private fun buildKeycapLegend(items: List<Pair<String, String>>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        items.forEachIndexed { idx, (keyLabel, desc) ->
            val group = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            group.addView(TextView(this).apply {
                text = keyLabel
                setTextColor(Color.parseColor("#F1F5F9"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(dp(7), dp(2), dp(7), dp(2))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(5).toFloat()
                    setColor(Color.parseColor("#24FFFFFF"))
                    setStroke(dp(1), Color.parseColor("#33FFFFFF"))
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(6)
            })
            group.addView(TextView(this).apply {
                text = desc
                setTextColor(Color.parseColor("#E6E2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            })
            row.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (idx < items.lastIndex) rightMargin = dp(20)
            })
        }
        return row
    }

    private fun buildProgressDrawable(): LayerDrawable {
        val track = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(99).toFloat()
            setColor(Color.parseColor("#33FFFFFF"))
        }
        val buffered = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(99).toFloat()
            setColor(Color.parseColor("#59FFFFFF"))
        }
        val fill = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#38BDF8"), Color.parseColor("#60A5FA")),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(99).toFloat()
        }
        val bufferedClip = ClipDrawable(buffered, Gravity.LEFT, ClipDrawable.HORIZONTAL)
        val fillClip = ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL)
        return LayerDrawable(arrayOf(track, bufferedClip, fillClip)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.secondaryProgress)
            setId(2, android.R.id.progress)
        }
    }

    private fun refreshProgressUi(overridePosMs: Long? = null) {
        val session = CastBus.session ?: return
        val live = session.isLive()
        val paused = session.state == TransportState.PAUSED_PLAYBACK
        val pos = overridePosMs ?: seekTracker.resolveDisplayPosition(session.positionMs())
        val dur = session.durationMs()
        val buffered = session.bufferedPositionMs()
        val speed = session.playbackSpeed

        topTitleView.text = session.currentTitle?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        topClockView.text = clockFormat.format(Date())
        topLiveBadge.visibility = if (live) View.VISIBLE else View.GONE
        topPausedBadge.visibility = if (paused) View.VISIBLE else View.GONE
        topMuteBadge.visibility = if (session.muted) View.VISIBLE else View.GONE

        if (!live && kotlin.math.abs(speed - 1.0f) > 0.01f) {
            topSpeedBadge.text = OsdFormatter.formatSpeed(speed)
            topSpeedBadge.visibility = View.VISIBLE
        } else {
            topSpeedBadge.visibility = View.GONE
        }

        val stateIconRes = if (paused) R.drawable.ic_osd_pause else R.drawable.ic_osd_play
        vodStateIcon.setImageResource(stateIconRes)
        liveStateIcon.setImageResource(stateIconRes)

        if (live) {
            vodTimelineRow.visibility = View.GONE
            liveTimelineRow.visibility = View.VISIBLE
            legendVodRow.visibility = View.GONE
            legendLiveRow.visibility = View.VISIBLE
            liveWatchedView.text = "${getString(R.string.osd_watched)}  ${OsdFormatter.formatClock(pos)}"
        } else {
            vodTimelineRow.visibility = View.VISIBLE
            liveTimelineRow.visibility = View.GONE
            legendVodRow.visibility = View.VISIBLE
            legendLiveRow.visibility = View.GONE
            elapsedView.text = OsdFormatter.formatClock(pos)
            durationView.text = OsdFormatter.formatClock(dur)
            val permille = OsdFormatter.progressPermille(pos, dur)
            progressBar.progress = permille
            progressBar.secondaryProgress = OsdFormatter.progressPermille(buffered, dur)
            updateThumbPosition(permille)
        }
    }

    private fun updateThumbPosition(permille: Int) {
        val trackW = progressTrackContainer.width
        val thumbW = progressThumb.width
        if (trackW > thumbW && thumbW > 0) {
            val usable = (trackW - thumbW).toFloat()
            progressThumb.translationX = usable * (permille / 1000f)
        }
    }

    private fun currentClockSummary(): String {
        val session = CastBus.session ?: return ""
        val pos = OsdFormatter.formatClock(session.positionMs())
        if (session.isLive()) return "${getString(R.string.osd_watched)} $pos"
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

    private fun showCenterMessage(iconRes: Int, mainText: String, subText: String, persistent: Boolean) {
        centerSpinner.visibility = View.GONE
        centerIcon.visibility = View.VISIBLE
        centerIcon.setImageResource(iconRes)
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

    private fun showBufferingMessage(subText: String) {
        centerIcon.visibility = View.GONE
        centerSpinner.visibility = View.VISIBLE
        centerPillTitle.text = getString(R.string.osd_loading)
        centerPillSub.text = subText
        centerPillSub.visibility = if (subText.isBlank()) View.GONE else View.VISIBLE
        centerPill.animate().cancel()
        centerPill.alpha = 1f
        centerPill.visibility = View.VISIBLE
        ui.removeCallbacks(hideCenterPill)
    }

    private fun hideCenterPillImmediately() {
        ui.removeCallbacks(hideCenterPill)
        centerPill.animate().cancel()
        centerPill.visibility = View.GONE
    }

    private fun pillDrawable(fillHex: String, strokeHex: String?): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(999).toFloat()
        setColor(Color.parseColor(fillHex))
        if (strokeHex != null) setStroke(dp(1), Color.parseColor(strokeHex))
    }

    private fun dp(value: Int): Int =
        (resources.displayMetrics.density * value).toInt()

    companion object {
        private const val BAR_TIMEOUT_MS = 3_000L
        private const val PILL_TIMEOUT_MS = 1_300L

        /** How long NO_MEDIA_PRESENT must persist before the player screen exits. */
        private const val SWITCH_GRACE_MS = 1_500L
    }
}
