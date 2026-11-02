package com.nimu.omeeeye.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nimu.omeeeye.R

/**
 * Permission status screen. Nothing is granted from here directly:
 * each button opens the matching system Settings screen, and the
 * statuses are re-read in onResume when the user comes back.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var accessibilityStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var batteryStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Appearance: Light / Dark (AMOLED) / System default.
        // The radio is checked before the listener is attached, so the
        // initial check does not trigger a mode change or a recreate.
        val themeGroup = findViewById<RadioGroup>(R.id.theme_group)
        when (ThemePrefs.getMode(this)) {
            ThemePrefs.MODE_LIGHT -> themeGroup.check(R.id.theme_light)
            ThemePrefs.MODE_DARK -> themeGroup.check(R.id.theme_dark)
            else -> themeGroup.check(R.id.theme_system)
        }
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.theme_light -> ThemePrefs.MODE_LIGHT
                R.id.theme_dark -> ThemePrefs.MODE_DARK
                else -> ThemePrefs.MODE_SYSTEM
            }
            if (mode != ThemePrefs.getMode(this)) {
                ThemePrefs.setMode(this, mode)
                recreate()
            }
        }

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

        addFloatingPanelSection()
    }

    /**
     * Floating panel chooser (item 7), added programmatically (no
     * layout-file change): one checkbox per function FloatingService
     * offers, in panel order. Choices persist in FloatingPanelPrefs
     * and apply the next time the panel is shown. Close, Expand, Stop
     * and More have checkboxes too, but FloatingService keeps those
     * four visible regardless, so unchecking everything can never
     * strand the user without a way back.
     */
    private fun addFloatingPanelSection() {
        val form = findViewById<TextView>(R.id.title_text).parent as LinearLayout
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.row_card)
            elevation = 4 * density
            setPadding(pad, pad, pad, pad)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = (24 * density).toInt()
            layoutParams = params
        }
        section.addView(
            TextView(this).apply {
                text = getString(R.string.floating_panel_title)
                setTextColor(resources.getColor(R.color.text_primary, theme))
                textSize = 17f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
        )
        section.addView(
            TextView(this).apply {
                text = getString(R.string.floating_panel_desc)
                setTextColor(resources.getColor(R.color.text_secondary, theme))
                textSize = 14f
                val smallPad = (4 * density).toInt()
                setPadding(0, smallPad, 0, smallPad)
            }
        )
        val labels = mapOf(
            FloatingPanelPrefs.FN_CONFIGURE to R.string.floating_fn_configure,
            FloatingPanelPrefs.FN_PLAY to R.string.floating_fn_play,
            FloatingPanelPrefs.FN_TARGET to R.string.floating_fn_target,
            FloatingPanelPrefs.FN_EXPAND to R.string.floating_fn_expand,
            FloatingPanelPrefs.FN_STOP to R.string.floating_fn_stop,
            FloatingPanelPrefs.FN_PAUSE to R.string.floating_fn_pause,
            FloatingPanelPrefs.FN_BACK to R.string.floating_fn_back,
            FloatingPanelPrefs.FN_HOME to R.string.floating_fn_home,
            FloatingPanelPrefs.FN_RECENTS to R.string.floating_fn_recents,
            FloatingPanelPrefs.FN_MORE to R.string.floating_fn_more
        )
        for (fn in FloatingPanelPrefs.ALL) {
            val labelRes = labels[fn] ?: continue
            val box = CheckBox(this).apply {
                text = getString(labelRes)
                setTextColor(resources.getColor(R.color.text_primary, theme))
                buttonTintList = resources.getColorStateList(R.color.accent, theme)
                textSize = 15f
                isChecked = FloatingPanelPrefs.isEnabled(this@SettingsActivity, fn)
            }
            box.setOnCheckedChangeListener { _, checked ->
                FloatingPanelPrefs.setEnabled(this, fn, checked)
            }
            section.addView(box)
        }
        form.addView(section)
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    private fun refreshStatuses() {
        // (a) Accessibility: is this app's AutomationService enabled?
        // Fixed detection (see PermissionStatus): the old code compared
        // the enabled-services string against only the SHORT flattened
        // form, so on devices/ROMs that store the FULL form it always
        // reported OFF even with the service turned on.
        val accessibilityOn = PermissionStatus.isAccessibilityEnabled(this)
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
