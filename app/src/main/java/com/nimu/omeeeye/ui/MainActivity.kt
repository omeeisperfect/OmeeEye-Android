package com.nimu.omeeeye.ui

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.TriggerStore
import com.nimu.omeeeye.data.WorkflowStore
import com.nimu.omeeeye.engine.TriggerEngine
import com.nimu.omeeeye.engine.WorkflowRunner
import com.nimu.omeeeye.model.Workflow

/**
 * Home screen: list of active (non-trashed) workflows.
 * Search here filters by workflow NAME only. It is not a coordinate field:
 * no X/Y input exists anywhere in this app (pick-only rule).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // Home-screen shortcut (item 2): the pinned shortcut's intent
        // carries the workflow id under this extra; onCreate and
        // onNewIntent both honour it through the existing run path.
        const val EXTRA_SHORTCUT_WORKFLOW_ID = "shortcut_workflow_id"
        private const val PREFS_NAME = "omeeeye_prefs"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val SORT_NEWEST = "newest"
        private const val SORT_NAME = "name"
        private const val MENU_SORT = 1
        private const val MENU_HISTORY = 2
        private const val MENU_SETTINGS = 3
        private const val MENU_TRASH = 4
        // Workflow tools (feature 12), appended after the existing items.
        // Duplicate already exists per workflow card and is untouched.
        private const val MENU_EXPORT = 5
        private const val MENU_IMPORT = 6
    }

    private lateinit var workflowList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var searchBox: EditText
    private lateinit var adapter: WorkflowAdapter

    private var allWorkflows: List<Workflow> = emptyList()
    private var searchQuery: String = ""

    // Workflow tools (feature 12): the workflow chosen in the picker
    // while the system file picker is open, and the two launchers.
    private var pendingExport: Workflow? = null

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val workflow = pendingExport
            pendingExport = null
            if (uri == null || workflow == null) return@registerForActivityResult
            writeExport(uri, workflow)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            importWorkflow(uri)
        }

    private var sortMode: String
        get() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_SORT_MODE, SORT_NEWEST) ?: SORT_NEWEST
        set(value) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(KEY_SORT_MODE, value).apply()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // One-time runner setup, as per the shared contract.
        WorkflowRunner.init(applicationContext)

        val btnSearch = findViewById<ImageButton>(R.id.btn_search)
        val btnMenu = findViewById<ImageButton>(R.id.btn_menu)
        val btnCreateTask = findViewById<Button>(R.id.btn_create_task)
        val btnClose = findViewById<Button>(R.id.btn_close)
        searchBox = findViewById(R.id.search_box)
        workflowList = findViewById(R.id.workflow_list)
        emptyText = findViewById(R.id.empty_text)

        adapter = WorkflowAdapter(object : WorkflowAdapter.Listener {
            override fun onEdit(workflow: Workflow) {
                val intent = Intent(this@MainActivity, TaskEditorActivity::class.java)
                intent.putExtra("workflow_id", workflow.id)
                startActivity(intent)
            }

            override fun onDuplicate(workflow: Workflow) {
                val copy = workflow.copy(
                    id = WorkflowStore.newId(),
                    name = workflow.name + " (copy)",
                    deleted = false
                )
                WorkflowStore.save(this@MainActivity, copy)
                reload()
            }

            override fun onDelete(workflow: Workflow) {
                WorkflowStore.delete(this@MainActivity, workflow.id)
                Toast.makeText(
                    this@MainActivity,
                    R.string.workflow_deleted,
                    Toast.LENGTH_SHORT
                ).show()
                reload()
            }

            override fun onFloatingBar(workflow: Workflow) {
                FloatingService.startIdle(this@MainActivity, workflow.id)
            }

            override fun onPlay(workflow: Workflow) {
                FloatingService.startRunning(this@MainActivity, workflow.id)
            }

            override fun onEditDetails(workflow: Workflow) {
                // Rename lives in the editor (its name field): reuse
                // the existing Edit path, no separate rename screen.
                val intent = Intent(this@MainActivity, TaskEditorActivity::class.java)
                intent.putExtra("workflow_id", workflow.id)
                startActivity(intent)
            }

            override fun onShare(workflow: Workflow) {
                shareWorkflow(workflow)
            }

            override fun onShortcut(workflow: Workflow) {
                pinShortcut(workflow)
            }

            override fun onTimed(workflow: Workflow) {
                showTimedDialog(workflow)
            }

            override fun onGesturePath(workflow: Workflow) {
                // False = overlay permission missing: the guide dialog
                // is already shown by the service launcher, nothing
                // else to do (never a fake drawing).
                GesturePathService.start(this@MainActivity, workflow.id)
            }
        })
        workflowList.layoutManager = LinearLayoutManager(this)
        workflowList.adapter = adapter

        btnSearch.setOnClickListener {
            if (searchBox.visibility == View.VISIBLE) {
                searchBox.visibility = View.GONE
                searchBox.setText("")
            } else {
                searchBox.visibility = View.VISIBLE
                searchBox.requestFocus()
            }
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString() ?: ""
                applyFilter()
            }
        })

        btnMenu.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, MENU_SORT, 0, R.string.menu_sort)
            popup.menu.add(0, MENU_HISTORY, 1, R.string.menu_history)
            popup.menu.add(0, MENU_SETTINGS, 2, R.string.menu_settings)
            popup.menu.add(0, MENU_TRASH, 3, R.string.menu_trash)
            popup.menu.add(0, MENU_EXPORT, 4, R.string.menu_export)
            popup.menu.add(0, MENU_IMPORT, 5, R.string.menu_import)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SORT -> {
                        // Cycle: Newest first <-> Name A-Z, and persist the choice.
                        sortMode = if (sortMode == SORT_NEWEST) SORT_NAME else SORT_NEWEST
                        applyFilter()
                        true
                    }
                    MENU_HISTORY -> {
                        startActivity(Intent(this, HistoryActivity::class.java))
                        true
                    }
                    MENU_SETTINGS -> {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        true
                    }
                    MENU_TRASH -> {
                        startActivity(Intent(this, TrashActivity::class.java))
                        true
                    }
                    MENU_EXPORT -> {
                        showExportPicker()
                        true
                    }
                    MENU_IMPORT -> {
                        // "*/*": exported .omeeeye.json files are often
                        // reported as application/octet-stream, which an
                        // "application/json" filter would hide. The JSON
                        // is still validated after reading, so a wrong
                        // file only produces a clean failure toast.
                        importLauncher.launch("*/*")
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        btnCreateTask.setOnClickListener {
            // No "workflow_id" extra = TaskEditorActivity creates a new workflow.
            startActivity(Intent(this, TaskEditorActivity::class.java))
        }

        btnClose.setOnClickListener { finish() }

        // Base-v2 bottom navigation: open the section screens.
        // Home is this screen, so its tab does nothing.
        // Nothing else on this screen changed: the top 3-dot menu,
        // search, cards, CREATE TASK, and CLOSE all work as before.
        findViewById<View>(R.id.nav_home).setOnClickListener { /* Already home. */ }
        findViewById<View>(R.id.nav_triggers).setOnClickListener {
            startActivity(Intent(this, TriggersActivity::class.java))
        }
        findViewById<View>(R.id.nav_hide).setOnClickListener {
            startActivity(Intent(this, HideActivity::class.java))
        }
        findViewById<View>(R.id.nav_library).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        findViewById<View>(R.id.nav_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Home-screen shortcut launch (item 2): a pinned shortcut
        // returns here with the workflow id; run it, same as Play.
        handleShortcutIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    /** Runs the workflow named by a pinned shortcut, if it still exists. */
    private fun handleShortcutIntent(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_SHORTCUT_WORKFLOW_ID)
            ?: return
        if (id.isBlank()) return
        val workflow = WorkflowStore.get(this, id)
        if (workflow == null || workflow.deleted) {
            Toast.makeText(
                this, R.string.shortcut_not_found, Toast.LENGTH_LONG
            ).show()
            return
        }
        // Existing run path, identical to tapping Play on the card.
        FloatingService.startRunning(this, workflow.id)
    }

    /** Home-screen shortcut (item 2) via ShortcutManager.requestPinShortcut. */
    private fun pinShortcut(workflow: Workflow) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(
                this, R.string.shortcut_not_supported, Toast.LENGTH_LONG
            ).show()
            return
        }
        val manager = getSystemService(ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) {
            Toast.makeText(
                this, R.string.shortcut_not_supported, Toast.LENGTH_LONG
            ).show()
            return
        }
        val shortcutIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_SHORTCUT_WORKFLOW_ID, workflow.id)
        }
        val info = ShortcutInfo.Builder(this, workflow.id)
            .setShortLabel(workflow.name.ifBlank { getString(R.string.app_name) })
            .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(shortcutIntent)
            .build()
        try {
            manager.requestPinShortcut(info, null)
            Toast.makeText(
                this, R.string.shortcut_created, Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this, R.string.shortcut_not_supported, Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Share task (item 5): system share sheet, exported JSON + name. */
    private fun shareWorkflow(workflow: Workflow) {
        try {
            val text = workflow.name + "\n\n" +
                WorkflowStore.workflowToJson(workflow).toString()
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, workflow.name)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(
                Intent.createChooser(share, getString(R.string.share_task_title))
            )
        } catch (e: Exception) {
            Toast.makeText(
                this, R.string.share_task_failed, Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Timed execution (item 4): time picker -> TIME trigger. */
    private fun showTimedDialog(workflow: Workflow) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val picker = TimePicker(this).apply {
            setIs24HourView(true)
        }
        layout.addView(picker)
        layout.addView(
            TextView(this).apply {
                text = getString(R.string.timed_note)
                val smallPad = (8 * density).toInt()
                setPadding(0, smallPad, 0, 0)
            }
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.timed_title)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val hour = picker.hour
                val minute = picker.minute
                val trigger = TriggerStore.TriggerConfig(
                    id = TriggerStore.newId(),
                    kind = TriggerStore.TriggerKind.TIME,
                    workflowId = workflow.id,
                    enabled = true,
                    timeMinutes = hour * 60 + minute
                )
                TriggerStore.save(this, trigger)
                TriggerEngine.rescheduleTimeTriggers(this)
                Toast.makeText(
                    this,
                    getString(
                        R.string.timed_set,
                        String.format("%02d:%02d", hour, minute)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- Workflow tools: Export / Import (feature 12) ----------

    /** Picker of active workflows, then the system file picker. */
    private fun showExportPicker() {
        val workflows = WorkflowStore.loadActive(this)
        if (workflows.isEmpty()) {
            Toast.makeText(this, R.string.tools_no_workflows, Toast.LENGTH_SHORT).show()
            return
        }
        val names = workflows.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.tools_choose_workflow)
            .setItems(names) { _, which ->
                val workflow = workflows[which]
                pendingExport = workflow
                // Slashes would break a file name; anything else is kept.
                val safeName = workflow.name
                    .replace('/', '_').replace('\\', '_')
                    .ifBlank { "workflow" }
                exportLauncher.launch("$safeName.omeeeye.json")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Writes ONLY workflow data (name, steps, data fields) as JSON.
     * No personal data and no device info is ever in a workflow object,
     * so nothing else can leak into the file.
     */
    private fun writeExport(uri: Uri, workflow: Workflow) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(WorkflowStore.workflowToJson(workflow).toString().toByteArray())
            } ?: throw IllegalStateException("No output stream")
            Toast.makeText(this, R.string.tools_exported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.tools_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Imports one workflow from JSON. The imported workflow always gets
     * a FRESH id, so an import can never overwrite an existing workflow.
     * Data fields (dataType, dataFileName, startRow, dataRepeat) are kept
     * exactly as exported, even if dataFileName points to a file that does
     * not exist on this phone — they are NOT silently dropped. If the
     * user runs such a workflow, the runner fails cleanly at that point
     * (its data file cannot be loaded), instead of the import lying
     * about what was imported.
     */
    private fun importWorkflow(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()
                ?.use { it.readText() } ?: throw IllegalStateException("No input")
            val imported = WorkflowStore.stepWorkflowFromJson(
                org.json.JSONObject(text)
            )
            val fresh = imported.copy(
                id = WorkflowStore.newId(),
                deleted = false
            )
            WorkflowStore.save(this, fresh)
            Toast.makeText(this, R.string.tools_imported, Toast.LENGTH_SHORT).show()
            reload()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.tools_import_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        allWorkflows = WorkflowStore.loadActive(this)
        applyFilter()
    }

    private fun applyFilter() {
        var filtered = allWorkflows
        if (searchQuery.isNotBlank()) {
            filtered = filtered.filter {
                it.name.contains(searchQuery, ignoreCase = true)
            }
        }
        filtered = when (sortMode) {
            SORT_NAME -> filtered.sortedBy { it.name.lowercase() }
            else -> filtered.sortedByDescending { it.updatedAt }
        }
        adapter.submitList(filtered)
        emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        workflowList.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }
}
