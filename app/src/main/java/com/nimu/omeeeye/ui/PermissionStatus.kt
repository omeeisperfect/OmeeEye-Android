package com.nimu.omeeeye.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.nimu.omeeeye.engine.AutomationService
import com.nimu.omeeeye.engine.TriggerNotificationService

/**
 * Single source of truth for "is this permission really granted?".
 * Every check below reads live system state — nothing here is ever
 * hardcoded as granted, so a UI state built on these functions can
 * only show granted when the grant truly exists.
 *
 * Accessibility detection is deliberately dual-format: Android
 * versions and OEM ROMs store the enabled-service component in
 * either the full flattened form
 * ("com.nimu.omeeeye/com.nimu.omeeeye.engine.AutomationService")
 * or the short form ("com.nimu.omeeeye/.engine.AutomationService").
 * Comparing against only one form reports OFF on devices that store
 * the other — the exact bug this helper fixes. The
 * AccessibilityManager list is checked as a second source.
 */
object PermissionStatus {

    fun isAccessibilityEnabled(context: Context): Boolean {
        val component = ComponentName(context, AutomationService::class.java)
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        val stored = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        if (stored.split(":").any { it == full || it == short }) return true
        return try {
            val manager =
                context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any {
                it.id == full || it.id == short ||
                    (it.resolveInfo?.serviceInfo?.packageName == context.packageName &&
                        it.resolveInfo?.serviceInfo?.name == AutomationService::class.java.name)
            }
        } catch (e: Exception) {
            false
        }
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val component = ComponentName(context, TriggerNotificationService::class.java)
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        val stored = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_NOTIFICATION_LISTENERS
        ) ?: ""
        return stored.split(":").any { entry ->
            entry == full || entry == short ||
                (entry.startsWith(context.packageName + "/") &&
                    entry.contains(TriggerNotificationService::class.java.simpleName))
        }
    }

    fun isSmsGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun isBatteryUnrestricted(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
}
