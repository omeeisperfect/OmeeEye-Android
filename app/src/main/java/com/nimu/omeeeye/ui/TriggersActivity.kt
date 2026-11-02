package com.nimu.omeeeye.ui

import android.Manifest
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.TriggerStore
import com.nimu.omeeeye.data.WorkflowStore
import com.nimu.omeeeye.engine.AutomationService
import com.nimu.omeeeye.engine.TriggerEngine
import com.nimu.omeeeye.engine.WorkflowRunner

/**
 * Triggers (feature 1): real screen, replacing the base-v2
 * placeholder. Every row is a working trigger: kind,
 * mapped workflow, condition summary, enabled switch, Test button
 * (runs the mapped workflow immediately), and Delete.
 *
 * Add (and tap-to-edit) walks one dialog at a time: trigger type,
 * workflow, condition for that type, then an enabled toggle with
 * Save. The workflow picker is never shown empty: when no workflow
 * exists at all, Add stops with a clear message instead.
 *
 * Condition editors use pickers, never typed locations (there are
 * no coordinates anywhere here): TimePicker for TIME, a keyword
 * text field for SMS (text entry is allowed — it is not a
 * location), the installed-app picker for NOTIFICATION (same
 * pattern as the launch-app picker in the task editor), a
 * single-choice sensitivity list for SHAKE, and a NumberPicker
 * (5..100) for BATTERY.
 *
 * Test never fakes a run: with the accessibility service off it
 * shows a Toast guiding to Settings, and while another run is
 * active it says so instead of pretending to start.
 */
class TriggersActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_SMS = 1001
    }

    private lateinit var triggerList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: TriggerAdapter

    private var triggers: List<TriggerStore.TriggerConfig> = emptyList()
    private var workflowNames: Map<String, String> = emptyMap()

    // Draft carried through the add/edit dialog chain.
    private class TriggerDraft(
        var kind: TriggerStore.TriggerKind,
        var workflowId: String,
        var timeMinutes: Int,
        var smsKeyword: String,
        var notifPackage: String,
        var shakeSensitivity: Int,
        var batteryPct: Int,
        var enabled: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_triggers)
        WorkflowRunner.init(applicationContext)

        triggerList = findViewById(R.id.trigger_list)
        emptyText = findViewById(R.id.trigger_empty_text)
        adapter = TriggerAdapter(object : Listener {
            override fun onToggle(trigger: TriggerStore.TriggerConfig, enabled: Boolean) {
                TriggerStore.save(this@TriggersActivity, trigger.copy(enabled = enabled))
                TriggerEngine.startIfNeeded(this@TriggersActivity)
                reload()
            }

            override fun onTest(trigger: TriggerStore.TriggerConfig) {
                testTrigger(trigger)
            }

            override fun onDelete(trigger: TriggerStore.TriggerConfig) {
                AlertDialog.Builder(this@TriggersActivity)
                    .setTitle(R.string.trigger_delete_title)
                    .setMessage(R.string.trigger_delete_confirm)
                    .setPositiveButton(R.string.trigger_delete) { _, _ ->
                        TriggerStore.delete(this@TriggersActivity, trigger.id)
                        TriggerEngine.startIfNeeded(this@TriggersActivity)
                        Toast.makeText(
                            this@TriggersActivity,
                            R.string.trigger_deleted,
                            Toast.LENGTH_SHORT
                        ).show()
                        reload()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            override fun onEdit(trigger: TriggerStore.TriggerConfig) {
                showTriggerDialog(trigger)
            }
        })
        triggerList.layoutManager = LinearLayoutManager(this)
        triggerList.adapter = adapter

        findViewById<Button>(R.id.btn_add_trigger).setOnClickListener {
            // Never show an empty workflow picker: abort here, with
            // a clear message, when no workflow exists at all.
            if (WorkflowStore.loadActive(this).isEmpty()) {
                Toast.makeText(this, R.string.trigger_no_workflows, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            showTriggerDialog(null)
        }

        findViewById<Button>(R.id.btn_perm_accessibility).setOnClickListener {
            openSettingsScreen(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_perm_notification).setOnClickListener {
            openSettingsScreen(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btn_perm_sms).setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, R.string.trigger_sms_granted, Toast.LENGTH_SHORT).show()
            } else {
                requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), REQUEST_SMS)
            }
        }
        findViewById<Button>(R.id.btn_perm_battery).setOnClickListener {
            openSettingsScreen(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_SMS) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                if (granted) R.string.trigger_sms_granted else R.string.trigger_sms_denied,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openSettingsScreen(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.trigger_settings_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun reload() {
        triggers = TriggerStore.loadAll(this)
        val active = WorkflowStore.loadActive(this)
        workflowNames = active.associate { it.id to it.name }
        adapter.submit(triggers, workflowNames)
        emptyText.visibility = if (triggers.isEmpty()) View.VISIBLE else View.GONE
        triggerList.visibility = if (triggers.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun testTrigger(trigger: TriggerStore.TriggerConfig) {
        val workflow = WorkflowStore.get(this, trigger.workflowId)
        if (workflow == null) {
            Toast.makeText(this, R.string.trigger_test_no_workflow, Toast.LENGTH_LONG).show()
            return
        }
        if (AutomationService.instance == null) {
            Toast.makeText(
                this,
                R.string.trigger_test_needs_accessibility,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (WorkflowRunner.isRunning()) {
            Toast.makeText(this, R.string.trigger_test_running, Toast.LENGTH_LONG).show()
            return
        }
        WorkflowRunner.init(applicationContext)
        WorkflowRunner.start(workflow)
        Toast.makeText(this, R.string.trigger_test_started, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    // Add / edit dialog chain
    // ------------------------------------------------------------------

    private fun showTriggerDialog(existing: TriggerStore.TriggerConfig?) {
        val draft = if (existing != null) {
            TriggerDraft(
                kind = existing.kind,
                workflowId = existing.workflowId,
                timeMinutes = existing.timeMinutes,
                smsKeyword = existing.smsKeyword,
                notifPackage = existing.notifPackage,
                shakeSensitivity = existing.shakeSensitivity,
                batteryPct = existing.batteryPct,
                enabled = existing.enabled
            )
        } else {
            TriggerDraft(
                kind = TriggerStore.TriggerKind.TIME,
                workflowId = "",
                timeMinutes = -1,
                smsKeyword = "",
                notifPackage = "",
                shakeSensitivity = 2,
                batteryPct = 20,
                enabled = true
            )
        }
        showKindDialog(draft, existing?.id)
    }

    private fun showKindDialog(draft: TriggerDraft, existingId: String?) {
        val kinds = listOf(
            TriggerStore.TriggerKind.TIME,
            TriggerStore.TriggerKind.SMS,
            TriggerStore.TriggerKind.NOTIFICATION,
            TriggerStore.TriggerKind.SHAKE,
            TriggerStore.TriggerKind.BATTERY
        )
        val labels = kinds.map { kindLabel(it) }.toTypedArray()
        var selected = kinds.indexOf(draft.kind).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.trigger_dialog_kind)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.ok) { _, _ ->
                draft.kind = kinds[selected]
                showWorkflowDialog(draft, existingId)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showWorkflowDialog(draft: TriggerDraft, existingId: String?) {
        val workflows = WorkflowStore.loadActive(this)
        if (workflows.isEmpty()) {
            Toast.makeText(this, R.string.trigger_no_workflows, Toast.LENGTH_LONG).show()
            return
        }
        val labels = workflows.map { it.name }.toTypedArray()
        var selected = workflows.indexOfFirst { it.id == draft.workflowId }
        AlertDialog.Builder(this)
            .setTitle(R.string.trigger_dialog_workflow)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.ok) { _, _ ->
                if (selected < 0) {
                    // No workflow picked: save nothing, and say so
                    // plainly instead of pretending a trigger exists.
                    Toast.makeText(
                        this,
                        R.string.trigger_pick_first,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                draft.workflowId = workflows[selected].id
                showConditionDialog(draft, existingId)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConditionDialog(draft: TriggerDraft, existingId: String?) {
        when (draft.kind) {
            TriggerStore.TriggerKind.TIME -> {
                val initialHour = if (draft.timeMinutes >= 0) draft.timeMinutes / 60 else 9
                val initialMinute = if (draft.timeMinutes >= 0) draft.timeMinutes % 60 else 0
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        draft.timeMinutes = hour * 60 + minute
                        showSaveDialog(draft, existingId)
                    },
                    initialHour,
                    initialMinute,
                    DateFormat.is24HourFormat(this)
                ).show()
            }
            TriggerStore.TriggerKind.SMS -> {
                val input = EditText(this).apply {
                    setText(draft.smsKeyword)
                    hint = getString(R.string.trigger_dialog_sms_hint)
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.trigger_dialog_sms_keyword)
                    .setView(input)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        val keyword = input.text.toString().trim()
                        if (keyword.isBlank()) {
                            Toast.makeText(
                                this,
                                R.string.trigger_keyword_empty,
                                Toast.LENGTH_LONG
                            ).show()
                            return@setPositiveButton
                        }
                        draft.smsKeyword = keyword
                        showSaveDialog(draft, existingId)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            TriggerStore.TriggerKind.NOTIFICATION -> {
                showAppPickerDialog(draft, existingId)
            }
            TriggerStore.TriggerKind.SHAKE -> {
                val labels = arrayOf(
                    getString(R.string.trigger_sensitivity_gentle),
                    getString(R.string.trigger_sensitivity_normal),
                    getString(R.string.trigger_sensitivity_firm)
                )
                var selected = (draft.shakeSensitivity - 1).coerceIn(0, 2)
                AlertDialog.Builder(this)
                    .setTitle(R.string.trigger_dialog_shake)
                    .setSingleChoiceItems(labels, selected) { _, which ->
                        selected = which
                    }
                    .setPositiveButton(R.string.ok) { _, _ ->
                        draft.shakeSensitivity = selected + 1
                        showSaveDialog(draft, existingId)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            TriggerStore.TriggerKind.BATTERY -> {
                val picker = NumberPicker(this).apply {
                    minValue = 5
                    maxValue = 100
                    value = draft.batteryPct.coerceIn(5, 100)
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.trigger_dialog_battery)
                    .setView(picker)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        draft.batteryPct = picker.value
                        showSaveDialog(draft, existingId)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /** Installed-app picker, same pattern as the launch-app picker. */
    private fun showAppPickerDialog(draft: TriggerDraft, existingId: String?) {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launchIntent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launchIntent, 0)
        }.sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.trigger_apps_empty, Toast.LENGTH_LONG).show()
            return
        }
        val labels = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        var selected = apps.indexOfFirst {
            it.activityInfo?.packageName == draft.notifPackage
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_app)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.ok) { _, _ ->
                if (selected < 0) {
                    Toast.makeText(
                        this,
                        R.string.trigger_pick_first,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                val packageName = apps[selected].activityInfo?.packageName
                    ?: return@setPositiveButton
                draft.notifPackage = packageName
                showSaveDialog(draft, existingId)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSaveDialog(draft: TriggerDraft, existingId: String?) {
        val workflowName = workflowNames[draft.workflowId]
            ?: WorkflowStore.get(this, draft.workflowId)?.name
            ?: getString(R.string.trigger_no_workflow)
        val summary = kindLabel(draft.kind) + " → " + workflowName + "\n" +
            summaryFor(
                draft.kind, draft.timeMinutes, draft.smsKeyword,
                draft.notifPackage, draft.shakeSensitivity, draft.batteryPct
            )
        val enabledBox = CheckBox(this).apply {
            text = getString(R.string.trigger_enabled)
            isChecked = draft.enabled
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.trigger_dialog_save_title)
            .setMessage(summary)
            .setView(enabledBox)
            .setPositiveButton(R.string.trigger_save) { _, _ ->
                draft.enabled = enabledBox.isChecked
                val trigger = TriggerStore.TriggerConfig(
                    id = existingId ?: TriggerStore.newId(),
                    kind = draft.kind,
                    workflowId = draft.workflowId,
                    enabled = draft.enabled,
                    timeMinutes = draft.timeMinutes,
                    smsKeyword = draft.smsKeyword,
                    notifPackage = draft.notifPackage,
                    shakeSensitivity = draft.shakeSensitivity,
                    batteryPct = draft.batteryPct
                )
                TriggerStore.save(this, trigger)
                TriggerEngine.startIfNeeded(this)
                Toast.makeText(this, R.string.trigger_saved, Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Labels and summaries
    // ------------------------------------------------------------------

    private fun kindLabel(kind: TriggerStore.TriggerKind): String =
        getString(
            when (kind) {
                TriggerStore.TriggerKind.TIME -> R.string.trigger_time
                TriggerStore.TriggerKind.SMS -> R.string.trigger_sms
                TriggerStore.TriggerKind.NOTIFICATION -> R.string.trigger_notification
                TriggerStore.TriggerKind.SHAKE -> R.string.trigger_shake
                TriggerStore.TriggerKind.BATTERY -> R.string.trigger_battery
            }
        )

    private fun sensitivityLabel(sensitivity: Int): String =
        getString(
            when (sensitivity) {
                1 -> R.string.trigger_sensitivity_gentle
                3 -> R.string.trigger_sensitivity_firm
                else -> R.string.trigger_sensitivity_normal
            }
        )

    private fun appLabel(packageName: String): String {
        if (packageName.isBlank()) return packageName
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun summaryFor(
        kind: TriggerStore.TriggerKind,
        timeMinutes: Int,
        smsKeyword: String,
        notifPackage: String,
        shakeSensitivity: Int,
        batteryPct: Int
    ): String {
        return when (kind) {
            TriggerStore.TriggerKind.TIME -> {
                if (timeMinutes < 0) getString(R.string.trigger_summary_unknown)
                else getString(
                    R.string.trigger_summary_time,
                    timeMinutes / 60,
                    timeMinutes % 60
                )
            }
            TriggerStore.TriggerKind.SMS ->
                getString(R.string.trigger_summary_sms, smsKeyword)
            TriggerStore.TriggerKind.NOTIFICATION ->
                getString(R.string.trigger_summary_notification, appLabel(notifPackage))
            TriggerStore.TriggerKind.SHAKE ->
                getString(R.string.trigger_summary_shake, sensitivityLabel(shakeSensitivity))
            TriggerStore.TriggerKind.BATTERY ->
                getString(R.string.trigger_summary_battery, batteryPct)
        }
    }

    // ------------------------------------------------------------------
    // Row adapter (kept inside this file: no other feature owns it)
    // ------------------------------------------------------------------

    // Row-action callbacks. Declared at activity level (not inside the
    // inner adapter class): Kotlin does not allow an interface inside
    // an inner class ("Interface is not allowed here" compile error).
    interface Listener {
        fun onToggle(trigger: TriggerStore.TriggerConfig, enabled: Boolean)
        fun onTest(trigger: TriggerStore.TriggerConfig)
        fun onDelete(trigger: TriggerStore.TriggerConfig)
        fun onEdit(trigger: TriggerStore.TriggerConfig)
    }

    private inner class TriggerAdapter(
        private val listener: Listener
    ) : RecyclerView.Adapter<TriggerAdapter.ViewHolder>() {

        private var items: List<TriggerStore.TriggerConfig> = emptyList()
        private var names: Map<String, String> = emptyMap()

        fun submit(
            items: List<TriggerStore.TriggerConfig>,
            names: Map<String, String>
        ) {
            this.items = items
            this.names = names
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val kindText: TextView = view.findViewById(R.id.trigger_kind)
            val workflowText: TextView = view.findViewById(R.id.trigger_workflow)
            val conditionText: TextView = view.findViewById(R.id.trigger_condition)
            val enabledSwitch: Switch = view.findViewById(R.id.trigger_enabled)
            val testButton: Button = view.findViewById(R.id.btn_trigger_test)
            val deleteButton: Button = view.findViewById(R.id.btn_trigger_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_trigger_row, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val trigger = items[position]
            holder.kindText.text = kindLabel(trigger.kind)
            holder.workflowText.text = names[trigger.workflowId]
                ?: getString(R.string.trigger_no_workflow)
            holder.conditionText.text = summaryFor(
                trigger.kind, trigger.timeMinutes, trigger.smsKeyword,
                trigger.notifPackage, trigger.shakeSensitivity, trigger.batteryPct
            )
            // Detach the listener before setting the state, so
            // recycling a row never fires a phantom toggle.
            holder.enabledSwitch.setOnCheckedChangeListener(null)
            holder.enabledSwitch.isChecked = trigger.enabled
            holder.enabledSwitch.setOnCheckedChangeListener { _, checked ->
                listener.onToggle(trigger, checked)
            }
            holder.testButton.setOnClickListener { listener.onTest(trigger) }
            holder.deleteButton.setOnClickListener { listener.onDelete(trigger) }
            holder.itemView.setOnClickListener { listener.onEdit(trigger) }
        }
    }
}
