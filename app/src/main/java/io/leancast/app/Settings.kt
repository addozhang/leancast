package io.leancast.app

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale
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

    /** Persisted UI language code: `"zh"` (Simplified Chinese, default) or `"en"` (English). */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "zh") ?: "zh"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, if (value == "en") "en" else "zh").apply()

    fun toggleLanguage(): String {
        val next = if (language == "zh") "en" else "zh"
        language = next
        return next
    }

    companion object {
        private const val KEY_UUID = "uuid"
        private const val KEY_NAME = "friendly_name"
        private const val KEY_AUTOSTART = "autostart"
        private const val KEY_LANGUAGE = "language"

        /** Wraps [base] context with the user's selected locale (`zh` or `en`). */
        fun wrapLocale(base: Context): Context {
            val lang = Settings(base).language
            val locale = if (lang == "en") Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            return base.createConfigurationContext(config)
        }
    }
}
