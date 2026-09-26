package com.compressor.audio

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import java.util.Locale

/** In-app language switch (Arabic default, English optional), persisted locally. */
object LocaleHelper {
    const val AR = "ar"
    const val EN = "en"
    private const val PREFS = "settings"
    private const val KEY = "lang"

    fun getLang(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, AR) ?: AR

    fun setLang(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, lang).apply()
    }

    fun wrap(context: Context): ContextWrapper {
        val lang = getLang(context)
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val res = context.resources
        val config = android.content.res.Configuration(res.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            config.setLayoutDirection(locale)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)
        val wrapped = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createConfigurationContext(config)
        } else {
            context
        }
        return ContextWrapper(wrapped)
    }
}
