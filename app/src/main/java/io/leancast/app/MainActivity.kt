package io.leancast.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.leancast.domain.cast.TransportState

/**
 * 10-foot Android TV dashboard (Plan A: Apple TV / modern two-column layout).
 * Left column (58%): Hero status card with live badge, large device name,
 * endpoint/protocol pills, active media banner, and 3-step casting guide.
 * Right column (42%): D-pad focusable action cards with high-contrast glow
 * border and 1.03x focus scale animation.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings

    private lateinit var badgeDot: View
    private lateinit var badgeText: TextView
    private lateinit var badgeContainer: LinearLayout
    private lateinit var heroDeviceName: TextView
    private lateinit var pillEndpoint: TextView
    private lateinit var nowPlayingTitle: TextView
    private lateinit var guideStep3: TextView

    private lateinit var renameDesc: TextView
    private lateinit var autostartStatus: TextView
    private lateinit var autostartPill: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var servicePill: TextView
    private lateinit var resumePlayerCard: View

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
                intArrayOf(Color.parseColor("#080D1A"), Color.parseColor("#0F172A"), Color.parseColor("#111827")),
            )
            setPadding(dp(48), dp(40), dp(48), dp(40))
        }

        val leftCol = buildLeftHeroCard()
        val rightCol = buildRightActionColumn()

        root.addView(
            leftCol,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 58f).apply {
                rightMargin = dp(32)
            },
        )
        root.addView(
            rightCol,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 42f),
        )

        setContentView(root)
        ReceiverService.start(this)
    }

    override fun onResume() {
        super.onResume()
        poll.post(pollTask)
    }

    override fun onPause() {
        poll.removeCallbacks(pollTask)
        super.onPause()
    }

    private fun buildLeftHeroCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(
                fillColor = Color.parseColor("#131C31"),
                strokeColor = Color.parseColor("#1E293B"),
                strokeWidthPx = dp(1),
                radiusPx = dp(20).toFloat(),
            )
            setPadding(dp(32), dp(28), dp(32), dp(28))
        }

        // Top row: Icon + Brand + Status Badge
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_brand)
        }
        headerRow.addView(iconView, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            rightMargin = dp(14)
        })

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleCol.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        titleCol.addView(TextView(this).apply {
            text = getString(R.string.app_Tagline)
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        headerRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        badgeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = roundedRect(
                fillColor = Color.parseColor("#064E3B"),
                strokeColor = Color.parseColor("#10B981"),
                strokeWidthPx = dp(1),
                radiusPx = dp(999).toFloat(),
            )
        }
        badgeDot = View(this).apply {
            background = roundedRect(Color.parseColor("#34D399"), 0, 0, dp(99).toFloat())
        }
        badgeContainer.addView(badgeDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            rightMargin = dp(8)
        })
        badgeText = TextView(this).apply {
            text = getString(R.string.status_ready)
            setTextColor(Color.parseColor("#A7F3D0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }
        badgeContainer.addView(badgeText)
        headerRow.addView(badgeContainer)
        card.addView(headerRow)

        // Device Name Section
        card.addView(TextView(this).apply {
            text = getString(R.string.label_device_name)
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(22)
        })

        heroDeviceName = TextView(this).apply {
            text = settings.friendlyName()
            setTextColor(Color.parseColor("#FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(heroDeviceName, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })

        // Endpoint / Protocol / Decoder spec sheet (full-width rows; never truncated)
        val specList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val epRow = makeSpecRow(getString(R.string.meta_label_endpoint), "-:-", monospace = true)
        pillEndpoint = epRow.second
        val protoRow = makeSpecRow(
            getString(R.string.meta_label_protocol),
            getString(R.string.meta_value_protocol),
            monospace = false,
        )
        val codecRow = makeSpecRow(
            getString(R.string.meta_label_decoder),
            getString(R.string.meta_value_decoder),
            monospace = false,
        )
        specList.addView(epRow.first, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) })
        specList.addView(protoRow.first, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) })
        specList.addView(codecRow.first, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        card.addView(specList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        // Now Playing Box
        val nowBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(
                fillColor = Color.parseColor("#0B1120"),
                strokeColor = Color.parseColor("#1E293B"),
                strokeWidthPx = dp(1),
                radiusPx = dp(12).toFloat(),
            )
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        nowBox.addView(TextView(this).apply {
            text = getString(R.string.label_now_playing)
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
        })
        nowPlayingTitle = TextView(this).apply {
            text = getString(R.string.label_idle_hint)
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        nowBox.addView(nowPlayingTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        card.addView(nowBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        })

        // Spacer
        card.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 3-step Guide Footer
        val guideBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        guideBox.addView(TextView(this).apply {
            text = getString(R.string.guide_title)
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
        })
        guideBox.addView(makeGuideLine(getString(R.string.guide_step_1)))
        guideBox.addView(makeGuideLine(getString(R.string.guide_step_2)))
        guideStep3 = makeGuideLine(getString(R.string.guide_step_3, settings.friendlyName()))
        guideBox.addView(guideStep3)
        card.addView(guideBox)

        return card
    }

    private fun buildRightActionColumn(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        col.addView(TextView(this).apply {
            text = getString(R.string.section_controls)
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
            leftMargin = dp(4)
        })

        // Card 0: Resume Player (shown only when actively playing/paused)
        val resumeRefs = makeActionCard(
            title = getString(R.string.action_resume_player),
            subtitle = getString(R.string.action_resume_player_desc),
            pillText = getString(R.string.pill_live),
            accentPill = true,
            onClick = {
                startActivity(Intent(this, PlayerActivity::class.java))
            },
        )
        resumePlayerCard = resumeRefs.card
        resumePlayerCard.visibility = View.GONE
        col.addView(resumePlayerCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        // Card 1: Rename Device
        val renameRefs = makeActionCard(
            title = getString(R.string.setting_rename),
            subtitle = getString(R.string.setting_rename_desc),
            pillText = getString(R.string.pill_edit),
            accentPill = false,
            onClick = { showRenameDialog() },
        )
        renameDesc = renameRefs.subtitle
        col.addView(renameRefs.card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        // Card 2: Boot Autostart Toggle
        val autoRefs = makeActionCard(
            title = getString(R.string.setting_autostart),
            subtitle = "",
            pillText = "",
            accentPill = false,
            onClick = {
                settings.autostart = !settings.autostart
                renderStatus()
            },
        )
        autostartStatus = autoRefs.subtitle
        autostartPill = autoRefs.pill
        col.addView(autoRefs.card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        // Card 3: Start / Stop Service
        val svcRefs = makeActionCard(
            title = getString(R.string.action_toggle_service),
            subtitle = "",
            pillText = "",
            accentPill = false,
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

        // Card 4: Language Switch (中文 / EN)
        val langRefs = makeActionCard(
            title = getString(R.string.setting_language),
            subtitle = getString(R.string.setting_language_desc),
            pillText = getString(R.string.setting_language_pill),
            accentPill = true,
            onClick = {
                settings.toggleLanguage()
                recreate()
            },
        )
        col.addView(langRefs.card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Request initial D-pad focus on Rename card
        renameRefs.card.post { renameRefs.card.requestFocus() }

        return col
    }

    private data class ActionCardRefs(
        val card: View,
        val subtitle: TextView,
        val pill: TextView,
    )

    private fun makeActionCard(
        title: String,
        subtitle: String,
        pillText: String,
        accentPill: Boolean,
        onClick: () -> Unit,
    ): ActionCardRefs {
        val normalBg = roundedRect(
            fillColor = Color.parseColor("#162033"),
            strokeColor = Color.parseColor("#26354D"),
            strokeWidthPx = dp(2),
            radiusPx = dp(16).toFloat(),
        )
        val focusedBg = roundedRect(
            fillColor = Color.parseColor("#1E293B"),
            strokeColor = Color.parseColor("#38BDF8"),
            strokeWidthPx = dp(3),
            radiusPx = dp(16).toFloat(),
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = normalBg
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            setPadding(dp(22), dp(18), dp(22), dp(18))
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = if (hasFocus) focusedBg else normalBg
                val scale = if (hasFocus) 1.02f else 1.0f
                v.elevation = if (hasFocus) dp(8).toFloat() else 0f
                v.animate().scaleX(scale).scaleY(scale).setDuration(140).start()
            }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val subView = TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(titleView)
        textCol.addView(subView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
        })
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(12)
        })

        val pill = TextView(this).apply {
            text = pillText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setTextColor(if (accentPill) Color.parseColor("#082F49") else Color.parseColor("#E2E8F0"))
            background = roundedRect(
                fillColor = if (accentPill) Color.parseColor("#38BDF8") else Color.parseColor("#334155"),
                strokeColor = 0,
                strokeWidthPx = 0,
                radiusPx = dp(999).toFloat(),
            )
        }
        row.addView(pill)

        return ActionCardRefs(row, subView, pill)
    }

    /** One spec-sheet row: fixed label column + flexible value; never truncates our strings. */
    private fun makeSpecRow(
        label: String,
        initialValue: String,
        monospace: Boolean,
    ): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = roundedRect(
                fillColor = Color.parseColor("#0B1120"),
                strokeColor = Color.parseColor("#1E293B"),
                strokeWidthPx = dp(1),
                radiusPx = dp(10).toFloat(),
            )
        }
        val lbl = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            maxLines = 1
        }
        row.addView(lbl, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))

        val valueView = TextView(this).apply {
            text = initialValue
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = if (monospace) Typeface.MONOSPACE else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(valueView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(12)
        })
        return row to valueView
    }

    private fun makeGuideLine(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#94A3B8"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(4), 0, 0)
    }

    private fun renderStatus() {
        val name = settings.friendlyName()
        heroDeviceName.text = name
        guideStep3.text = getString(R.string.guide_step_3, name)

        val ep = CastBus.endpoint.ifEmpty { "-:-" }
        pillEndpoint.text = ep

        val session = CastBus.session
        val state = session?.state
        val title = session?.currentTitle?.takeIf { it.isNotBlank() }

        when (state) {
            null -> {
                styleBadge("#450A0A", "#EF4444", "#FCA5A5", getString(R.string.status_service_stopped))
                nowPlayingTitle.text = getString(R.string.status_service_stopped)
                resumePlayerCard.visibility = View.GONE
            }
            TransportState.PLAYING -> {
                styleBadge("#082F49", "#38BDF8", "#BAE6FD", getString(R.string.status_playing))
                nowPlayingTitle.text = title ?: (session.currentUri ?: getString(R.string.status_playing))
                resumePlayerCard.visibility = View.VISIBLE
            }
            TransportState.PAUSED_PLAYBACK -> {
                styleBadge("#422006", "#F59E0B", "#FDE68A", getString(R.string.status_paused))
                nowPlayingTitle.text = title ?: (session.currentUri ?: getString(R.string.status_paused))
                resumePlayerCard.visibility = View.VISIBLE
            }
            TransportState.TRANSITIONING -> {
                styleBadge("#1E1B4B", "#818CF8", "#C7D2FE", getString(R.string.status_loading))
                nowPlayingTitle.text = title ?: getString(R.string.status_loading)
                resumePlayerCard.visibility = View.VISIBLE
            }
            else -> {
                styleBadge("#064E3B", "#10B981", "#A7F3D0", getString(R.string.status_ready))
                nowPlayingTitle.text = title ?: getString(R.string.label_idle_hint)
                resumePlayerCard.visibility = View.GONE
            }
        }

        // Autostart card state
        val auto = settings.autostart
        autostartStatus.text = if (auto) getString(R.string.setting_autostart_on) else getString(R.string.setting_autostart_off)
        autostartPill.text = if (auto) getString(R.string.pill_on) else getString(R.string.pill_off)
        autostartPill.setTextColor(if (auto) Color.parseColor("#052E16") else Color.parseColor("#94A3B8"))
        autostartPill.background = roundedRect(
            fillColor = if (auto) Color.parseColor("#34D399") else Color.parseColor("#1E293B"),
            strokeColor = 0,
            strokeWidthPx = 0,
            radiusPx = dp(999).toFloat(),
        )

        // Service card state
        val running = session != null
        serviceStatus.text = if (running) getString(R.string.action_service_running) else getString(R.string.action_service_stopped)
        servicePill.text = if (running) getString(R.string.pill_active) else getString(R.string.pill_stopped)
        servicePill.setTextColor(if (running) Color.parseColor("#052E16") else Color.parseColor("#FCA5A5"))
        servicePill.background = roundedRect(
            fillColor = if (running) Color.parseColor("#34D399") else Color.parseColor("#7F1D1D"),
            strokeColor = 0,
            strokeWidthPx = 0,
            radiusPx = dp(999).toFloat(),
        )
    }

    private fun styleBadge(bgHex: String, borderHex: String, textHex: String, label: String) {
        badgeContainer.background = roundedRect(
            fillColor = Color.parseColor(bgHex),
            strokeColor = Color.parseColor(borderHex),
            strokeWidthPx = dp(1),
            radiusPx = dp(999).toFloat(),
        )
        badgeDot.background = roundedRect(Color.parseColor(borderHex), 0, 0, dp(99).toFloat())
        badgeText.setTextColor(Color.parseColor(textHex))
        badgeText.text = label
    }

    private fun showRenameDialog() {
        val pad = dp(20)
        val container = FrameLayout(this).apply {
            setPadding(pad, dp(12), pad, dp(4))
        }
        val input = EditText(this).apply {
            setText(settings.friendlyName())
            setSelectAllOnFocus(true)
        }
        container.addView(input)
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_rename)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.setFriendlyName(input.text.toString())
                // Restart service so SSDP & UPnP description advertise the new name immediately
                ReceiverService.stop(this)
                ReceiverService.start(this)
                renderStatus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

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

    private fun dp(value: Int): Int =
        (resources.displayMetrics.density * value).toInt()
}
