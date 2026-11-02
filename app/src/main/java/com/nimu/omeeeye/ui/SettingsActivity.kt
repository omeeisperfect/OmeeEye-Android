package com.nimu.omeeeye.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nimu.omeeeye.R

/**
 * Permission status screen. Nothing is granted from here directly:
 * each button opens the matching system Settings screen, and the
 * statuses are re-read in onResume when the user comes back.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        // Component flatten form: package + "/.engine.AutomationService".
        private const val AUTOMATION_SERVICE_COMPONENT =
            "com.nimu.omeeeye/.engine.AutomationService"
    }

    private lateinit var accessibilityStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var batteryStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        accessibilityStatus = findViewById(R.id.accessibility_status)
        overlayStatus = findViewById(R.id.overlay_status)
        batteryStatus = findViewById(R.id.battery_status)

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_overlay).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    private fun refreshStatuses() {
        // (a) Accessibility: is this app's AutomationService enabled?
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val accessibilityOn = enabledServices
            .split(":")
            .any { it == AUTOMATION_SERVICE_COMPONENT }
        accessibilityStatus.text = if (accessibilityOn) {
            getString(R.string.accessibility_on)
        } else {
            getString(R.string.accessibility_off)
        }

        // (b) Display over other apps.
        overlayStatus.text = if (Settings.canDrawOverlays(this)) {
            getString(R.string.overlay_on)
        } else {
            getString(R.string.overlay_off)
        }

        // (c) Battery unrestricted.
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryStatus.text = if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            getString(R.string.battery_on)
        } else {
            getString(R.string.battery_off)
        }
    }
}
