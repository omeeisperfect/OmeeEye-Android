package com.nimu.omeeeye.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.NumberPicker
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
    }

    private lateinit var nameEditText: EditText
    private lateinit var stepListView: RecyclerView
    private lateinit var adapter: StepAdapter
    private var touchHelper: ItemTouchHelper? = null

    private var existingWorkflow: Workflow? = null
    private lateinit var workflowId: String
    private val steps: MutableList<Step> = mutableListOf()

    // Data-driven repeat state (workflow-level, edited via the Data button).
    private var dataType: String = ""
    private var dataFileName: String = ""
    private var startRow: Int = 1
    private var dataRepeat: Int = 0

    // Recognition / area-click edit state (-1 = creating a new step).
    private var recogEditIndex: Int = -1
    private var areaClickEditIndex: Int = -1

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
                // User aborted from the overlay: nothing changes.
                pickKind = null
                return
            }
            "point" -> {
                val point = Point(obj.optInt("x"), obj.optInt("y"))
                when (pickKind) {
                    PickKind.TAP_NEW -> addStep(
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
                    PickKind.SWIPE_NEW -> addStep(
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
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.AREA_CLICK,
                            regionLeft = left, regionTop = top,
                            regionRight = right, regionBottom = bottom,
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
                        val position = steps.size
                        steps.addAll(added)
                        adapter.notifyItemRangeInserted(position, added.size)
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
        findViewById<Button>(R.id.btn_data).setOnClickListener { showDataDialog() }
        findViewById<Button>(R.id.btn_save).setOnClickListener { saveAndFinish() }

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

    private fun addStep(step: Step) {
        steps.add(step)
        adapter.notifyItemInserted(steps.size - 1)
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
            StepType.SCREENSHOT,
            StepType.PASTE,
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
            StepType.AREA_CLICK -> launchAreaPick(-1)
            StepType.WAIT_RANDOM -> showWaitRandomDialog(null, null)
            StepType.INTENT_URI -> showUriDialog(null, null, false)
            StepType.NETWORK_GET -> showUriDialog(null, null, true)
        }
    }

    private fun editStep(position: Int) {
        val step = steps.getOrNull(position) ?: return
        when (step.type) {
            StepType.TAP -> {
                pickKind = PickKind.TAP_EDIT
                pickEditIndex = position
                launchPick(getString(R.string.pick_on_screen))
            }
            StepType.SWIPE -> {
                pickKind = PickKind.SWIPE_EDIT
                pickEditIndex = position
                launchSwipePick()
            }
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
            StepType.AREA_CLICK -> launchAreaPick(position)
            StepType.WAIT_RANDOM -> showWaitRandomDialog(step, position)
            StepType.INTENT_URI -> showUriDialog(step, position, false)
            StepType.NETWORK_GET -> showUriDialog(step, position, true)
            StepType.SCREENSHOT,
            StepType.PASTE,
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
        val labels = snippets.map { it.name }.toTypedArray()
        var selected = snippets.indexOfFirst { it.id == existing?.snippetId }
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_snippet)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.ok) { _, _ ->
                if (selected < 0) return@setPositiveButton
                val snippetId = snippets[selected].id
                if (existing != null && position != null) {
                    replaceStep(position, existing.copy(snippetId = snippetId))
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.TYPE_CODE,
                            snippetId = snippetId,
                            mistakeLevel = MistakeLevel.NORMAL,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS
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
        AlertDialog.Builder(this)
            .setTitle(R.string.mistakes_title)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.ok) { _, _ ->
                val level = when (selected) {
                    1 -> MistakeLevel.LIGHT
                    2 -> MistakeLevel.NORMAL
                    3 -> MistakeLevel.HEAVY
                    else -> MistakeLevel.OFF
                }
                if (existing != null && position != null) {
                    replaceStep(position, existing.copy(mistakeLevel = level))
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.TYPE_EXCEL_ROW,
                            mistakeLevel = level,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS
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
        AlertDialog.Builder(this)
            .setTitle(R.string.step_wait_random)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val minMs = minPicker.value * 100L
                var maxMs = maxPicker.value * 100L
                if (maxMs < minMs) maxMs = minMs
                if (existing != null && position != null) {
                    replaceStep(position, existing.copy(waitMinMs = minMs, waitMaxMs = maxMs))
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.WAIT_RANDOM,
                            waitMinMs = minMs,
                            waitMaxMs = maxMs,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** INTENT_URI / NETWORK_GET: a URI/URL is text, not a location. */
    private fun showUriDialog(existing: Step?, position: Int?, isNetwork: Boolean) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(if (isNetwork) existing?.networkUrl ?: "" else existing?.intentUri ?: "")
            hint = getString(
                if (isNetwork) R.string.network_url_hint else R.string.intent_uri_hint
            )
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle(
                if (isNetwork) R.string.step_network_get else R.string.step_intent_uri
            )
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val value = input.text.toString()
                if (existing != null && position != null) {
                    replaceStep(
                        position,
                        if (isNetwork) existing.copy(networkUrl = value)
                        else existing.copy(intentUri = value)
                    )
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = if (isNetwork) StepType.NETWORK_GET else StepType.INTENT_URI,
                            networkUrl = if (isNetwork) value else "",
                            intentUri = if (isNetwork) "" else value,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
                if (existing != null && position != null) {
                    replaceStep(
                        position,
                        existing.copy(
                            text = text, waitAfterMs = waitAfterMs, mistakeLevel = level
                        )
                    )
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.TYPE_TEXT,
                            text = text,
                            waitAfterMs = waitAfterMs,
                            mistakeLevel = level
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** WAIT step duration (not the per-step wait-after, which has its own editor). */
    private fun showWaitDurationDialog(existing: Step?, position: Int?) {
        val picker = NumberPicker(this).apply {
            minValue = 0
            maxValue = WAIT_PICKER_MAX
            value = ((existing?.durationMs ?: 0) / 100).toInt()
                .coerceIn(0, WAIT_PICKER_MAX)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.step_wait)
            .setView(picker)
            .setPositiveButton(R.string.ok) { _, _ ->
                val durationMs = picker.value * 100L
                if (existing != null && position != null) {
                    replaceStep(position, existing.copy(durationMs = durationMs))
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.WAIT,
                            durationMs = durationMs,
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
        val labels = arrayOf(
            getString(R.string.nav_back),
            getString(R.string.nav_home),
            getString(R.string.nav_recents)
        )
        var selected = when (existing?.navKey) {
            NavKey.BACK -> 0
            NavKey.HOME -> 1
            NavKey.RECENTS -> 2
            null -> -1
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.step_navigation)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.ok) { _, _ ->
                if (selected < 0) return@setPositiveButton
                val navKey = when (selected) {
                    0 -> NavKey.BACK
                    1 -> NavKey.HOME
                    else -> NavKey.RECENTS
                }
                if (existing != null && position != null) {
                    replaceStep(position, existing.copy(navKey = navKey))
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.NAVIGATION,
                            navKey = navKey,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** App picker: label shown, package name stored. No typed package field. */
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
        val labels = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        var selected = apps.indexOfFirst {
            it.activityInfo?.packageName == existing?.packageName
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_app)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.ok) { _, _ ->
                if (selected < 0) return@setPositiveButton
                val packageName = apps[selected].activityInfo?.packageName
                    ?: return@setPositiveButton
                if (existing != null && position != null) {
                    replaceStep(position, existing.copy(packageName = packageName))
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.LAUNCH_APP,
                            packageName = packageName,
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
        val labels = others.map { it.name }.toTypedArray()
        var selected = others.indexOfFirst { it.id == existing?.subWorkflowId }
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_workflow)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.ok) { _, _ ->
                if (selected < 0) return@setPositiveButton
                val subWorkflowId = others[selected].id
                if (existing != null && position != null) {
                    replaceStep(position, existing.copy(subWorkflowId = subWorkflowId))
                } else {
                    addStep(
                        Step(
                            id = WorkflowStore.newId(),
                            type = StepType.SUB_TASK,
                            subWorkflowId = subWorkflowId,
                            waitAfterMs = DEFAULT_WAIT_AFTER_MS
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
