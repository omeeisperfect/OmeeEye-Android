package com.nimu.omeeeye.ui

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Appearance choice only: Light / Dark (AMOLED) / System default.
 *
 * Stored in the existing "omeeeye_prefs" SharedPreferences file (the same
 * file MainActivity already uses for sort mode) under a new key, so no
 * existing preference is touched. Applied once at app start by
 * OmeeEyeApplication, so every activity follows the choice without any
 * workflow, runner, pick, or record behaviour changing.
 */
object ThemePrefs {
    private const val PREFS_NAME = "omeeeye_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"
    const val MODE_SYSTEM = "system"

    fun getMode(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, MODE_SYSTEM) ?: MODE_SYSTEM
    }

    fun setMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME_MODE, mode).apply()
        apply(context)
    }

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (getMode(context)) {
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}

/**
 * Minimal theme-application hook: applies the stored appearance choice
 * before any activity is created. No other behaviour lives here.
 */
class OmeeEyeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePrefs.apply(this)
    }
}
