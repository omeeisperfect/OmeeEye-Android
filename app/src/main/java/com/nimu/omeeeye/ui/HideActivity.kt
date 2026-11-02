package com.nimu.omeeeye.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.HideStore
import com.nimu.omeeeye.engine.HideManager
import com.nimu.omeeeye.engine.TriggerEngine

/**
 * Hide Mode (feature 2): real screen, replacing the base-v2
 * placeholder.
 *
 * - "Hide app" saves the current editors, starts the trigger
 *   watchers (so unhide still works while hidden), disables the
 *   launcher icon, and removes the app from recents.
 * - The unhide editors (dialer code, volume switch, shake switch,
 *   SMS keyword, auto-unhide time) are saved with Save. Dialer
 *   code and SMS keyword are text, not locations — no coordinate
 *   input exists on this screen.
 * - The current hidden state is implicit: if you can see this
 *   screen, the app is visible. A stale stored "hidden" flag (for
 *   example after the user re-enabled the app from Settings >
 *   Apps by hand) is corrected on open from the component state.
 * - Honest notes at the bottom state the Android limits: Settings
 *   > Apps still lists the app, the trigger notification may show
 *   in the shade, phone lock fully stops a running script, volume
 *   unhide needs the accessibility service, and dialer-code unhide
 *   may be restricted on Android 10+.
 */
class HideActivity : AppCompatActivity() {

    private lateinit var dialerCodeEdit: EditText
    private lateinit var smsKeywordEdit: EditText
    private lateinit var volumeSwitch: Switch
    private lateinit var shakeSwitch: Switch
    private lateinit var autoUnhideText: TextView

    private var autoUnhideMinutes: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hide)

        dialerCodeEdit = findViewById(R.id.edit_dialer_code)
        smsKeywordEdit = findViewById(R.id.edit_unhide_sms)
        volumeSwitch = findViewById(R.id.switch_unhide_volume)
        shakeSwitch = findViewById(R.id.switch_unhide_shake)
        autoUnhideText = findViewById(R.id.text_auto_unhide)

        // Correct a stale stored flag: the launcher-component state
        // is the truth. If the component is enabled, the app is
        // visible, whatever the stored flag says.
        var stored = HideStore.get(this)
        if (stored.hidden && !HideManager.isHidden(this)) {
            stored = stored.copy(hidden = false)
            HideStore.save(this, stored)
        }
        dialerCodeEdit.setText(stored.dialerCode)
        smsKeywordEdit.setText(stored.smsKeyword)
        volumeSwitch.isChecked = stored.volumeEnabled
        shakeSwitch.isChecked = stored.shakeEnabled
        autoUnhideMinutes = stored.autoUnhideMinutes
        updateAutoUnhideLabel()

        findViewById<Button>(R.id.btn_auto_unhide_set).setOnClickListener {
            val initialHour = if (autoUnhideMinutes >= 0) autoUnhideMinutes / 60 else 9
            val initialMinute = if (autoUnhideMinutes >= 0) autoUnhideMinutes % 60 else 0
            TimePickerDialog(
                this,
                { _, hour, minute ->
                    autoUnhideMinutes = hour * 60 + minute
                    updateAutoUnhideLabel()
                },
                initialHour,
                initialMinute,
                DateFormat.is24HourFormat(this)
            ).show()
        }

        findViewById<Button>(R.id.btn_auto_unhide_off).setOnClickListener {
            autoUnhideMinutes = -1
            updateAutoUnhideLabel()
        }

        findViewById<Button>(R.id.btn_hide_save).setOnClickListener {
            persistEditors()
            TriggerEngine.startIfNeeded(this)
            Toast.makeText(this, R.string.hide_saved, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_hide_app).setOnClickListener {
            // Save first, so unhide triggers edited but not yet
            // saved still work after hiding. Start the watchers
            // before hiding, so shake / volume / SMS / time unhide
            // keep working while the icon is gone.
            persistEditors()
            TriggerEngine.startIfNeeded(this)
            HideManager.setHidden(this, true)
            finishAndRemoveTask()
        }
    }

    private fun updateAutoUnhideLabel() {
        autoUnhideText.text = if (autoUnhideMinutes < 0) {
            getString(R.string.hide_auto_unhide_off)
        } else {
            getString(
                R.string.hide_auto_unhide_format,
                autoUnhideMinutes / 60,
                autoUnhideMinutes % 60
            )
        }
    }

    /** Persists the editors, keeping the current hidden flag untouched. */
    private fun persistEditors() {
        val current = HideStore.get(this)
        HideStore.save(
            this,
            HideStore.HideSettings(
                hidden = current.hidden,
                dialerCode = dialerCodeEdit.text.toString().trim(),
                smsKeyword = smsKeywordEdit.text.toString().trim(),
                volumeEnabled = volumeSwitch.isChecked,
                shakeEnabled = shakeSwitch.isChecked,
                autoUnhideMinutes = autoUnhideMinutes
            )
        )
    }
}
