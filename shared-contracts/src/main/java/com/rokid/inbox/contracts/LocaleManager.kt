package com.rokid.inbox.contracts

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Per-app UI language (English / Portuguese). Stored in a lightweight plain
 * SharedPreferences and applied by wrapping the base context in each Activity's
 * `attachBaseContext`, so every screen and the phone controller share one locale.
 *
 * The phone owns the setting and pushes it to the glasses over the wire
 * (`PhoneToGlassesMessage.SetLocale`); both sides persist it here.
 */
object LocaleManager {
    const val EN = "en"
    const val PT = "pt"
    val SUPPORTED = listOf(EN, PT)

    private const val PREFS = "inbox_locale"
    private const val KEY = "lang"

    fun stored(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    /** Current language: the saved one, or the device default mapped to en/pt. */
    fun current(context: Context): String = normalize(stored(context) ?: deviceDefault())

    /** Persist a language choice. Returns true if it actually changed. */
    fun save(context: Context, lang: String): Boolean {
        val next = normalize(lang)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY, null) == next) return false
        prefs.edit().putString(KEY, next).apply()
        return true
    }

    /** Wrap a base context so `getString(...)` resolves in the chosen language. */
    fun wrap(base: Context): Context {
        val locale = Locale(current(base))
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    fun displayName(lang: String): String = when (normalize(lang)) {
        PT -> "Portugues"
        else -> "English"
    }

    private fun deviceDefault(): String = if (Locale.getDefault().language == "pt") PT else EN

    private fun normalize(lang: String): String = if (lang.lowercase().startsWith("pt")) PT else EN
}
