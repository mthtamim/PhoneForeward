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
        // v2.11: accent colour picked in General settings
        try {
            val prefs = getSharedPreferences("phonelink_cfg",
                android.content.Context.MODE_PRIVATE)
            when (prefs.getString("accent", "emerald")) {
                "blue" -> setTheme(R.style.Theme_PhoneLinkBlue)
                "purple" -> setTheme(R.style.Theme_PhoneLinkPurple)
                "amber" -> setTheme(R.style.Theme_PhoneLinkAmber)
                "rose" -> setTheme(R.style.Theme_PhoneLinkRose)
            }
        } catch (_: Exception) { }
        super.onCreate(savedInstanceState)
    }
}
