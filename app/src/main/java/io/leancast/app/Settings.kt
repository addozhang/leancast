package io.leancast.app

import android.content.Context
import android.os.Build
import java.util.UUID

/** Persisted receiver identity and toggles. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("receiver", Context.MODE_PRIVATE)

    /** UDN value with `uuid:` prefix, generated once and stable across restarts. */
    fun uuid(): String {
        var value = prefs.getString(KEY_UUID, null)
        if (value == null) {
            value = "uuid:${UUID.randomUUID()}"
            prefs.edit().putString(KEY_UUID, value).apply()
        }
        return value
    }

    /** Friendly name shown in cast lists; defaults to the device model, never a product literal. */
    fun friendlyName(): String =
        prefs.getString(KEY_NAME, null) ?: Build.MODEL

    fun setFriendlyName(name: String) {
        prefs.edit().putString(KEY_NAME, name.trim().ifEmpty { Build.MODEL }).apply()
    }

    var autostart: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()

    companion object {
        private const val KEY_UUID = "uuid"
        private const val KEY_NAME = "friendly_name"
        private const val KEY_AUTOSTART = "autostart"
    }
}
