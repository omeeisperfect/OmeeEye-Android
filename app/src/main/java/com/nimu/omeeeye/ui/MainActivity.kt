package com.nimu.omeeeye.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.WorkflowStore
import com.nimu.omeeeye.engine.WorkflowRunner
import com.nimu.omeeeye.model.Workflow

/**
 * Home screen: list of active (non-trashed) workflows.
 * Search here filters by workflow NAME only. It is not a coordinate field:
 * no X/Y input exists anywhere in this app (pick-only rule).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "omeeeye_prefs"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val SORT_NEWEST = "newest"
        private const val SORT_NAME = "name"
        private const val MENU_SORT = 1
        private const val MENU_HISTORY = 2
        private const val MENU_SETTINGS = 3
        private const val MENU_TRASH = 4
    }

    private lateinit var workflowList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var searchBox: EditText
    private lateinit var adapter: WorkflowAdapter

    private var allWorkflows: List<Workflow> = emptyList()
    private var searchQuery: String = ""

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
