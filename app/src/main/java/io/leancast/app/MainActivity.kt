package io.leancast.app

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.leancast.domain.cast.OsdFormatter
import io.leancast.domain.cast.TransportState
import io.leancast.domain.update.UpdateCheckThrottle
import io.leancast.domain.update.UpdateManifest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 10-foot Android TV dashboard calibrated for dark living rooms:
 * - Left column (57%): Brand header with version + live status pill, cast
 *   discovery name, single-line LAN endpoint + capability bar, dynamic session
 *   or error diagnostic box, and compact 3-step casting guide.
 * - Right column (43%, scrollable): D-pad focusable action cards with calm
 *   neutral pill hierarchy (electric cyan reserved strictly for focus and
 *   active playback) plus a dark TV rename modal with D-pad preset chips.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings

    private lateinit var badgeDot: View
    private lateinit var badgeText: TextView
    private lateinit var badgeContainer: LinearLayout
    private lateinit var heroDeviceName: TextView
    private lateinit var endpointText: TextView

    private lateinit var statusBox: LinearLayout
    private lateinit var statusAlertIcon: ImageView
    private lateinit var statusHeaderLabel: TextView
    private lateinit var statusHeaderMeta: TextView
    private lateinit var statusBodyText: TextView
    private lateinit var guideStep3: TextView

    private lateinit var renameCard: View
    private lateinit var autostartStatus: TextView
    private lateinit var autostartPill: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var servicePill: TextView
    private lateinit var resumePlayerCard: View
    private lateinit var resumePlayerSub: TextView
    private lateinit var resumePlayerPill: TextView
    private lateinit var resumeProgressBar: ProgressBar
    private lateinit var heroUpdateBadge: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updatePill: TextView

    private var renderedBadgeKey: String? = null
    private var renderedStatusBoxKey: String? = null
    private var renderedAutostart: Boolean? = null
    private var renderedServiceRunning: Boolean? = null
    private var renderedUpdateKey: String? = null
    private var wasResumeVisible: Boolean = false

    private val updateExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "leancast-updater").apply { isDaemon = true }
    }
    @Volatile private var updateChecking: Boolean = false
    @Volatile private var updateDownloading: Boolean = false
    @Volatile private var availableUpdate: UpdateManifest? = null
    private var updateModalRefresh: (() -> Unit)? = null
    private var updateModalMessage: String? = null
    private var updateDownloadPercent: Int? = null
    private var updateVerifying: Boolean = false

    private val poll = Handler(Looper.getMainLooper())
    private val pollTask = object : Runnable {
        override fun run() {
            renderStatus()
            poll.postDelayed(this, 1000)
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(Settings.wrapLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#060A14"), Color.parseColor("#0B1120"), Color.parseColor("#090E1A")),
            )
            setPadding(dp(44), dp(36), dp(44), dp(36))
        }

        val leftCol = buildLeftHeroCard()
        val rightCol = buildRightActionColumn()

        root.addView(
            leftCol,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 57f).apply {
                rightMargin = dp(28)
            },
        )
        root.addView(
            rightCol,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 43f),
        )

        setContentView(root)
        ReceiverService.start(this)
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        // Prioritize Resume Fullscreen card when returning during active playback.
        val target = if (resumePlayerCard.visibility == View.VISIBLE) resumePlayerCard else renameCard
        target.post { target.requestFocus() }
        poll.postDelayed(pollTask, 1000)
        maybeAutoCheckUpdate()
    }

    override fun onPause() {
        poll.removeCallbacks(pollTask)
        super.onPause()
    }

    override fun onDestroy() {
        updateModalRefresh = null
        updateExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildLeftHeroCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(
                fillColor = Color.parseColor("#C711192B"),
                strokeColor = Color.parseColor("#1F94A3B8"),
                strokeWidthPx = dp(1),
                radiusPx = dp(20).toFloat(),
            )
            setPadding(dp(30), dp(24), dp(30), dp(22))
        }

        // Top row: Brand Icon + Compact Name + Version Tag + Live Status Pill
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_brand)
        }
        headerRow.addView(iconView, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            rightMargin = dp(12)
        })

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandRow.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        brandRow.addView(TextView(this).apply {
            text = "v${appVersionName()}"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = roundedRect(
                fillColor = Color.parseColor("#1E293B"),
                strokeColor = Color.parseColor("#334155"),
                strokeWidthPx = dp(1),
                radiusPx = dp(6).toFloat(),
            )
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        heroUpdateBadge = TextView(this).apply {
            setTextColor(Color.parseColor("#7DD3FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = roundedRect(
                fillColor = Color.parseColor("#3338BDF8"),
                strokeColor = Color.parseColor("#6638BDF8"),
                strokeWidthPx = dp(1),
                radiusPx = dp(6).toFloat(),
            )
            visibility = View.GONE
        }
        brandRow.addView(heroUpdateBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(6)
        })
        titleCol.addView(brandRow)
        titleCol.addView(TextView(this).apply {
            text = getString(R.string.app_tagline)
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        })
        headerRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        badgeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        badgeDot = View(this)
        badgeContainer.addView(badgeDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            rightMargin = dp(7)
        })
        badgeText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        badgeContainer.addView(badgeText)
        headerRow.addView(badgeContainer)
        card.addView(headerRow)

        // Cast Discovery Name Section
        card.addView(TextView(this).apply {
            text = getString(R.string.label_device_name)
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })

        heroDeviceName = TextView(this).apply {
            text = settings.friendlyName()
            setTextColor(Color.parseColor("#FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(heroDeviceName, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })

        // Single-line Merged Endpoint + Capabilities Bar
        val endpointBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedRect(
                fillColor = Color.parseColor("#BF020617"),
                strokeColor = Color.parseColor("#1E293B"),
                strokeWidthPx = dp(1),
                radiusPx = dp(12).toFloat(),
            )
        }
        endpointText = TextView(this).apply {
            text = "-:-"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            maxLines = 1
        }
        endpointBar.addView(endpointText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        endpointBar.addView(TextView(this).apply {
            text = getString(R.string.meta_spec_summary)
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            maxLines = 1
        })
        card.addView(endpointBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        // Dynamic Status / Active Session / Error Diagnostic Box
        statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        val statusTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusAlertIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_osd_alert)
            setColorFilter(Color.parseColor("#F87171"))
            visibility = View.GONE
        }
        statusTopRow.addView(statusAlertIcon, LinearLayout.LayoutParams(dp(16), dp(16)).apply {
            rightMargin = dp(7)
        })
        statusHeaderLabel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
        }
        statusTopRow.addView(statusHeaderLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusHeaderMeta = TextView(this).apply {
            setTextColor(Color.parseColor("#7DD3FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
        }
        statusTopRow.addView(statusHeaderMeta)
        statusBox.addView(statusTopRow)

        statusBodyText = TextView(this).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        statusBox.addView(statusBodyText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
        })
        card.addView(statusBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        // Flexible spacer
        card.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Compact 3-Column Quick Start Footer
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        card.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            bottomMargin = dp(12)
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.guide_title)
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        })
        val stepsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        stepsRow.addView(makeGuideStep("1", getString(R.string.guide_step_1)).first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(10)
        })
        stepsRow.addView(makeGuideStep("2", getString(R.string.guide_step_2)).first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(10)
        })
        val step3 = makeGuideStep("3", getString(R.string.guide_step_3, settings.friendlyName()))
        guideStep3 = step3.second
        stepsRow.addView(step3.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(stepsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        return card
    }

    private fun makeGuideStep(num: String, text: String): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        row.addView(TextView(this).apply {
            this.text = num
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(6)
        })
        val body = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setLineSpacing(dp(1).toFloat(), 1f)
        }
        row.addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row to body
    }

    private fun buildRightActionColumn(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        col.addView(TextView(this).apply {
            text = getString(R.string.section_controls)
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
            leftMargin = dp(4)
        })

        // Card 0: Resume Fullscreen Player (with mini live progress bar; shown when active)
        val resumeRefs = makeActionCard(
            title = getString(R.string.action_resume_player),
            subtitle = getString(R.string.action_resume_player_desc),
            pillText = getString(R.string.status_playing),
            includeMiniProgress = true,
            onClick = {
                startActivity(Intent(this, PlayerActivity::class.java))
            },
        )
        resumePlayerCard = resumeRefs.card
        resumePlayerSub = resumeRefs.subtitle
        resumePlayerPill = resumeRefs.pill
        resumeProgressBar = resumeRefs.miniProgress!!
        resumePlayerPill.setTextColor(Color.parseColor("#7DD3FC"))
        resumePlayerPill.typeface = Typeface.MONOSPACE
        resumePlayerPill.background = roundedRect(
            fillColor = Color.parseColor("#3338BDF8"),
            strokeColor = Color.parseColor("#6638BDF8"),
            strokeWidthPx = dp(1),
            radiusPx = dp(999).toFloat(),
        )
        resumePlayerCard.visibility = View.GONE
        col.addView(resumePlayerCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        // Card 1: Rename Device
        val renameRefs = makeActionCard(
            title = getString(R.string.setting_rename),
            subtitle = getString(R.string.setting_rename_desc),
            pillText = getString(R.string.pill_edit),
            onClick = { showRenameDialog() },
        )
        renameCard = renameRefs.card
        col.addView(renameCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        // Card 2: Boot Autostart Toggle
        val autoRefs = makeActionCard(
            title = getString(R.string.setting_autostart),
            subtitle = "",
            pillText = "",
            onClick = {
                val enabling = !settings.autostart
                settings.autostart = enabling
                if (enabling && needsOverlayPermissionForBackgroundPopup()) {
                    requestOverlayPermissionIfSupported()
                }
                renderStatus()
            },
        )
        autostartStatus = autoRefs.subtitle
        autostartPill = autoRefs.pill
        col.addView(autoRefs.card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        // Card 3: Start / Stop Service
        val svcRefs = makeActionCard(
            title = getString(R.string.action_toggle_service),
            subtitle = "",
            pillText = "",
            onClick = {
                if (CastBus.session != null) ReceiverService.stop(this)
                else ReceiverService.start(this)
                poll.postDelayed({ renderStatus() }, 250)
            },
        )
        serviceStatus = svcRefs.subtitle
        servicePill = svcRefs.pill
        col.addView(svcRefs.card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        // Card 4: Language Switch (calm neutral pill so cyan stays reserved for focus)
        val langRefs = makeActionCard(
            title = getString(R.string.setting_language),
            subtitle = getString(R.string.setting_language_desc),
            pillText = getString(R.string.setting_language_pill),
            onClick = {
                settings.toggleLanguage()
                recreate()
            },
        )
        col.addView(langRefs.card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        // Card 5: Opt-in App Updates (default OFF for zero outbound traffic)
        val updateRefs = makeActionCard(
            title = getString(R.string.setting_updates),
            subtitle = "",
            pillText = "",
            onClick = { showUpdateDialog() },
        )
        updateStatus = updateRefs.subtitle
        updatePill = updateRefs.pill
        col.addView(updateRefs.card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private data class ActionCardRefs(
        val card: View,
        val subtitle: TextView,
        val pill: TextView,
        val miniProgress: ProgressBar? = null,
    )

    private fun makeActionCard(
        title: String,
        subtitle: String,
        pillText: String,
        includeMiniProgress: Boolean = false,
        onClick: () -> Unit,
    ): ActionCardRefs {
        val normalBg = roundedRect(
            fillColor = Color.parseColor("#D1141E33"),
            strokeColor = Color.parseColor("#2494A3B8"),
            strokeWidthPx = dp(2),
            radiusPx = dp(14).toFloat(),
        )
        val focusedBg = roundedRect(
            fillColor = Color.parseColor("#F51C2945"),
            strokeColor = Color.parseColor("#38BDF8"),
            strokeWidthPx = dp(3),
            radiusPx = dp(14).toFloat(),
        )

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = normalBg
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = if (hasFocus) focusedBg else normalBg
                val scale = if (hasFocus) 1.022f else 1.0f
                v.elevation = if (hasFocus) dp(8).toFloat() else 0f
                v.animate().scaleX(scale).scaleY(scale).setDuration(140).start()
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subView = TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(titleView)
        textCol.addView(subView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(10)
        })

        val pill = TextView(this).apply {
            text = pillText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(11), dp(5), dp(11), dp(5))
            setTextColor(Color.parseColor("#CBD5E1"))
            background = neutralPillDrawable()
        }
        row.addView(pill)
        container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        var miniBar: ProgressBar? = null
        if (includeMiniProgress) {
            miniBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = 0
                progressDrawable = buildMiniProgressDrawable()
            }
            container.addView(miniBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply {
                topMargin = dp(10)
            })
        }

        return ActionCardRefs(container, subView, pill, miniBar)
    }

    private fun buildMiniProgressDrawable(): LayerDrawable {
        val track = roundedRect(Color.parseColor("#1E293B"), 0, 0, dp(99).toFloat())
        val fill = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#38BDF8"), Color.parseColor("#60A5FA")),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(99).toFloat()
        }
        return LayerDrawable(arrayOf(track, ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL))).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun renderStatus() {
        val name = settings.friendlyName()
        heroDeviceName.text = name
        guideStep3.text = getString(R.string.guide_step_3, name)

        endpointText.text = CastBus.endpoint.ifEmpty { "-:-" }

        val session = CastBus.session
        val state = session?.state
        val title = session?.currentTitle?.takeIf { it.isNotBlank() }
        val lastError = session?.lastError?.takeIf { it.isNotBlank() }
        val activePlayback = state == TransportState.PLAYING ||
            state == TransportState.PAUSED_PLAYBACK ||
            state == TransportState.TRANSITIONING

        when {
            session == null -> {
                styleBadge("#8C7F1D1D", "#EF4444", "#F87171", "#FECACA", getString(R.string.status_service_stopped))
                styleStatusBox("offline", "#8C020617", "#1E293B", "#94A3B8", showAlert = false)
                statusHeaderLabel.text = getString(R.string.label_now_playing)
                statusHeaderMeta.visibility = View.GONE
                statusBodyText.text = getString(R.string.action_service_stopped)
                statusBodyText.setTextColor(Color.parseColor("#94A3B8"))
            }
            activePlayback -> {
                when (state) {
                    TransportState.PLAYING ->
                        styleBadge("#BF082F49", "#38BDF8", "#38BDF8", "#BAE6FD", getString(R.string.status_playing))
                    TransportState.PAUSED_PLAYBACK ->
                        styleBadge("#99422006", "#F59E0B", "#FBBF24", "#FDE68A", getString(R.string.status_paused))
                    else ->
                        styleBadge("#991E1B4B", "#818CF8", "#A5B4FC", "#E0E7FF", getString(R.string.status_loading))
                }
                styleStatusBox("active", "#59082F49", "#4D38BDF8", "#38BDF8", showAlert = false)
                statusHeaderLabel.text = when (state) {
                    TransportState.PAUSED_PLAYBACK -> getString(R.string.status_paused)
                    TransportState.TRANSITIONING -> getString(R.string.status_loading)
                    else -> getString(R.string.status_playing)
                }
                val live = session.isLive()
                val pos = session.positionMs()
                val dur = session.durationMs()
                val speed = session.playbackSpeed
                val speedSuffix = if (!live && kotlin.math.abs(speed - 1.0f) > 0.01f) {
                    " · ${OsdFormatter.formatSpeed(speed)}"
                } else {
                    ""
                }
                statusHeaderMeta.visibility = View.VISIBLE
                statusHeaderMeta.text = if (live) {
                    "${OsdFormatter.formatClock(pos)} · ${getString(R.string.osd_live)}"
                } else {
                    "${OsdFormatter.formatClock(pos)} / ${OsdFormatter.formatClock(dur)}$speedSuffix"
                }
                statusBodyText.text = title ?: (session.currentUri ?: getString(R.string.status_playing))
                statusBodyText.setTextColor(Color.parseColor("#F1F5F9"))

                resumePlayerSub.text = title ?: getString(R.string.action_resume_player_desc)
                resumePlayerPill.text = if (live) {
                    getString(R.string.pill_live)
                } else {
                    "${OsdFormatter.formatClock(pos)} / ${OsdFormatter.formatClock(dur)}"
                }
                resumeProgressBar.visibility = if (live) View.GONE else View.VISIBLE
                if (!live) resumeProgressBar.progress = OsdFormatter.progressPermille(pos, dur)
            }
            lastError != null -> {
                styleBadge("#737F1D1D", "#F87171", "#F87171", "#FECACA", getString(R.string.status_error))
                styleStatusBox("error", "#73450A0A", "#66F87171", "#FECACA", showAlert = true)
                statusHeaderLabel.text = getString(R.string.error_playback_title, lastError)
                statusHeaderMeta.visibility = View.GONE
                statusBodyText.text = getString(R.string.error_playback_desc)
                statusBodyText.setTextColor(Color.parseColor("#CCFECACA"))
            }
            else -> {
                // Idle / STOPPED: always show calm standby hint, never a stale previous title.
                styleBadge("#8C064E3B", "#10B981", "#34D399", "#A7F3D0", getString(R.string.status_ready))
                styleStatusBox("idle", "#8C020617", "#1E293B", "#94A3B8", showAlert = false)
                statusHeaderLabel.text = getString(R.string.label_now_playing)
                statusHeaderMeta.visibility = View.GONE
                statusBodyText.text = getString(R.string.label_idle_hint)
                statusBodyText.setTextColor(Color.parseColor("#CBD5E1"))
            }
        }

        resumePlayerCard.visibility = if (activePlayback) View.VISIBLE else View.GONE
        if (activePlayback && !wasResumeVisible) {
            resumePlayerCard.post { resumePlayerCard.requestFocus() }
        } else if (!activePlayback && wasResumeVisible) {
            renameCard.post { renameCard.requestFocus() }
        }
        wasResumeVisible = activePlayback

        // Autostart card state
        val auto = settings.autostart
        autostartStatus.text = when {
            !auto -> getString(R.string.setting_autostart_off)
            needsOverlayPermissionForBackgroundPopup() -> getString(R.string.setting_autostart_on_overlay_hint)
            else -> getString(R.string.setting_autostart_on)
        }
        if (renderedAutostart != auto) {
            renderedAutostart = auto
            autostartPill.text = if (auto) getString(R.string.pill_on) else getString(R.string.pill_off)
            autostartPill.setTextColor(if (auto) Color.parseColor("#052E16") else Color.parseColor("#94A3B8"))
            autostartPill.background = if (auto) {
                roundedRect(Color.parseColor("#E634D399"), 0, 0, dp(999).toFloat())
            } else {
                neutralPillDrawable()
            }
        }

        // Service card state
        val running = session != null
        if (renderedServiceRunning != running) {
            renderedServiceRunning = running
            serviceStatus.text = if (running) getString(R.string.action_service_running) else getString(R.string.action_service_stopped)
            servicePill.text = if (running) getString(R.string.pill_active) else getString(R.string.pill_stopped)
            servicePill.setTextColor(if (running) Color.parseColor("#052E16") else Color.parseColor("#FECACA"))
            servicePill.background = if (running) {
                roundedRect(Color.parseColor("#E634D399"), 0, 0, dp(999).toFloat())
            } else {
                roundedRect(Color.parseColor("#B37F1D1D"), Color.parseColor("#99B91C1C"), dp(1), dp(999).toFloat())
            }
        }

        // Opt-in Updates card & hero version badge
        val updateEnabled = settings.updateCheckEnabled
        val avail = if (updateEnabled) availableUpdate else null
        val updateKey = "$updateEnabled|$updateChecking|${avail?.version.orEmpty()}"
        if (renderedUpdateKey != updateKey) {
            renderedUpdateKey = updateKey
            when {
                !updateEnabled -> {
                    updateStatus.text = getString(R.string.setting_updates_off)
                    updatePill.text = getString(R.string.pill_off)
                    updatePill.setTextColor(Color.parseColor("#94A3B8"))
                    updatePill.background = neutralPillDrawable()
                    heroUpdateBadge.visibility = View.GONE
                }
                avail != null -> {
                    updateStatus.text = getString(R.string.setting_updates_available, avail.version)
                    updatePill.text = "v${avail.version}"
                    updatePill.setTextColor(Color.parseColor("#7DD3FC"))
                    updatePill.background = roundedRect(
                        Color.parseColor("#3338BDF8"),
                        Color.parseColor("#6638BDF8"),
                        dp(1),
                        dp(999).toFloat(),
                    )
                    heroUpdateBadge.text = "↑ v${avail.version}"
                    heroUpdateBadge.visibility = View.VISIBLE
                }
                updateChecking -> {
                    updateStatus.text = getString(R.string.setting_updates_checking)
                    updatePill.text = getString(R.string.pill_checking)
                    updatePill.setTextColor(Color.parseColor("#CBD5E1"))
                    updatePill.background = neutralPillDrawable()
                    heroUpdateBadge.visibility = View.GONE
                }
                else -> {
                    updateStatus.text = getString(R.string.setting_updates_on, appVersionName())
                    updatePill.text = getString(R.string.pill_on)
                    updatePill.setTextColor(Color.parseColor("#052E16"))
                    updatePill.background = roundedRect(Color.parseColor("#E634D399"), 0, 0, dp(999).toFloat())
                    heroUpdateBadge.visibility = View.GONE
                }
            }
        }
    }

    private fun styleBadge(bgHex: String, borderHex: String, dotHex: String, textHex: String, label: String) {
        val key = "$bgHex|$borderHex|$dotHex|$textHex|$label"
        if (renderedBadgeKey == key) return
        renderedBadgeKey = key
        badgeContainer.background = roundedRect(
            fillColor = Color.parseColor(bgHex),
            strokeColor = Color.parseColor(borderHex),
            strokeWidthPx = dp(1),
            radiusPx = dp(999).toFloat(),
        )
        badgeDot.background = roundedRect(Color.parseColor(dotHex), 0, 0, dp(99).toFloat())
        badgeText.setTextColor(Color.parseColor(textHex))
        badgeText.text = label
    }

    private fun styleStatusBox(
        key: String,
        bgHex: String,
        borderHex: String,
        headerHex: String,
        showAlert: Boolean,
    ) {
        if (renderedStatusBoxKey == key) return
        renderedStatusBoxKey = key
        statusBox.background = roundedRect(
            fillColor = Color.parseColor(bgHex),
            strokeColor = Color.parseColor(borderHex),
            strokeWidthPx = dp(1),
            radiusPx = dp(12).toFloat(),
        )
        statusHeaderLabel.setTextColor(Color.parseColor(headerHex))
        statusAlertIcon.visibility = if (showAlert) View.VISIBLE else View.GONE
    }

    private fun neutralPillDrawable(): GradientDrawable = roundedRect(
        fillColor = Color.parseColor("#1E293B"),
        strokeColor = Color.parseColor("#334155"),
        strokeWidthPx = dp(1),
        radiusPx = dp(999).toFloat(),
    )

    /** Dark TV-styled rename modal with D-pad preset chips and instant SSDP re-announcement. */
    private fun showRenameDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(
                fillColor = Color.parseColor("#0F172A"),
                strokeColor = Color.parseColor("#334155"),
                strokeWidthPx = dp(1),
                radiusPx = dp(18).toFloat(),
            )
            setPadding(dp(26), dp(22), dp(26), dp(22))
        }

        card.addView(TextView(this).apply {
            text = getString(R.string.rename_modal_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.rename_modal_sub)
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        // Quick D-pad Preset Chips
        card.addView(TextView(this).apply {
            text = getString(R.string.rename_presets_label)
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        val input = EditText(this).apply {
            setText(settings.friendlyName())
            setSelectAllOnFocus(true)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = roundedRect(
                fillColor = Color.parseColor("#020617"),
                strokeColor = Color.parseColor("#38BDF8"),
                strokeWidthPx = dp(2),
                radiusPx = dp(10).toFloat(),
            )
        }

        val saveAction = {
            settings.setFriendlyName(input.text.toString())
            if (CastBus.session != null) ReceiverService.reannounce(this)
            else ReceiverService.start(this)
            renderStatus()
            dialog.dismiss()
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveAction()
                true
            } else {
                false
            }
        }

        val saveBtn = makeModalButton(
            label = getString(R.string.btn_save),
            primary = true,
            onClick = saveAction,
        )

        val presets = listOf(
            getString(R.string.preset_name_1),
            getString(R.string.preset_name_2),
            getString(R.string.preset_name_3),
            getString(R.string.preset_name_4),
        )
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        var firstChip: View? = null
        presets.forEachIndexed { idx, preset ->
            val normalChip = roundedRect(Color.parseColor("#1E293B"), Color.parseColor("#334155"), dp(1), dp(999).toFloat())
            val focusedChip = roundedRect(Color.parseColor("#2E38BDF8"), Color.parseColor("#38BDF8"), dp(2), dp(999).toFloat())
            val chip = TextView(this).apply {
                text = preset
                setTextColor(Color.parseColor("#E2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(11), dp(7), dp(11), dp(7))
                maxLines = 1
                background = normalChip
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { v, hasFocus ->
                    v.background = if (hasFocus) focusedChip else normalChip
                }
                setOnClickListener {
                    input.setText(preset)
                    input.setSelection(preset.length)
                    saveBtn.requestFocus()
                }
            }
            if (idx == 0) firstChip = chip
            presetRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (idx < presets.lastIndex) rightMargin = dp(8)
            })
        }
        card.addView(presetRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        card.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        // Action Buttons (Cancel / Save)
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancelBtn = makeModalButton(
            label = getString(R.string.btn_cancel),
            primary = false,
            onClick = { dialog.dismiss() },
        )
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(10)
        })
        btnRow.addView(saveBtn)
        card.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        })

        dialog.setContentView(card, ViewGroup.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
        firstChip?.post { firstChip?.requestFocus() }
    }

    private fun makeModalButton(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val normalBg = if (primary) {
            roundedRect(Color.parseColor("#38BDF8"), 0, 0, dp(10).toFloat())
        } else {
            roundedRect(Color.parseColor("#1E293B"), Color.parseColor("#334155"), dp(1), dp(10).toFloat())
        }
        val focusedBg = if (primary) {
            roundedRect(Color.parseColor("#7DD3FC"), Color.WHITE, dp(2), dp(10).toFloat())
        } else {
            roundedRect(Color.parseColor("#334155"), Color.parseColor("#38BDF8"), dp(2), dp(10).toFloat())
        }
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.parseColor("#082F49") else Color.parseColor("#E2E8F0"))
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = normalBg
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.background = if (hasFocus) focusedBg else normalBg
            }
            setOnClickListener { onClick() }
        }
    }

    private fun maybeAutoCheckUpdate() {
        val now = System.currentTimeMillis()
        if (!UpdateCheckThrottle.shouldAutoCheck(settings.updateCheckEnabled, settings.lastUpdateCheckMs, now)) {
            return
        }
        startUpdateCheck(manual = false)
    }

    private fun startUpdateCheck(manual: Boolean) {
        if (!settings.updateCheckEnabled) return
        if (updateChecking || updateDownloading) return

        updateChecking = true
        settings.lastUpdateCheckMs = System.currentTimeMillis()
        if (manual) {
            updateModalMessage = getString(R.string.update_status_checking)
        }
        renderStatus()
        updateModalRefresh?.invoke()

        val currentVersion = appVersionName()
        runCatching {
            updateExecutor.execute {
                val result = AppUpdater.checkForUpdate(currentVersion)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    updateChecking = false
                    if (!settings.updateCheckEnabled) {
                        availableUpdate = null
                        updateModalMessage = null
                        renderStatus()
                        updateModalRefresh?.invoke()
                        return@runOnUiThread
                    }
                    when (result) {
                        is AppUpdater.CheckResult.UpdateAvailable -> {
                            availableUpdate = result.manifest
                            updateModalMessage = null
                        }
                        is AppUpdater.CheckResult.UpToDate -> {
                            availableUpdate = null
                            if (manual) {
                                updateModalMessage = getString(R.string.update_status_uptodate, currentVersion)
                            }
                        }
                        is AppUpdater.CheckResult.Failed -> {
                            if (manual) {
                                updateModalMessage = getString(R.string.update_status_error, result.reason)
                            }
                        }
                    }
                    renderStatus()
                    updateModalRefresh?.invoke()
                }
            }
        }.onFailure {
            updateChecking = false
            renderStatus()
            updateModalRefresh?.invoke()
        }
    }

    private fun startUpdateDownload(manifest: UpdateManifest, dialog: Dialog) {
        if (!settings.updateCheckEnabled || updateDownloading) return

        updateDownloading = true
        updateVerifying = false
        updateDownloadPercent = 0
        updateModalMessage = getString(R.string.update_status_downloading, manifest.version, 0)
        updateModalRefresh?.invoke()

        val cacheDir = AppUpdater.updatesCacheDir(applicationContext)
        runCatching {
            updateExecutor.execute {
                val result = AppUpdater.downloadAndVerifyApk(
                    manifest = manifest,
                    targetDir = cacheDir,
                ) { stage ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        when (stage) {
                            is AppUpdater.DownloadStage.Downloading -> {
                                updateVerifying = false
                                updateDownloadPercent = stage.percent
                                updateModalMessage = getString(
                                    R.string.update_status_downloading,
                                    manifest.version,
                                    stage.percent,
                                )
                            }
                            AppUpdater.DownloadStage.Verifying -> {
                                updateVerifying = true
                                updateDownloadPercent = 100
                                updateModalMessage = getString(R.string.update_status_verifying)
                            }
                        }
                        updateModalRefresh?.invoke()
                    }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    updateDownloading = false
                    updateVerifying = false
                    updateDownloadPercent = null
                    when (result) {
                        is AppUpdater.DownloadResult.Ready -> {
                            when (AppUpdater.launchInstaller(this, result.apkFile)) {
                                AppUpdater.InstallOutcome.LAUNCHED -> {
                                    if (dialog.isShowing) dialog.dismiss()
                                }
                                AppUpdater.InstallOutcome.PERMISSION_REQUIRED -> {
                                    updateModalMessage = getString(R.string.update_status_permission)
                                }
                                AppUpdater.InstallOutcome.INSTALLER_UNAVAILABLE -> {
                                    updateModalMessage = getString(R.string.update_status_no_installer)
                                }
                            }
                        }
                        is AppUpdater.DownloadResult.IntegrityError -> {
                            updateModalMessage = getString(R.string.update_status_integrity_error)
                        }
                        is AppUpdater.DownloadResult.Failed -> {
                            updateModalMessage = getString(R.string.update_status_error, result.reason)
                        }
                    }
                    updateModalRefresh?.invoke()
                }
            }
        }.onFailure {
            updateDownloading = false
            updateVerifying = false
            updateDownloadPercent = null
            updateModalRefresh?.invoke()
        }
    }

    /** Dark TV-styled update modal with D-pad opt-in toggle, changelog, and verified installer flow. */
    private fun showUpdateDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(
                fillColor = Color.parseColor("#0F172A"),
                strokeColor = Color.parseColor("#334155"),
                strokeWidthPx = dp(1),
                radiusPx = dp(18).toFloat(),
            )
            setPadding(dp(26), dp(22), dp(26), dp(22))
        }

        card.addView(TextView(this).apply {
            text = getString(R.string.update_modal_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.update_modal_sub, appVersionName())
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        // Focusable opt-in toggle row
        lateinit var refreshModal: () -> Unit
        val toggleRefs = makeActionCard(
            title = getString(R.string.update_toggle_title),
            subtitle = getString(R.string.update_toggle_desc),
            pillText = "",
            onClick = {
                if (updateDownloading) return@makeActionCard
                val enabling = !settings.updateCheckEnabled
                settings.updateCheckEnabled = enabling
                if (enabling) {
                    updateModalMessage = null
                    renderStatus()
                    refreshModal()
                    startUpdateCheck(manual = true)
                } else {
                    availableUpdate = null
                    updateModalMessage = null
                    renderStatus()
                    refreshModal()
                }
            },
        )
        card.addView(toggleRefs.card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        // Status + scrollable changelog box
        val infoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(
                fillColor = Color.parseColor("#BF020617"),
                strokeColor = Color.parseColor("#1E293B"),
                strokeWidthPx = dp(1),
                radiusPx = dp(12).toFloat(),
            )
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        infoBox.addView(statusText)

        val changelogScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            visibility = View.GONE
        }
        val changelogText = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        changelogScroll.addView(changelogText)
        infoBox.addView(changelogScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)).apply {
            topMargin = dp(8)
        })

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressDrawable = buildMiniProgressDrawable()
            visibility = View.GONE
        }
        infoBox.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
            topMargin = dp(10)
        })

        card.addView(infoBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        // Action buttons
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val closeBtn = makeModalButton(
            label = getString(R.string.btn_close),
            primary = false,
            onClick = { dialog.dismiss() },
        )
        val checkBtn = makeModalButton(
            label = getString(R.string.btn_check_now),
            primary = false,
            onClick = { startUpdateCheck(manual = true) },
        )
        val installBtn = makeModalButton(
            label = getString(R.string.btn_download_install),
            primary = true,
            onClick = {
                availableUpdate?.let { startUpdateDownload(it, dialog) }
            },
        )
        btnRow.addView(closeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(10)
        })
        btnRow.addView(checkBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(10)
        })
        btnRow.addView(installBtn)
        card.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        })

        refreshModal = {
            val enabled = settings.updateCheckEnabled
            val avail = if (enabled) availableUpdate else null

            toggleRefs.pill.text = if (enabled) getString(R.string.pill_on) else getString(R.string.pill_off)
            toggleRefs.pill.setTextColor(if (enabled) Color.parseColor("#052E16") else Color.parseColor("#94A3B8"))
            toggleRefs.pill.background = if (enabled) {
                roundedRect(Color.parseColor("#E634D399"), 0, 0, dp(999).toFloat())
            } else {
                neutralPillDrawable()
            }

            statusText.text = when {
                !enabled -> getString(R.string.update_status_disabled)
                updateModalMessage != null -> updateModalMessage
                updateChecking -> getString(R.string.update_status_checking)
                avail != null -> getString(R.string.update_status_available, avail.version)
                else -> getString(R.string.update_status_idle)
            }

            val notes = avail?.changelog?.takeIf { it.isNotBlank() }
            if (enabled && notes != null) {
                changelogText.text = notes
                changelogScroll.visibility = View.VISIBLE
            } else {
                changelogScroll.visibility = View.GONE
            }

            if (updateDownloading || updateVerifying) {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = updateVerifying
                progressBar.progress = updateDownloadPercent ?: 0
            } else {
                progressBar.visibility = View.GONE
            }

            val canCheck = enabled && !updateChecking && !updateDownloading
            checkBtn.visibility = if (enabled) View.VISIBLE else View.GONE
            checkBtn.isEnabled = canCheck
            checkBtn.isFocusable = canCheck
            checkBtn.alpha = if (canCheck) 1f else 0.45f

            val canInstall = enabled && avail != null && !updateDownloading
            installBtn.visibility = if (enabled && avail != null) View.VISIBLE else View.GONE
            installBtn.isEnabled = canInstall
            installBtn.isFocusable = canInstall
            installBtn.alpha = if (canInstall) 1f else 0.45f
        }

        updateModalRefresh = refreshModal
        dialog.setOnDismissListener { updateModalRefresh = null }
        refreshModal()

        dialog.setContentView(card, ViewGroup.LayoutParams(dp(580), ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
        val initialFocus = if (settings.updateCheckEnabled && availableUpdate != null) installBtn else toggleRefs.card
        initialFocus.post { initialFocus.requestFocus() }
    }

    private fun appVersionName(): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }.getOrDefault("")

    private fun roundedRect(
        fillColor: Int,
        strokeColor: Int,
        strokeWidthPx: Int,
        radiusPx: Float,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(fillColor)
        if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
    }

    private fun needsOverlayPermissionForBackgroundPopup(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !AndroidSettings.canDrawOverlays(this)

    private fun requestOverlayPermissionIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            val intent = Intent(
                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
        }
    }

    private fun dp(value: Int): Int =
        (resources.displayMetrics.density * value).toInt()
}
