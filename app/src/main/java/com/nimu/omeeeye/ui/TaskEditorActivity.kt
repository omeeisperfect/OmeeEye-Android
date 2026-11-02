package com.nimu.omeeeye.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.NumberPicker
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.CodeLibraryStore
import com.nimu.omeeeye.data.DataFileStore
import com.nimu.omeeeye.data.DataSourceLoader
import com.nimu.omeeeye.data.WorkflowStore
import com.nimu.omeeeye.engine.WorkflowRunner
import com.nimu.omeeeye.model.MistakeLevel
import com.nimu.omeeeye.model.NavKey
import com.nimu.omeeeye.model.Point
import com.nimu.omeeeye.model.Step
import com.nimu.omeeeye.model.StepType
import com.nimu.omeeeye.model.Workflow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Task editor: name a workflow, order its steps, and edit each step.
 *
 * Intent extra "workflow_id" (String): absent = new workflow.
 * CANCEL and back press finish WITHOUT saving. SAVE writes via
 * WorkflowStore.save, toasts, and finishes.
 *
 * Locations are NEVER typed here: there is no X/Y input anywhere in this
 * activity. Points come only from the OverlayPickService overlay (Tap,
 * Swipe, Record gesture), which picks over other apps. The workflow name
 * and the Type-text content are text, not locations, so typing those is
 * fine.
 *
 * NOTE: The WorkflowRunner.Listener signatures below were verified against
 * the runner's actual source (engine/WorkflowRunner.kt): onStepChanged
 * carries (index, total, step), onTick carries elapsed ms, and onFinished
 * carries (Status, message?). Callbacks already arrive on the main thread.
 */
class TaskEditorActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_WAIT_AFTER_MS = 2000L
        private const val DEFAULT_SWIPE_DURATION_MS = 500L
        private const val WAIT_PICKER_MAX = 300 // steps of 100 ms -> max 30,000 ms

        /**
         * Standard Quick Settings tile labels, offered as a LIST (never
         * typed). Android exposes no API to enumerate the tiles actually
         * present on a device, so these are standard names only; a step
         * whose tile is absent fails cleanly at run time (see the QS
         * dialog note). Shared with RecognitionEditActivity's
         * sub-action QS picker so both lists stay identical.
         */
        val QS_TILE_LABELS = listOf(
            "Wi-Fi", "Bluetooth", "Flashlight", "Auto-rotate", "Location",
            "Airplane mode", "Do Not Disturb", "Mobile data", "Hotspot",
            "NFC", "Screen Cast", "Battery Saver"
        )
    }

    private lateinit var nameEditText: EditText
    private lateinit var stepListView: RecyclerView
    private lateinit var adapter: StepAdapter
    private var touchHelper: ItemTouchHelper? = null

    private var existingWorkflow: Workflow? = null
    private lateinit var workflowId: String
    private val steps: MutableList<Step> = mutableListOf()

    // Data-driven repeat state (workflow-level, edited via the editor's
    // overflow menu — the Data button no longer sits in the bottom row).
    private var dataType: String = ""
    private var dataFileName: String = ""
    private var startRow: Int = 1
    private var dataRepeat: Int = 0

    // Recognition / area-click edit state (-1 = creating a new step).
    private var recogEditIndex: Int = -1
    private var areaClickEditIndex: Int = -1
    // Area-click mode + cycles chosen in the area dialog BEFORE the
    // region pick (a new step has no region yet to attach them to).
    // Applied when the region result arrives; cleared on cancel.
    private var pendingAreaMode: String = "RANDOM"
    private var pendingAreaCycles: Int = 1

    // Sub-task name lookup, cached so binds never hit disk repeatedly.
    private val subTaskNames: MutableMap<String, String> = mutableMapOf()
    private fun subTaskName(id: String): String? {
        val cached = subTaskNames[id]
        if (cached != null) return cached.ifBlank { null }
        val name = WorkflowStore.loadActive(this)
            .firstOrNull { it.id == id }?.name ?: ""
        subTaskNames[id] = name
        return name.ifBlank { null }
    }

    // ---- Overlay pick state (Tap one point, Swipe start+end together) ----
    // The overlay service picks over other apps and stores its result in
    // OverlayPickStore; this activity consumes it in onResume(). pickKind
    // remembers which step the pick belongs to while the task is in back.
    private enum class PickKind { TAP_NEW, TAP_EDIT, SWIPE_NEW, SWIPE_EDIT }
    private var pickKind: PickKind? = null
    private var pickEditIndex: Int = -1
    private var overlayRequestId: String? = null

    override fun onResume() {
        super.onResume()
        val requestId = overlayRequestId ?: return
        val json = OverlayPickStore.take(requestId) ?: return
        // A result arrived: the pick is over, whatever it says.
        overlayRequestId = null
        applyOverlayResult(json)
    }

    /** Applies one overlay result (point / swipe / region / record). */
    private fun applyOverlayResult(json: String) {
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            return
        }
        when (obj.optString("kind")) {
            OverlayPickStore.CANCELLED_KIND -> {
                // User aborted from the overlay: nothing changes, and
                // a pending area-click mode/cycles choice is dropped.
                pickKind = null
                pendingAreaMode = "RANDOM"
                pendingAreaCycles = 1
                return
            }
            "point" -> {
                val point = Point(obj.optInt("x"), obj.optInt("y"))
                when (pickKind) {
                    PickKind.TAP_NEW -> showNewPickCyclesDialog(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.TAP,
                            point = point,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS
                        )
                    )
                    PickKind.TAP_EDIT -> {
                        val old = steps.getOrNull(pickEditIndex)
                        if (old != null) replaceStep(pickEditIndex, old.copy(point = point))
                    }
                    else -> { /* A point cannot complete a swipe. */ }
                }
            }
            "swipe" -> {
                val start = Point(obj.optInt("startX"), obj.optInt("startY"))
                val end = Point(obj.optInt("endX"), obj.optInt("endY"))
                when (pickKind) {
                    PickKind.SWIPE_NEW -> showNewPickCyclesDialog(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.SWIPE,
                            start = start,
                            end = end,
                            durationMs = DEFAULT_SWIPE_DURATION_MS,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS
                        )
                    )
                    PickKind.SWIPE_EDIT -> {
                        val old = steps.getOrNull(pickEditIndex)
                        if (old != null) {
                            replaceStep(pickEditIndex, old.copy(start = start, end = end))
                        }
                    }
                    else -> { /* A swipe cannot complete a tap. */ }
                }
            }
            "region" -> {
                val left = obj.optInt("left", -1)
                val top = obj.optInt("top", -1)
                val right = obj.optInt("right", -1)
                val bottom = obj.optInt("bottom", -1)
                if (left < 0 || top < 0 || right <= left || bottom <= top) return
                val editIndex = areaClickEditIndex
                areaClickEditIndex = -1
                if (editIndex in steps.indices) {
                    val old = steps[editIndex]
                    replaceStep(
                        editIndex,
                        old.copy(
                            regionLeft = left, regionTop = top,
                            regionRight = right, regionBottom = bottom
                        )
                    )
                } else {
                    val mode = pendingAreaMode
                    val cycles = pendingAreaCycles
                    pendingAreaMode = "RANDOM"
                    pendingAreaCycles = 1
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.AREA_CLICK,
                            regionLeft = left, regionTop = top,
                            regionRight = right, regionBottom = bottom,
                            areaClickMode = mode,
                            stepCycles = cycles,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS
                        )
                    )
                }
            }
            "record" -> {
                val stepsJson = obj.optString("steps_json")
                if (stepsJson.isBlank()) return
                try {
                    val arr = JSONArray(stepsJson)
                    val added = mutableListOf<Step>()
                    for (i in 0 until arr.length()) {
                        added.add(WorkflowStore.stepFromJson(arr.getJSONObject(i)))
                    }
                    if (added.isNotEmpty()) {
                        showInsertPositionDialogForBatch(added)
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Recorded steps could not be loaded",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        pickKind = null
    }

    // Recognition editor result: add the returned step, or replace the
    // step being edited (recogEditIndex >= 0).
    private val recogLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val editIndex = recogEditIndex
            recogEditIndex = -1
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val json = result.data?.getStringExtra("step_json") ?: return@registerForActivityResult
            try {
                val step = WorkflowStore.stepFromJson(JSONObject(json))
                if (editIndex in steps.indices) {
                    replaceStep(editIndex, step)
                } else {
                    addStep(step)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Recognition step could not be loaded", Toast.LENGTH_SHORT).show()
            }
        }

    // Data file picker: the chosen file is copied into internal storage
    // (never referenced by its original path, which the app may lose).
    private val dataLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val rawName = uri.lastPathSegment ?: ""
                var ext = rawName.substringAfterLast('.', "").lowercase()
                if (ext !in listOf("txt", "csv", "xlsx", "xls")) {
                    ext = when (contentResolver.getType(uri)) {
                        "text/plain" -> "txt"
                        "text/csv", "text/comma-separated-values" -> "csv"
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
                        else -> ""
                    }
                }
                if (ext == "xls") {
                    Toast.makeText(this, R.string.data_xls_unsupported, Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                if (ext !in listOf("txt", "csv", "xlsx")) {
                    Toast.makeText(this, R.string.data_read_failed, Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
                val fileName = "data_" + WorkflowStore.newId() + "." + ext
                val outFile = java.io.File(filesDir, fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    Toast.makeText(this, R.string.data_read_failed, Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
                dataType = if (ext == "xlsx") "EXCEL" else ext.uppercase()
                dataFileName = fileName
                Toast.makeText(this, R.string.data_attached, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, R.string.data_read_failed, Toast.LENGTH_SHORT).show()
            }
        }

    // Toasts the result of a per-step test run; step/tick updates are ignored.
    // Callbacks already arrive on the main thread (WorkflowRunner posts them).
    private val runnerListener = object : WorkflowRunner.Listener {
        override fun onStepChanged(index: Int, total: Int, step: Step) {}
        override fun onTick(elapsedMs: Long) {}
        override fun onFinished(status: WorkflowRunner.Status, message: String?) {
            val text = message?.ifBlank { null } ?: when (status) {
                WorkflowRunner.Status.FINISHED -> "Step finished"
                WorkflowRunner.Status.STOPPED -> "Step stopped"
                WorkflowRunner.Status.FAILED -> "Step failed"
            }
            Toast.makeText(this@TaskEditorActivity, text, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_editor)

        val workflowIdExtra = intent.getStringExtra("workflow_id")
        existingWorkflow = workflowIdExtra?.let { WorkflowStore.get(this, it) }
        workflowId = existingWorkflow?.id ?: WorkflowStore.newId()
        existingWorkflow?.let {
            steps.addAll(it.steps)
            dataType = it.dataType
            dataFileName = it.dataFileName
            startRow = it.startRow
            dataRepeat = it.dataRepeat
        }

        val titleView = findViewById<TextView>(R.id.editor_title)
        titleView.text = existingWorkflow?.name?.ifBlank { null }
            ?: getString(R.string.editor_new_title)
        title = titleView.text

        nameEditText = findViewById(R.id.workflow_name)
        nameEditText.setText(existingWorkflow?.name ?: "")
        stepListView = findViewById(R.id.step_list)

        adapter = StepAdapter(steps, object : StepAdapter.Listener {
            override fun onStepClicked(position: Int) = editStep(position)
            override fun onCopyStep(position: Int) {
                val step = steps.getOrNull(position) ?: return
                steps.add(position + 1, step.copy(id = WorkflowStore.newId()))
                adapter.notifyItemInserted(position + 1)
            }
            override fun onDeleteStep(position: Int) {
                if (position !in steps.indices) return
                steps.removeAt(position)
                adapter.notifyItemRemoved(position)
                if (position < steps.size) {
                    adapter.notifyItemRangeChanged(position, steps.size - position)
                }
            }
            override fun onPlayStep(position: Int) {
                WorkflowRunner.runSingle(currentWorkflow(), position)
            }
            override fun onWaitClicked(position: Int) = showWaitAfterDialog(position)
            override fun onStartDrag(holder: RecyclerView.ViewHolder) {
                touchHelper?.startDrag(holder)
            }
            override fun subTaskName(subWorkflowId: String): String? =
                this@TaskEditorActivity.subTaskName(subWorkflowId)
        })
        stepListView.layoutManager = LinearLayoutManager(this)
        stepListView.adapter = adapter

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                    return false
                }
                adapter.move(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            // Drag starts only from the btn_drag handle, never long-press.
            override fun isLongPressDragEnabled(): Boolean = false
        })
        touchHelper?.attachToRecyclerView(stepListView)

        findViewById<Button>(R.id.btn_add).setOnClickListener { openAddStepSheet() }
        findViewById<Button>(R.id.btn_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_save).setOnClickListener { saveAndFinish() }
        findViewById<ImageButton>(R.id.btn_editor_menu).setOnClickListener {
            showEditorMenu(it)
        }

        // Back press = cancel: finish without saving. Uses the base
        // OnBackPressedCallback API (no activity-ktx dependency).
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }
            }
        )

        WorkflowRunner.addListener(runnerListener)

        // Opened from the floating bar's target button: go straight to
        // point picking; the picked point is appended as a Tap step below
        // (pick-only — the point is never typed).
        if (intent.getBooleanExtra("pick_target", false)) {
            pickKind = PickKind.TAP_NEW
            launchPick(getString(R.string.pick_on_screen))
        }
    }

    override fun onDestroy() {
        WorkflowRunner.removeListener(runnerListener)
        super.onDestroy()
    }

    // ---------- Workflow assembly / persistence ----------

    private fun currentWorkflow(): Workflow {
        val existing = existingWorkflow
        return Workflow(
            id = workflowId,
            name = nameEditText.text.toString(),
            cycleIntervalMs = existing?.cycleIntervalMs ?: 500,
            cycles = existing?.cycles ?: 1,
            steps = steps.toList(),
            deleted = existing?.deleted ?: false,
            updatedAt = existing?.updatedAt ?: System.currentTimeMillis(),
            dataType = dataType,
            dataFileName = dataFileName,
            startRow = startRow,
            dataRepeat = dataRepeat
        )
    }

    private fun saveAndFinish() {
        WorkflowStore.save(this, currentWorkflow())
        Toast.makeText(this, R.string.workflow_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * Every step added from the ADD menu (and every recognition step
     * returned from its editor) goes through the insert-position
     * dialog: NumberPicker 1..N+1 (N = current step count), defaulting
     * to the last position (append). Drag-reorder is untouched, and
     * Copy still inserts directly after the copied step.
     */
    private fun addStep(step: Step) {
        showInsertPositionDialog(step)
    }

    private fun insertStepAt(step: Step, position: Int) {
        val index = (position - 1).coerceIn(0, steps.size)
        steps.add(index, step)
        adapter.notifyItemInserted(index)
    }

    private fun showInsertPositionDialog(step: Step) {
        val picker = NumberPicker(this).apply {
            minValue = 1
            maxValue = steps.size + 1
            value = steps.size + 1
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.editor_insert_position_title)
            .setView(picker)
            .setPositiveButton(R.string.ok) { _, _ ->
                insertStepAt(step, picker.value)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showInsertPositionDialogForBatch(added: List<Step>) {
        val picker = NumberPicker(this).apply {
            minValue = 1
            maxValue = steps.size + 1
            value = steps.size + 1
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.editor_insert_position_title)
            .setView(picker)
            .setPositiveButton(R.string.ok) { _, _ ->
                val index = (picker.value - 1).coerceIn(0, steps.size)
                steps.addAll(index, added)
                adapter.notifyItemRangeInserted(index, added.size)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                // Cancel keeps the recording out of the workflow: the
                // recorded steps are not silently appended anywhere.
            }
            .show()
    }

    /**
     * Cycles picker for a freshly picked Tap / Swipe step (these steps
     * have no other configuration dialog). OK inserts the step with
     * the chosen cycles; Cancel inserts it with cycles = 1, so the
     * pick itself is never lost to a cancelled picker.
     */
    private fun showNewPickCyclesDialog(step: Step) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val picker = addCyclesSection(layout, step.stepCycles)
        AlertDialog.Builder(this)
            .setTitle(R.string.cycles_title)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                addStep(step.copy(stepCycles = picker.value))
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                addStep(step)
            }
            .show()
    }

    /**
     * Cycles picker for a Tap / Swipe step being edited (tapping its
     * row). Save stores the cycles and keeps the old point; Pick again
     * stores the cycles first, then launches the overlay pick, whose
     * edit result preserves them (it copies the existing step).
     */
    private fun showTapSwipeCyclesDialog(step: Step, position: Int) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val picker = addCyclesSection(layout, step.stepCycles)
        val pickAgainLabel = getString(R.string.pick_on_screen)
        AlertDialog.Builder(this)
            .setTitle(R.string.cycles_title)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                replaceStep(position, step.copy(stepCycles = picker.value))
            }
            .setNeutralButton(pickAgainLabel) { _, _ ->
                val updated = step.copy(stepCycles = picker.value)
                replaceStep(position, updated)
                pickEditIndex = position
                if (step.type == StepType.SWIPE) {
                    pickKind = PickKind.SWIPE_EDIT
                    launchSwipePick()
                } else {
                    pickKind = PickKind.TAP_EDIT
                    launchPick(getString(R.string.pick_on_screen))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Appends a "Cycles" label + NumberPicker (1..99, default 1) to a
     * dialog [layout] and returns the picker. Old steps (cycles = 1)
     * show the picker at 1, so saving without touching it changes
     * nothing; the runner repeats only when cycles > 1.
     */
    private fun addCyclesSection(
        layout: android.widget.LinearLayout,
        existingCycles: Int
    ): NumberPicker {
        layout.addView(
            android.widget.TextView(this).apply {
                text = getString(R.string.cycles_title)
            }
        )
        return NumberPicker(this).apply {
            minValue = 1
            maxValue = 99
            value = existingCycles.coerceIn(1, 99)
            layout.addView(this)
        }
    }

    private fun replaceStep(position: Int, step: Step) {
        if (position !in steps.indices) return
        steps[position] = step
        adapter.notifyItemChanged(position)
    }

    // ---------- Add-step sheet ----------

    private fun openAddStepSheet() {
        val sheet = AddStepBottomSheet()
        sheet.onStepChosenListener =
            AddStepBottomSheet.OnStepChosenListener { type -> onAddStepType(type) }
        sheet.onRecordRequested = { launchRecord() }
        sheet.show(supportFragmentManager, "add_step")
    }

    private fun onAddStepType(type: StepType) {
        when (type) {
            StepType.TAP -> {
                pickKind = PickKind.TAP_NEW
                launchPick(getString(R.string.pick_on_screen))
            }
            StepType.SWIPE -> {
                pickKind = PickKind.SWIPE_NEW
                launchSwipePick()
            }
            StepType.TYPE_TEXT -> showTypeTextDialog(null, null)
            StepType.WAIT -> showWaitDurationDialog(null, null)
            StepType.NAVIGATION -> showNavigationDialog(null, null)
            StepType.LAUNCH_APP -> showLaunchAppDialog(null, null)
            StepType.SUB_TASK -> showSubTaskDialog(null, null)
            StepType.TYPE_CODE -> showSnippetPicker(null, null)
            StepType.TYPE_EXCEL_ROW -> showMistakesPicker(null, null)
            StepType.BUTTON_RECOGNITION,
            StepType.IMAGE_RECOGNITION,
            StepType.COLOR_RECOGNITION,
            StepType.OCR_READ -> launchRecognition(null, type, -1)
            StepType.PASTE -> showPasteDialog(null, null)
            StepType.SCREENSHOT,
            StepType.VIBRATION,
            StepType.SOUND,
            StepType.SCREEN_OFF,
            StepType.STOP_TASK -> addStep(
                Step(
                    id = WorkflowStore.newId(),
                    type = type,
                    waitAfterMs = DEFAULT_WAIT_AFTER_MS
                )
            )
            StepType.AREA_CLICK -> showAreaClickDialog(null, null)
            StepType.WAIT_RANDOM -> showWaitRandomDialog(null, null)
            StepType.INTENT_URI -> showUriDialog(null, null, false)
            StepType.NETWORK_GET -> showUriDialog(null, null, true)
            StepType.SLIDE_LIST -> showSlideListDialog(null, null)
            StepType.QS_TILE -> showQsTileDialog(null, null)
            StepType.POWER_DIALOG -> showPowerDialogDialog(null, null)
        }
    }

    private fun editStep(position: Int) {
        val step = steps.getOrNull(position) ?: return
        when (step.type) {
            StepType.TAP -> showTapSwipeCyclesDialog(step, position)
            StepType.SWIPE -> showTapSwipeCyclesDialog(step, position)
            StepType.TYPE_TEXT -> showTypeTextDialog(step, position)
            StepType.WAIT -> showWaitDurationDialog(step, position)
            StepType.NAVIGATION -> showNavigationDialog(step, position)
            StepType.LAUNCH_APP -> showLaunchAppDialog(step, position)
            StepType.SUB_TASK -> showSubTaskDialog(step, position)
            StepType.TYPE_CODE -> showSnippetPicker(step, position)
            StepType.TYPE_EXCEL_ROW -> showMistakesPicker(step, position)
            StepType.BUTTON_RECOGNITION,
            StepType.IMAGE_RECOGNITION,
            StepType.COLOR_RECOGNITION,
            StepType.OCR_READ -> launchRecognition(step, step.type, position)
            StepType.AREA_CLICK -> showAreaClickDialog(step, position)
            StepType.SLIDE_LIST -> showSlideListDialog(step, position)
            StepType.QS_TILE -> showQsTileDialog(step, position)
            StepType.POWER_DIALOG -> showPowerDialogDialog(step, position)
            StepType.WAIT_RANDOM -> showWaitRandomDialog(step, position)
            StepType.INTENT_URI -> showUriDialog(step, position, false)
            StepType.NETWORK_GET -> showUriDialog(step, position, true)
            StepType.PASTE -> showPasteDialog(step, position)
            StepType.SCREENSHOT,
            StepType.VIBRATION,
            StepType.SOUND,
            StepType.SCREEN_OFF,
            StepType.STOP_TASK -> Toast.makeText(
                this, R.string.step_no_settings, Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ---------- New-step editors (12-feature build) ----------

    /** TYPE_CODE: pick a saved snippet (code typing is always human-like). */
    private fun showSnippetPicker(existing: Step?, position: Int?) {
        val snippets = CodeLibraryStore.loadAll(this)
        if (snippets.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage(R.string.no_snippets_yet)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        val labels = snippets.map { it.name }
        var selected = snippets.indexOfFirst { it.id == existing?.snippetId }
        // Single-choice list rebuilt as a RadioGroup so the Cycles
        // picker can sit below it in the same dialog (a builder
        // setSingleChoiceItems dialog has no custom-view slot for it).
        // Selection behaviour is unchanged: one item, none by default.
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val group = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        labels.forEachIndexed { index, label ->
            group.addView(
                android.widget.RadioButton(this).apply {
                    text = label
                    isChecked = index == selected
                    setOnClickListener { selected = index }
                }
            )
        }
        layout.addView(group)
        val cyclesPicker = addCyclesSection(
            layout, existing?.stepCycles ?: 1
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_snippet)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (selected < 0) return@setPositiveButton
                val snippetId = snippets[selected].id
                if (existing != null && position != null) {
                    replaceStep(
                        position,
                        existing.copy(
                            snippetId = snippetId,
                            stepCycles = cyclesPicker.value
                        )
                    )
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.TYPE_CODE,
                            snippetId = snippetId,
                            mistakeLevel = MistakeLevel.NORMAL,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS,
                            stepCycles = cyclesPicker.value
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** TYPE_EXCEL_ROW: mistakes level only; the row itself comes from data. */
    private fun showMistakesPicker(existing: Step?, position: Int?) {
        val labels = arrayOf(
            getString(R.string.mistakes_off),
            getString(R.string.mistakes_light),
            getString(R.string.mistakes_normal),
            getString(R.string.mistakes_heavy)
        )
        var selected = when (existing?.mistakeLevel) {
            MistakeLevel.LIGHT -> 1
            MistakeLevel.NORMAL -> 2
            MistakeLevel.HEAVY -> 3
            else -> 0
        }
        // Single-choice list rebuilt as a RadioGroup so the Cycles
        // picker can sit below it in the same dialog (a builder
        // setSingleChoiceItems dialog has no custom-view slot for it).
        // Selection behaviour is unchanged: one level, Off by default.
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val group = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        labels.forEachIndexed { index, label ->
            group.addView(
                android.widget.RadioButton(this).apply {
                    text = label
                    isChecked = index == selected
                    setOnClickListener { selected = index }
                }
            )
        }
        layout.addView(group)
        val cyclesPicker = addCyclesSection(
            layout, existing?.stepCycles ?: 1
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.mistakes_title)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val level = when (selected) {
                    1 -> MistakeLevel.LIGHT
                    2 -> MistakeLevel.NORMAL
                    3 -> MistakeLevel.HEAVY
                    else -> MistakeLevel.OFF
                }
                if (existing != null && position != null) {
                    replaceStep(
                        position,
                        existing.copy(
                            mistakeLevel = level,
                            stepCycles = cyclesPicker.value
                        )
                    )
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.TYPE_EXCEL_ROW,
                            mistakeLevel = level,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS,
                            stepCycles = cyclesPicker.value
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Recognition steps: full editor in its own activity. */
    private fun launchRecognition(existing: Step?, type: StepType, position: Int) {
        recogEditIndex = position
        val intent = Intent(this, RecognitionEditActivity::class.java)
        intent.putExtra("step_type", type.name)
        if (existing != null) {
            intent.putExtra("step_json", WorkflowStore.stepToJson(existing).toString())
        }
        val stepsJson = JSONArray()
        steps.forEach { stepsJson.put(WorkflowStore.stepToJson(it)) }
        intent.putExtra("workflow_steps_json", stepsJson.toString())
        recogLauncher.launch(intent)
    }

    /** AREA_CLICK: pick the area on screen via the overlay (never typed). */
    private fun launchAreaPick(position: Int) {
        areaClickEditIndex = position
        val requestId = WorkflowStore.newId()
        overlayRequestId = requestId
        if (!OverlayPickService.startRegion(
                this, requestId, "region", TaskEditorActivity::class.java.name
            )
        ) {
            overlayRequestId = null
            areaClickEditIndex = -1
        }
    }

    /** WAIT_RANDOM: min/max in 100 ms steps. */
    private fun showWaitRandomDialog(existing: Step?, position: Int?) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val minLabel = android.widget.TextView(this).apply {
            text = getString(R.string.wait_min_title)
        }
        val minPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 300
            value = ((existing?.waitMinMs ?: 0) / 100).toInt().coerceIn(0, 300)
        }
        val maxLabel = android.widget.TextView(this).apply {
            text = getString(R.string.wait_max_title)
        }
        val maxPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 300
            value = ((existing?.waitMaxMs ?: 1000) / 100).toInt().coerceIn(0, 300)
        }
        layout.addView(minLabel)
        layout.addView(minPicker)
        layout.addView(maxLabel)
        layout.addView(maxPicker)
        val cyclesPicker = addCyclesSection(
            layout, existing?.stepCycles ?: 1
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.step_wait_random)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val minMs = minPicker.value * 100L
                var maxMs = maxPicker.value * 100L
                if (maxMs < minMs) maxMs = minMs
                if (existing != null && position != null) {
                    replaceStep(
                        position,
                        existing.copy(
                            waitMinMs = minMs, waitMaxMs = maxMs,
                            stepCycles = cyclesPicker.value
                        )
                    )
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.WAIT_RANDOM,
                            waitMinMs = minMs,
                            waitMaxMs = maxMs,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS,
                            stepCycles = cyclesPicker.value
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** INTENT_URI / NETWORK_GET: a URI/URL is text, not a location. */
    private fun showUriDialog(existing: Step?, position: Int?, isNetwork: Boolean) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(if (isNetwork) existing?.networkUrl ?: "" else existing?.intentUri ?: "")
            hint = getString(
                if (isNetwork) R.string.network_url_hint else R.string.intent_uri_hint
            )
        }
        layout.addView(input)
        // Cycles shares this dialog (it serves both INTENT_URI and
        // NETWORK_GET), so both top-level step types get the picker.
        val cyclesPicker = addCyclesSection(
            layout, existing?.stepCycles ?: 1
        )
        AlertDialog.Builder(this)
            .setTitle(
                if (isNetwork) R.string.step_network_get else R.string.step_intent_uri
            )
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val value = input.text.toString()
                if (existing != null && position != null) {
                    replaceStep(
                        position,
                        if (isNetwork) {
                            existing.copy(
                                networkUrl = value,
                                stepCycles = cyclesPicker.value
                            )
                        } else {
                            existing.copy(
                                intentUri = value,
                                stepCycles = cyclesPicker.value
                            )
                        }
                    )
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = if (isNetwork) StepType.NETWORK_GET else StepType.INTENT_URI,
                            networkUrl = if (isNetwork) value else "",
                            intentUri = if (isNetwork) "" else value,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS,
                            stepCycles = cyclesPicker.value
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Top 3-dot menu: workflow-level actions (currently just Data…). */
    private fun showEditorMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(getString(R.string.editor_data))
        popup.setOnMenuItemClickListener {
            showDataDialog()
            true
        }
        popup.show()
    }

    /**
     * State for a "Library data file + column" picker pair, shared by
     * the Type text and Paste dialogs. Position 0 of the file spinner
     * is always "None"; real files follow in [files] order.
     */
    private class DataPickerState(
        val fileSpinner: Spinner,
        val columnSpinner: Spinner,
        val files: List<DataFileStore.DataFile>
    ) {
        fun selectedFileId(): String {
            val pos = fileSpinner.selectedItemPosition
            return if (pos > 0) files[pos - 1].id else ""
        }

        fun selectedFile(): DataFileStore.DataFile? {
            val pos = fileSpinner.selectedItemPosition
            return if (pos > 0) files[pos - 1] else null
        }

        fun selectedColumn(): String {
            if (selectedFileId().isBlank()) return ""
            return columnSpinner.selectedItem as? String ?: ""
        }
    }

    /**
     * Appends a "Data source" section (file spinner + column spinner +
     * optional hint + deleted-file warning) to a dialog [layout].
     * [existingId]/[existingColumn] pre-select the step's current
     * source; when the id no longer exists in the Library, the warning
     * shows and the spinners fall back to None (saving then clears the
     * stale reference, visibly — never silently).
     */
    private fun addDataSourceSection(
        layout: android.widget.LinearLayout,
        existingId: String,
        existingColumn: String,
        hintRes: Int?
    ): DataPickerState {
        val files = DataFileStore.loadAll(this)
        val title = android.widget.TextView(this).apply {
            text = getString(R.string.type_data_source_title)
        }
        layout.addView(title)
        val fileSpinner = Spinner(this)
        val fileLabels = mutableListOf(getString(R.string.type_data_none))
        fileLabels.addAll(files.map { it.name })
        fileSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, fileLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        layout.addView(fileSpinner)
        val columnSpinner = Spinner(this)
        layout.addView(columnSpinner)
        val warning = android.widget.TextView(this).apply {
            text = getString(R.string.type_data_missing_warning)
            visibility = View.GONE
        }
        layout.addView(warning)
        if (hintRes != null) {
            layout.addView(
                android.widget.TextView(this).apply {
                    text = getString(hintRes)
                }
            )
        }
        val existingIndex = files.indexOfFirst { it.id == existingId }
        if (existingId.isNotBlank() && existingIndex < 0) {
            warning.visibility = View.VISIBLE
        }
        fun refreshColumns() {
            val file = DataPickerState(fileSpinner, columnSpinner, files).selectedFile()
            val columns = if (file == null) {
                emptyList()
            } else {
                DataSourceLoader.columns(this, file.type, file.fileName)
            }
            columnSpinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_item,
                if (columns.isEmpty()) listOf("") else columns
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            if (file != null && file.id == existingId && existingColumn in columns) {
                columnSpinner.setSelection(columns.indexOf(existingColumn))
            }
        }
        fileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                refreshColumns()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        if (existingIndex >= 0) {
            fileSpinner.setSelection(existingIndex + 1)
        }
        refreshColumns()
        return DataPickerState(fileSpinner, columnSpinner, files)
    }

    /** Workflow-level data source: attach file, start row, repeat, remove. */
    private fun showDataDialog() {
        val message = if (dataFileName.isBlank()) {
            getString(R.string.data_current_none)
        } else {
            getString(
                R.string.data_current_format,
                dataFileName, dataType.ifBlank { "-" }, startRow, dataRepeat
            )
        }
        val items = arrayOf(
            getString(R.string.data_attach),
            getString(R.string.data_start_row),
            getString(R.string.data_repeat),
            getString(R.string.data_remove)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.data_dialog_title)
            .setMessage(message)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> dataLauncher.launch("*/*")
                    1 -> {
                        val picker = NumberPicker(this).apply {
                            minValue = 1
                            maxValue = 10000
                            value = startRow.coerceIn(1, 10000)
                        }
                        AlertDialog.Builder(this)
                            .setTitle(R.string.start_row_title)
                            .setView(picker)
                            .setPositiveButton(R.string.ok) { _, _ ->
                                startRow = picker.value
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                    2 -> {
                        val picker = NumberPicker(this).apply {
                            minValue = 0
                            maxValue = 10000
                            value = dataRepeat.coerceIn(0, 10000)
                        }
                        AlertDialog.Builder(this)
                            .setTitle(R.string.repeat_title)
                            .setView(picker)
                            .setPositiveButton(R.string.ok) { _, _ ->
                                dataRepeat = picker.value
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                    3 -> {
                        dataType = ""
                        dataFileName = ""
                        Toast.makeText(this, R.string.data_removed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- Pick / record launches (overlay over other apps) ----------
    // Each launch starts the overlay service and moves this task to the
    // back, so the user picks on the real screen underneath. The result
    // is consumed in onResume(). When the overlay permission is missing,
    // the service helper shows the guide dialog and returns false, and
    // no pick is left pending.

    private fun launchPick(hint: String) {
        val requestId = WorkflowStore.newId()
        overlayRequestId = requestId
        if (!OverlayPickService.startPoint(
                this, requestId, hint, TaskEditorActivity::class.java.name
            )
        ) {
            overlayRequestId = null
        }
    }

    private fun launchSwipePick() {
        val requestId = WorkflowStore.newId()
        overlayRequestId = requestId
        if (!OverlayPickService.startSwipe(
                this, requestId, TaskEditorActivity::class.java.name
            )
        ) {
            overlayRequestId = null
        }
    }

    private fun launchRecord() {
        val requestId = WorkflowStore.newId()
        overlayRequestId = requestId
        if (!OverlayPickService.startRecord(
                this, requestId, TaskEditorActivity::class.java.name
            )
        ) {
            overlayRequestId = null
        }
    }

    // ---------- Step editors (shared by create and edit) ----------

    /** TYPE_TEXT: text + wait-after + typing-mistakes level.
     * The runner already applies mistakeLevel (human-like typing with
     * correction when it is not OFF, plain typing when OFF), and the
     * step JSON already persists it — this dialog only exposes it.
     * Defaults keep old behaviour exactly: wait 2000 ms, mistakes Off. */
    private fun showTypeTextDialog(existing: Step?, position: Int?) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.text ?: "")
        }
        layout.addView(input)
        val waitLabel = android.widget.TextView(this).apply {
            text = getString(R.string.type_wait_after_title)
        }
        layout.addView(waitLabel)
        val waitPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = WAIT_PICKER_MAX
            value = ((existing?.waitAfterMs ?: DEFAULT_WAIT_AFTER_MS) / 100).toInt()
                .coerceIn(0, WAIT_PICKER_MAX)
        }
        layout.addView(waitPicker)
        val cyclesPicker = addCyclesSection(
            layout, existing?.stepCycles ?: 1
        )
        val mistakesLabel = android.widget.TextView(this).apply {
            text = getString(R.string.type_mistakes_title)
        }
        layout.addView(mistakesLabel)
        val mistakesGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        listOf(
            getString(R.string.mistakes_off) to MistakeLevel.OFF,
            getString(R.string.mistakes_light) to MistakeLevel.LIGHT,
            getString(R.string.mistakes_normal) to MistakeLevel.NORMAL,
            getString(R.string.mistakes_heavy) to MistakeLevel.HEAVY
        ).forEach { (label, level) ->
            mistakesGroup.addView(
                android.widget.RadioButton(this).apply {
                    text = label
                    isChecked = (existing?.mistakeLevel ?: MistakeLevel.OFF) == level
                    tag = level
                }
            )
        }
        layout.addView(mistakesGroup)
        // Per-step data source (desktop _data_source_id): None means
        // "use the workflow's data file", exactly like the desktop
        // default; otherwise this step reads its own Library file.
        val dataPickers = addDataSourceSection(
            layout,
            existing?.dataSourceId ?: "",
            existing?.dataColumn ?: "",
            R.string.type_data_hint
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.step_type_text)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = input.text.toString()
                val waitAfterMs = waitPicker.value * 100L
                val checkedId = mistakesGroup.checkedRadioButtonId
                val level = if (checkedId >= 0) {
                    (mistakesGroup.findViewById<android.widget.RadioButton>(checkedId)?.tag
                        as? MistakeLevel) ?: MistakeLevel.OFF
                } else {
                    MistakeLevel.OFF
                }
                val dataSourceId = dataPickers.selectedFileId()
                val dataColumn = dataPickers.selectedColumn()
                if (existing != null && position != null) {
                    replaceStep(
                        position,
                        existing.copy(
                            text = text, waitAfterMs = waitAfterMs, mistakeLevel = level,
                            stepCycles = cyclesPicker.value,
                            dataSourceId = dataSourceId, dataColumn = dataColumn
                        )
                    )
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.TYPE_TEXT,
                            text = text,
                            waitAfterMs = waitAfterMs,
                            mistakeLevel = level,
                            stepCycles = cyclesPicker.value,
                            dataSourceId = dataSourceId,
                            dataColumn = dataColumn
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * PASTE options — this dialog did not exist before: adding a Paste
     * step showed no settings at all, and editing one only showed a
     * "no settings" toast. Source Clipboard preserves the old
     * behaviour exactly (old steps keep a blank dataSourceId and blank
     * text); Typed text stores the typed content in the step's text
     * field and the runner puts it on the clipboard before pasting;
     * Library data pastes the chosen file's value at the current row.
     */
    private fun showPasteDialog(existing: Step?, position: Int?) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val sourceGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        val hasLibrary = (existing?.dataSourceId ?: "").isNotBlank()
        val hasTyped = !hasLibrary && (existing?.text ?: "").isNotBlank()
        val clipboardButton = android.widget.RadioButton(this).apply {
            text = getString(R.string.paste_source_clipboard)
            isChecked = !hasLibrary && !hasTyped
            tag = "clipboard"
        }
        val typedButton = android.widget.RadioButton(this).apply {
            text = getString(R.string.paste_source_typed)
            isChecked = hasTyped
            tag = "typed"
        }
        val libraryButton = android.widget.RadioButton(this).apply {
            text = getString(R.string.paste_source_library)
            isChecked = hasLibrary
            tag = "library"
        }
        sourceGroup.addView(clipboardButton)
        sourceGroup.addView(typedButton)
        sourceGroup.addView(libraryButton)
        layout.addView(sourceGroup)
        val typedInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(if (hasTyped) existing?.text ?: "" else "")
            hint = getString(R.string.paste_typed_hint)
        }
        layout.addView(typedInput)
        val dataPickers = addDataSourceSection(
            layout,
            existing?.dataSourceId ?: "",
            existing?.dataColumn ?: "",
            R.string.paste_data_hint
        )
        fun refreshEnabled() {
            val library = libraryButton.isChecked
            dataPickers.fileSpinner.isEnabled = library
            dataPickers.columnSpinner.isEnabled = library
            typedInput.isEnabled = typedButton.isChecked
        }
        sourceGroup.setOnCheckedChangeListener { _, _ -> refreshEnabled() }
        refreshEnabled()
        val waitLabel = android.widget.TextView(this).apply {
            text = getString(R.string.type_wait_after_title)
        }
        layout.addView(waitLabel)
        val waitPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = WAIT_PICKER_MAX
            value = ((existing?.waitAfterMs ?: DEFAULT_WAIT_AFTER_MS) / 100).toInt()
                .coerceIn(0, WAIT_PICKER_MAX)
        }
        layout.addView(waitPicker)
        val cyclesPicker = addCyclesSection(
            layout, existing?.stepCycles ?: 1
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.paste_dialog_title)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val waitAfterMs = waitPicker.value * 100L
                val useLibrary = libraryButton.isChecked
                val useTyped = typedButton.isChecked
                val dataSourceId = if (useLibrary) dataPickers.selectedFileId() else ""
                val dataColumn = if (useLibrary) dataPickers.selectedColumn() else ""
                // Clipboard and Library both clear the typed text, so
                // a step never carries a stale typed value into the
                // other source's run path; Typed stores it, Clipboard
                // keeps the old blank-text behaviour exactly.
                val text = if (useTyped) typedInput.text.toString() else ""
                if (existing != null && position != null) {
                    replaceStep(
                        position,
                        existing.copy(
                            waitAfterMs = waitAfterMs,
                            text = text,
                            dataSourceId = dataSourceId,
                            dataColumn = dataColumn,
                            stepCycles = cyclesPicker.value
                        )
                    )
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.PASTE,
                            text = text,
                            waitAfterMs = waitAfterMs,
                            dataSourceId = dataSourceId,
                            dataColumn = dataColumn,
                            stepCycles = cyclesPicker.value
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** WAIT step duration (not the per-step wait-after, which has its own editor). */
    private fun showWaitDurationDialog(existing: Step?, position: Int?) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val picker = NumberPicker(this).apply {
            minValue = 0
            maxValue = WAIT_PICKER_MAX
            value = ((existing?.durationMs ?: 0) / 100).toInt()
                .coerceIn(0, WAIT_PICKER_MAX)
            layout.addView(this)
        }
        val cyclesPicker = addCyclesSection(layout, existing?.stepCycles ?: 1)
        AlertDialog.Builder(this)
            .setTitle(R.string.step_wait)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val durationMs = picker.value * 100L
                if (existing != null && position != null) {
                    replaceStep(
                        position,
                        existing.copy(durationMs = durationMs, stepCycles = cyclesPicker.value)
                    )
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.WAIT,
                            durationMs = durationMs,
                            stepCycles = cyclesPicker.value,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Per-step pause shown under each row ("Wait: N ms"). */
    private fun showWaitAfterDialog(position: Int) {
        val step = steps.getOrNull(position) ?: return
        val picker = NumberPicker(this).apply {
            minValue = 0
            maxValue = WAIT_PICKER_MAX
            value = (step.waitAfterMs / 100).toInt().coerceIn(0, WAIT_PICKER_MAX)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.step_wait)
            .setView(picker)
            .setPositiveButton(R.string.ok) { _, _ ->
                replaceStep(position, step.copy(waitAfterMs = picker.value * 100L))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNavigationDialog(existing: Step?, position: Int?) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val navGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        listOf(
            getString(R.string.nav_back) to NavKey.BACK,
            getString(R.string.nav_home) to NavKey.HOME,
            getString(R.string.nav_recents) to NavKey.RECENTS
        ).forEach { (label, key) ->
            navGroup.addView(
                android.widget.RadioButton(this).apply {
                    text = label
                    isChecked = existing?.navKey == key
                    tag = key
                }
            )
        }
        layout.addView(navGroup)
        val cyclesPicker = addCyclesSection(layout, existing?.stepCycles ?: 1)
        AlertDialog.Builder(this)
            .setTitle(R.string.step_navigation)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val checkedId = navGroup.checkedRadioButtonId
                if (checkedId < 0) return@setPositiveButton
                val navKey = navGroup.findViewById<android.widget.RadioButton>(checkedId)
                    ?.tag as? NavKey ?: return@setPositiveButton
                if (existing != null && position != null) {
                    replaceStep(
                        position,
                        existing.copy(navKey = navKey, stepCycles = cyclesPicker.value)
                    )
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.NAVIGATION,
                            navKey = navKey,
                            stepCycles = cyclesPicker.value,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * App picker: label shown, package name stored. No typed package
     * field. A search box filters the list by app name as the user
     * types; OK/CANCEL keep the old selection behaviour (OK with
     * nothing selected does nothing).
     */
    private fun showLaunchAppDialog(existing: Step?, position: Int?) {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launchIntent,
                android.content.pm.PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launchIntent, 0)
        }.sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.no_apps_found, Toast.LENGTH_SHORT).show()
            return
        }
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        var selectedPackage: String? = existing?.packageName?.ifBlank { null }
        val searchBox = EditText(this).apply {
            hint = getString(R.string.launch_search_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        layout.addView(searchBox)
        var filtered = apps
        val listView = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (360 * density).toInt()
            )
        }
        layout.addView(listView)
        fun refreshList() {
            val query = searchBox.text.toString().trim().lowercase()
            filtered = if (query.isEmpty()) {
                apps
            } else {
                apps.filter {
                    it.loadLabel(packageManager).toString().lowercase().contains(query)
                }
            }
            listView.adapter = ArrayAdapter(
                this, android.R.layout.simple_list_item_single_choice,
                filtered.map { it.loadLabel(packageManager).toString() }
            )
            val selectedIndex = filtered.indexOfFirst {
                it.activityInfo?.packageName == selectedPackage
            }
            if (selectedIndex >= 0) {
                listView.setItemChecked(selectedIndex, true)
            }
        }
        listView.setOnItemClickListener { _, _, which, _ ->
            selectedPackage = filtered.getOrNull(which)?.activityInfo?.packageName
        }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshList()
            }
        })
        refreshList()
        val cyclesPicker = addCyclesSection(layout, existing?.stepCycles ?: 1)
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_app)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val packageName = selectedPackage ?: return@setPositiveButton
                if (apps.none { it.activityInfo?.packageName == packageName }) {
                    return@setPositiveButton
                }
                if (existing != null && position != null) {
                    replaceStep(
                        position,
                        existing.copy(
                            packageName = packageName,
                            stepCycles = cyclesPicker.value
                        )
                    )
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.LAUNCH_APP,
                            packageName = packageName,
                            stepCycles = cyclesPicker.value,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- Missing-core step dialogs ----------

    /**
     * AREA_CLICK: mode + cycles. For a new step, OK stores the choices
     * and launches the overlay region pick (the region result then
     * creates the step with them). For an existing step, Save stores
     * mode + cycles and keeps the old region; "Pick region again"
     * stores them first, then re-picks (the edit result copies the
     * existing step, so mode + cycles survive the re-pick).
     */
    private fun showAreaClickDialog(existing: Step?, position: Int?) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(
            android.widget.TextView(this).apply {
                text = getString(R.string.area_mode_title)
            }
        )
        val modeGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        listOf(
            getString(R.string.area_mode_random) to "RANDOM",
            getString(R.string.area_mode_all) to "ALL"
        ).forEach { (label, mode) ->
            modeGroup.addView(
                android.widget.RadioButton(this).apply {
                    text = label
                    isChecked = (existing?.areaClickMode ?: "RANDOM") == mode
                    tag = mode
                }
            )
        }
        layout.addView(modeGroup)
        val cyclesPicker = addCyclesSection(layout, existing?.stepCycles ?: 1)
        fun chosenMode(): String {
            val checkedId = modeGroup.checkedRadioButtonId
            return if (checkedId >= 0) {
                modeGroup.findViewById<android.widget.RadioButton>(checkedId)
                    ?.tag as? String ?: "RANDOM"
            } else {
                "RANDOM"
            }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.step_area_click)
            .setView(layout)
            .setNegativeButton(R.string.cancel, null)
        if (existing != null && position != null) {
            builder
                .setPositiveButton(R.string.ok) { _, _ ->
                    replaceStep(
                        position,
                        existing.copy(
                            areaClickMode = chosenMode(),
                            stepCycles = cyclesPicker.value
                        )
                    )
                }
                .setNeutralButton(R.string.area_pick_region_again) { _, _ ->
                    replaceStep(
                        position,
                        existing.copy(
                            areaClickMode = chosenMode(),
                            stepCycles = cyclesPicker.value
                        )
                    )
                    launchAreaPick(position)
                }
        } else {
            builder.setPositiveButton(R.string.ok) { _, _ ->
                pendingAreaMode = chosenMode()
                pendingAreaCycles = cyclesPicker.value
                launchAreaPick(-1)
            }
        }
        builder.show()
    }

    /** SLIDE_LIST: direction (Down/Up) + count (1..20) + cycles. */
    private fun showSlideListDialog(existing: Step?, position: Int?) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(
            android.widget.TextView(this).apply {
                text = getString(R.string.slide_direction_title)
            }
        )
        val directionGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        listOf(
            getString(R.string.slide_direction_down) to "DOWN",
            getString(R.string.slide_direction_up) to "UP"
        ).forEach { (label, direction) ->
            directionGroup.addView(
                android.widget.RadioButton(this).apply {
                    text = label
                    isChecked = (existing?.slideDirection ?: "DOWN") == direction
                    tag = direction
                }
            )
        }
        layout.addView(directionGroup)
        layout.addView(
            android.widget.TextView(this).apply {
                text = getString(R.string.slide_count_title)
            }
        )
        val countPicker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 20
            value = (existing?.slideCount ?: 1).coerceIn(1, 20)
            layout.addView(this)
        }
        val cyclesPicker = addCyclesSection(layout, existing?.stepCycles ?: 1)
        AlertDialog.Builder(this)
            .setTitle(R.string.step_slide_list)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val checkedId = directionGroup.checkedRadioButtonId
                val direction = if (checkedId >= 0) {
                    directionGroup.findViewById<android.widget.RadioButton>(checkedId)
                        ?.tag as? String ?: "DOWN"
                } else {
                    "DOWN"
                }
                if (existing != null && position != null) {
                    replaceStep(
                        position,
                        existing.copy(
                            slideDirection = direction,
                            slideCount = countPicker.value,
                            stepCycles = cyclesPicker.value
                        )
                    )
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.SLIDE_LIST,
                            slideDirection = direction,
                            slideCount = countPicker.value,
                            stepCycles = cyclesPicker.value,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * QS_TILE: a LIST of standard tile labels — never a typed field.
     * The note below the list states the honest limit: Android offers
     * no API to enumerate the device's actual tiles, so a tile that is
     * absent on this device fails cleanly at run time.
     */
    private fun showQsTileDialog(existing: Step?, position: Int?) {
        val scroll = android.widget.ScrollView(this)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(layout)
        val tileGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        QS_TILE_LABELS.forEach { label ->
            tileGroup.addView(
                android.widget.RadioButton(this).apply {
                    text = label
                    isChecked = existing?.qsTileLabel == label
                    tag = label
                }
            )
        }
        layout.addView(tileGroup)
        layout.addView(
            android.widget.TextView(this).apply {
                text = getString(R.string.qs_tile_note)
            }
        )
        val cyclesPicker = addCyclesSection(layout, existing?.stepCycles ?: 1)
        AlertDialog.Builder(this)
            .setTitle(R.string.qs_tile_title)
            .setView(scroll)
            .setPositiveButton(R.string.ok) { _, _ ->
                val checkedId = tileGroup.checkedRadioButtonId
                if (checkedId < 0) return@setPositiveButton
                val label = tileGroup.findViewById<android.widget.RadioButton>(checkedId)
                    ?.tag as? String ?: return@setPositiveButton
                if (existing != null && position != null) {
                    replaceStep(
                        position,
                        existing.copy(
                            qsTileLabel = label,
                            stepCycles = cyclesPicker.value
                        )
                    )
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.QS_TILE,
                            qsTileLabel = label,
                            stepCycles = cyclesPicker.value,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * POWER_DIALOG: no configuration — just the explanatory note
     * (closest alternative to a power long-press, which cannot be
     * injected) plus the cycles picker.
     */
    private fun showPowerDialogDialog(existing: Step?, position: Int?) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(
            android.widget.TextView(this).apply {
                text = getString(R.string.power_dialog_note)
            }
        )
        val cyclesPicker = addCyclesSection(layout, existing?.stepCycles ?: 1)
        AlertDialog.Builder(this)
            .setTitle(R.string.power_dialog_title)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (existing != null && position != null) {
                    replaceStep(
                        position,
                        existing.copy(stepCycles = cyclesPicker.value)
                    )
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.POWER_DIALOG,
                            stepCycles = cyclesPicker.value,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Sub-task picker: other active workflows only, never this workflow itself. */
    private fun showSubTaskDialog(existing: Step?, position: Int?) {
        val others = WorkflowStore.loadActive(this).filter { it.id != workflowId }
        if (others.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage(R.string.no_workflows_to_pick)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        val labels = others.map { it.name }
        var selected = others.indexOfFirst { it.id == existing?.subWorkflowId }
        // Single-choice list rebuilt as a RadioGroup so the Cycles
        // picker can sit below it in the same dialog (a builder
        // setSingleChoiceItems dialog has no custom-view slot for it).
        // Selection behaviour is unchanged: one workflow, none by
        // default. Cycles here repeats the whole sub-workflow, since
        // this Sub-task step itself is a top-level step.
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val group = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        labels.forEachIndexed { index, label ->
            group.addView(
                android.widget.RadioButton(this).apply {
                    text = label
                    isChecked = index == selected
                    setOnClickListener { selected = index }
                }
            )
        }
        layout.addView(group)
        val cyclesPicker = addCyclesSection(
            layout, existing?.stepCycles ?: 1
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_workflow)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (selected < 0) return@setPositiveButton
                val subWorkflowId = others[selected].id
                if (existing != null && position != null) {
                    replaceStep(
                        position,
                        existing.copy(
                            subWorkflowId = subWorkflowId,
                            stepCycles = cyclesPicker.value
                        )
                    )
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.SUB_TASK,
                            subWorkflowId = subWorkflowId,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS,
                            stepCycles = cyclesPicker.value
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
