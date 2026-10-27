package io.leancast.app

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Status screen: service state, identity, endpoint, current cast; plus settings. */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var statusName: TextView
    private lateinit var statusEndpoint: TextView
    private lateinit var statusState: TextView
    private val poll = Handler(Looper.getMainLooper())
    private val pollTask = object : Runnable {
        override fun run() {
            renderStatus()
            poll.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)

        val pad = (resources.displayMetrics.density * 24).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val label = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 32f
        }
        statusName = statusLine()
        statusEndpoint = statusLine()
        statusState = statusLine()
        val autostartSwitch = Switch(this).apply {
            text = getString(R.string.setting_autostart)
            isChecked = settings.autostart
            setOnCheckedChangeListener { _, checked -> settings.autostart = checked }
        }
        val rename = Button(this).apply {
            text = getString(R.string.setting_rename)
            setOnClickListener { showRenameDialog() }
        }
        val toggle = Button(this).apply {
            text = getString(R.string.action_toggle_service)
            setOnClickListener {
                if (CastBus.session != null) ReceiverService.stop(this@MainActivity)
                else ReceiverService.start(this@MainActivity)
                renderStatus()
            }
        }
        root.addView(label)
        root.addView(statusName)
        root.addView(statusEndpoint)
        root.addView(statusState)
        root.addView(autostartSwitch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = pad })
        root.addView(rename)
        root.addView(toggle)
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

    private fun statusLine() = TextView(this).apply { textSize = 18f }

    private fun renderStatus() {
        val running = CastBus.session != null
        statusName.text = getString(R.string.status_name, settings.friendlyName())
        statusEndpoint.text = getString(R.string.status_endpoint, CastBus.endpoint.ifEmpty { "-" })
        val state = CastBus.session?.state?.name ?: getString(R.string.status_service_stopped)
        val title = CastBus.session?.currentTitle
        statusState.text = getString(R.string.status_state, state, title ?: "")
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply { setText(settings.friendlyName()) }
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.setFriendlyName(input.text.toString())
                renderStatus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

}
