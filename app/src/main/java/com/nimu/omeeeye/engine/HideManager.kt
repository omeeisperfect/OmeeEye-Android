package com.nimu.omeeeye.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.nimu.omeeeye.data.HideStore
import com.nimu.omeeeye.ui.MainActivity

/**
 * Hide Mode (feature 2): hides the app icon from the launcher by
 * disabling the launcher activity component, and brings it back by
 * re-enabling it.
 *
 * HONEST ANDROID LIMIT, stated here and in the Hide screen: disabling
 * the launcher component removes the icon from the launcher and the
 * app from the recents list (together with finishAndRemoveTask from
 * the UI), but Settings > Apps ALWAYS still lists the app. Android
 * provides no API for a normal app to hide itself from Settings >
 * Apps; any claim otherwise would be false. The app also stays
 * installed and its data stays on the device.
 *
 * The component state is the authoritative visibility signal: if the
 * user re-enables the app from Settings > Apps manually, [isHidden]
 * reports visible even if the stored flag is stale.
 */
object HideManager {

    private fun component(context: Context): ComponentName =
        ComponentName(context, MainActivity::class.java)

    /** True only when the launcher component is currently disabled. */
    fun isHidden(context: Context): Boolean {
        return try {
            context.packageManager.getComponentEnabledSetting(component(context)) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (e: Exception) {
            HideStore.get(context).hidden
        }
    }

    /**
     * Enables or disables the launcher icon. DONT_KILL_APP keeps the
     * running process (and its trigger service) alive while hiding.
     * Also persists the matching flag in HideStore.
     */
    fun setHidden(context: Context, hidden: Boolean) {
        try {
            context.packageManager.setComponentEnabledSetting(
                component(context),
                if (hidden) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                },
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            // If the PackageManager call fails, the icon state is
            // unchanged; still persist nothing that claims otherwise.
            return
        }
        val current = HideStore.get(context)
        HideStore.save(context, current.copy(hidden = hidden))
    }

    /**
     * Unhides and brings the app to the front. Callers that fire on
     * background events (see TriggerEngine.triggerUnhide) must check
     * [isHidden] first, so a stray event never pops the app open
     * while it is already visible.
     */
    fun unhide(context: Context) {
        setHidden(context, false)
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Component re-enabled above; the user can still open the
            // app from the launcher icon that just reappeared.
        }
    }
}
