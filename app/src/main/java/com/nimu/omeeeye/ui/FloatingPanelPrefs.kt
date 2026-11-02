package com.nimu.omeeeye.ui

import android.content.Context

/**
 * Which floating-panel functions are shown (item 7). Stored as a
 * SharedPreferences set of function keys; default = every function
 * enabled, so existing panels look exactly as before until the user
 * changes something in Settings. The close handle is NOT governed by
 * these prefs: FloatingService always shows it, so the panel can never
 * be configured into a state with no way back to Settings.
 */
object FloatingPanelPrefs {

    private const val PREFS_NAME = "omeeeye_floating_panel"
    private const val KEY_ENABLED = "enabled_functions"

    const val FN_CONFIGURE = "configure"
    const val FN_PLAY = "play"
    const val FN_TARGET = "target"
    const val FN_EXPAND = "expand"
    const val FN_STOP = "stop"
    const val FN_PAUSE = "pause"
    const val FN_BACK = "back"
    const val FN_HOME = "home"
    const val FN_RECENTS = "recents"
    const val FN_MORE = "more"

    /** Every function FloatingService offers, in panel order. */
    val ALL: List<String> = listOf(
        FN_CONFIGURE, FN_PLAY, FN_TARGET, FN_EXPAND,
        FN_STOP, FN_PAUSE, FN_BACK, FN_HOME, FN_RECENTS, FN_MORE
    )

    fun isEnabled(context: Context, fn: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // No stored set yet = default all enabled (old behaviour).
        val stored = prefs.getStringSet(KEY_ENABLED, null) ?: return true
        return stored.contains(fn)
    }

    fun setEnabled(context: Context, fn: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_ENABLED, null)?.toMutableSet()
            ?: ALL.toMutableSet()
        if (enabled) current.add(fn) else current.remove(fn)
        prefs.edit().putStringSet(KEY_ENABLED, current).apply()
    }
}
