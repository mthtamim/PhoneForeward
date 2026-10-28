package com.mthtamim.phonelink

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/**
 * Base activity that applies the saved language and theme before the UI is
 * created. Language is read straight from the raw prefs because Config needs
 * a fully-built context; theme goes through AppCompatDelegate so the
 * DayNight theme follows it everywhere.
 */
open class LocalizedActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("phonelink_cfg", Context.MODE_PRIVATE)

        when (prefs.getString("theme_mode", "system")) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        val lang = prefs.getString("app_lang", "bn") ?: "bn"
        val wrapped = if (lang == "system") {
            newBase
        } else {
            val locale = Locale(lang)
            Locale.setDefault(locale)
            val cfg = Configuration(newBase.resources.configuration)
            cfg.setLocale(locale)
            newBase.createConfigurationContext(cfg)
        }
        super.attachBaseContext(wrapped)
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        // v2.25.0: optional app theme (Settings > App theme). A non-default
        // theme carries its own accent, so it takes precedence over the
        // v2.11 accent picker; the accent picker still applies exactly
        // as before when the theme is Emerald Dark (the default).
        // Each theme style is defined in BOTH values/themes.xml and
        // values-night/themes.xml so it survives light/dark/system mode.
        try {
            val prefs = getSharedPreferences("phonelink_cfg",
                android.content.Context.MODE_PRIVATE)
            when (prefs.getString("app_theme", "emerald_dark")) {
                "aurora" -> setTheme(R.style.Theme_PhoneLinkAurora)
                "ocean_deep" -> setTheme(R.style.Theme_PhoneLinkOceanDeep)
                "midnight_sapphire" -> setTheme(R.style.Theme_PhoneLinkMidnightSapphire)
                "sunset_ember" -> setTheme(R.style.Theme_PhoneLinkSunsetEmber)
                "rose_quartz" -> setTheme(R.style.Theme_PhoneLinkRoseQuartz)
                "forest_mist" -> setTheme(R.style.Theme_PhoneLinkForestMist)
                "graphite_mono" -> setTheme(R.style.Theme_PhoneLinkGraphiteMono)
                else -> {
                    // v2.11: accent colour picked in General settings
                    when (prefs.getString("accent", "emerald")) {
                        "blue" -> setTheme(R.style.Theme_PhoneLinkBlue)
                        "purple" -> setTheme(R.style.Theme_PhoneLinkPurple)
                        "amber" -> setTheme(R.style.Theme_PhoneLinkAmber)
                        "rose" -> setTheme(R.style.Theme_PhoneLinkRose)
                    }
                }
            }
        } catch (_: Exception) { }
        super.onCreate(savedInstanceState)
    }
}
