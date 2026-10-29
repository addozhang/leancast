package io.leancast.app

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings as AndroidSettings
import io.leancast.domain.ssdp.DeviceUuid
import java.util.Locale
import java.util.UUID

/** Persisted receiver identity and toggles. */
class Settings(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences("receiver", Context.MODE_PRIVATE)

    /**
     * UDN value with `uuid:` prefix.
     *
     * Derived from the platform `ANDROID_ID` rather than random, so a reinstall
     * (which wipes this prefs file) keeps the same identity instead of showing
     * up as a new device in cast lists. Falls back to a random UUID on devices
     * that report no usable id.
     */
    fun uuid(): String {
        prefs.getString(KEY_UUID, null)?.let { return it }
        val value = DeviceUuid.derive(androidId()) ?: "uuid:${UUID.randomUUID()}"
        prefs.edit().putString(KEY_UUID, value).apply()
        return value
    }

    /** `ANDROID_ID`: no permission needed, stable across reinstalls on API 26+. */
    private fun androidId(): String? = runCatching {
        AndroidSettings.Secure.getString(appContext.contentResolver, AndroidSettings.Secure.ANDROID_ID)
    }.getOrNull()

    /** Friendly name shown in cast lists; defaults to `@string/app_name` ("LeanCast"). */
    fun friendlyName(): String {
        val defaultName = appContext.getString(R.string.app_name)
        return prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultName
    }

    fun setFriendlyName(name: String) {
        val defaultName = appContext.getString(R.string.app_name)
        prefs.edit().putString(KEY_NAME, name.trim().ifEmpty { defaultName }).apply()
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
