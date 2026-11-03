package com.nimu.omeeeye.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.CodeLibraryStore
import com.nimu.omeeeye.data.DataFileStore
import com.nimu.omeeeye.data.DataSourceLoader
import com.nimu.omeeeye.data.WorkflowStore
import com.nimu.omeeeye.engine.AutomationService
import com.nimu.omeeeye.engine.OcrEngine
import com.nimu.omeeeye.model.AfterRetryMode
import com.nimu.omeeeye.model.AreaMode
import com.nimu.omeeeye.model.MatchMode
import com.nimu.omeeeye.model.MistakeLevel
import com.nimu.omeeeye.model.NavKey
import com.nimu.omeeeye.model.Point
import com.nimu.omeeeye.model.Step
import com.nimu.omeeeye.model.StepType
import com.nimu.omeeeye.model.SuccessMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Editor for ONE recognition step (Button / Image / Color / OCR),
 * reference structure: top card (Judgment method + Recognition area
 * dropdown pills, button/image/color value rows), actions after
 * successful recognition (Perform click / Custom actions / Jump &
 * Continue cards), retry, actions after the retry, Cycles, then
 * SAVE / CLOSE green pills (CLOSE bottom-right).
 *
 * Intent extras in:
 * - "step_json" (String, optional): the step being edited, serialised
 *   with WorkflowStore.stepToJson. Absent = a new step.
 * - "step_type" (String): StepType name for a new step.
 * - "workflow_steps_json" (String, optional): JSONArray of the workflow's
 *   EXISTING steps, for the jump pickers.
 *
 * Result: RESULT_OK with "step_json" (WorkflowStore.stepToJson of the
 * edited step), or RESULT_CANCELED (Close / back press, nothing saved).
 *
 * Standing rules honoured here, with no exceptions:
 * - Locations are ONLY picked on screen, via the OverlayPickService
 *   overlay over other apps (region drags, point taps). Button text /
 *   ID / description and the OCR variable name are TEXT, not locations,
 *   so typing those is allowed.
 * - Jump targets are ALWAYS picked from the workflow's existing steps
 *   (never a typed id, never the step being edited itself). When the
 *   workflow has no other step, the jump pickers show a hint instead of
 *   an empty list, and Save is disabled while a Jump mode is selected.
 * - No fake functionality: a step that needs a template / color area /
 *   button identification cannot be saved without it, and a failed
 *   template/color capture saves nothing (the overlay pick cancels).
 *
 * Self-contained: it does NOT depend on TaskEditorActivity internals.
 * Sub-action steps get fresh ids via WorkflowStore.newId() and a
 * waitAfterMs of 2000, matching the main editor's convention.
 */
/** One-shot holder for an OCR test read (fix round, item 6). The editor
 * moves its task to the back so the screenshot shows the real screen,
 * then returns; the result waits here for onResume(). If the editor is
 * finished meanwhile, active is false and the result is dropped instead
 * of yanking the user back into a dead editor. */
object OcrTestReadStore {
    var active: Boolean = false
    var result: String? = null
    fun take(): String? {
        val r = result
        result = null
        return r
    }
}

class RecognitionEditActivity : AppCompatActivity() {

    companion object {
        private const val WAIT_AFTER_MS = 2000L
        private const val MAX_PICKER_100MS = 300 // 300 x 100 ms = 30,000 ms
        // Existing SharedPreferences file (same one ThemePrefs uses).
        private const val PREFS_NAME = "omeeeye_prefs"
        private const val KEY_ANCHOR_GUIDE_DISMISSED = "color_anchor_guide_dismissed"
    }

    // ---- Step being edited ----
    private lateinit var stepType: StepType
    private lateinit var originalStep: Step

    // Jump-picker source: (original workflow index, step), excluding the
    // step being edited itself. Indexes stay ORIGINAL so the labels
    // ("Step 2: Tap") match the workflow's real step numbers.
    private val jumpChoices: MutableList<Pair<Int, Step>> = mutableListOf()
    private val hasJumpTargets: Boolean
        get() = jumpChoices.isNotEmpty()

    // Loop-action target source (reference-workflow clone): same
    // (original workflow index, step) shape as jumpChoices, from the
    // "loop_steps_json" intent extra (all top-level steps, in order).
    // When the extra is absent, the jump choices are the fallback
    // (they already exclude the edited step). Nested editors forward
    // their parent's list, so a loop sub-action always targets a
    // top-level step. [loopExcludeIds] holds enclosing recognition
    // steps that must never be loop targets (a step looping its own
    // enclosing recognition step would only fail on the depth guard).
    private val loopChoices: MutableList<Pair<Int, Step>> = mutableListOf()
    private val loopExcludeIds: MutableSet<String> = mutableSetOf()

    /** Loop targets, minus this editor's own step and excluded enclosing steps. */
    private fun loopTargetChoices(): List<Pair<Int, Step>> =
        loopChoices.filter { (_, step) ->
            step.id != originalStep.id && step.id !in loopExcludeIds
        }

    // ---- Mutable editor state (read from the views again at save time
    // for text / picker values; these hold what the views cannot) ----
    private var matchMode: MatchMode = MatchMode.EXACT
    private var areaMode: AreaMode = AreaMode.FULLSCREEN
    private var regionLeft: Int = -1
    private var regionTop: Int = -1
    private var regionRight: Int = -1
    private var regionBottom: Int = -1
    private var templatePath: String = ""
    private var colorValue: Int = 0
    private var colorPicked: Boolean = false
    // Color anchor (item 6): -1 = no anchor, old region behaviour.
    private var anchorX: Int = -1
    private var anchorY: Int = -1
    private val anchorPicked: Boolean
        get() = anchorX >= 0 && anchorY >= 0
    private var stepCycles: Int = 1
    // Inline numeric values (the layout NumberPickers are gone):
    // same ranges the pickers had — click 1..10, tolerance 0..100,
    // retries 0..10, retry delay 0..30000 ms, cycles 1..99.
    private var clickCountVal: Int = 1
    private var toleranceVal: Int = 0
    private var retryCountVal: Int = 0
    private var retryDelayMsVal: Long = 0L
    private var successMode: SuccessMode = SuccessMode.CLICK
    private var jumpTarget: Int = -1
    private var afterRetryMode: AfterRetryMode = AfterRetryMode.IGNORE
    private var afterRetryJumpTarget: Int = -1

    private val successActions: MutableList<Step> = mutableListOf()
    private val retryActions: MutableList<Step> = mutableListOf()
    private val afterActions: MutableList<Step> = mutableListOf()

    private val hasRegion: Boolean
        get() = regionLeft >= 0 && regionTop >= 0 &&
            regionRight > regionLeft && regionBottom > regionTop

    // ---- Views ----
    // Judgment / area pills are compact TextViews, not Buttons.
    private lateinit var btnJudgment: TextView
    private lateinit var judgmentLabel: TextView
    private lateinit var btnJudgmentHelp: View
    private lateinit var btnArea: TextView
    // Button text / ID / description are value TextViews edited
    // through their pencil buttons (reference top card), not inline
    // EditTexts. Every read/write site uses .text / .setText, which
    // TextView provides, so no save/load logic changes.
    private lateinit var editButtonText: TextView
    private lateinit var editButtonId: TextView
    private lateinit var editButtonDesc: TextView
    private lateinit var imgTemplatePreview: ImageView
    private lateinit var textTemplateStatus: TextView
    private lateinit var viewColorSwatch: View
    private lateinit var textColorStatus: TextView
    private lateinit var textToleranceValue: TextView
    private lateinit var editOcrMatch: EditText
    private lateinit var editOcrVariable: EditText
    // One RadioButton per mode card (no RadioGroup: radios live in
    // separate cards, so exclusivity is managed in code via
    // setSuccessMode / setAfterRetryMode).
    private lateinit var radioSuccessClick: RadioButton
    private lateinit var radioSuccessCustom: RadioButton
    private lateinit var radioSuccessJump: RadioButton
    // Click count is the inline "xN" badge next to the pill; tapping
    // it opens the validated edit dialog (no picker on the page).
    private lateinit var textClickCountBadge: TextView
    private lateinit var successActionsGroup: View
    private lateinit var switchClickFirst: CheckBox
    private lateinit var listSuccessActions: LinearLayout
    private lateinit var textSuccessEmpty: TextView
    private lateinit var successJumpGroup: View
    // Jump dropdown pills (dashed outline); empty text = unset.
    private lateinit var textSuccessJumpTarget: TextView
    private lateinit var listRetryActions: LinearLayout
    private lateinit var textRetryEmpty: TextView
    private lateinit var textRetryCountValue: TextView
    private lateinit var textRetryDelayValue: TextView
    private lateinit var radioAfterIgnore: RadioButton
    private lateinit var radioAfterCustom: RadioButton
    private lateinit var radioAfterJump: RadioButton
    private lateinit var afterActionsGroup: View
    private lateinit var listAfterActions: LinearLayout
    private lateinit var textAfterEmpty: TextView
    private lateinit var afterJumpGroup: View
    private lateinit var textAfterJumpTarget: TextView
    private lateinit var checkBreakLoopSuccess: CheckBox
    private lateinit var checkBreakLoopFailure: CheckBox
    private lateinit var btnSave: Button
    private lateinit var textCyclesValue: TextView
    // Programmatic sections: anchor controls appended to the Color
    // group (the Cycles inline value lives in the layout file).
    private lateinit var btnPickAnchor: Button
    private lateinit var textAnchorStatus: TextView
    private var textButtonDetails: TextView? = null
    // OCR test-read preview (fix round, item 6): created in
    // addProgrammaticSections for OCR steps only.
    private var textOcrPreview: TextView? = null

    // ---- Overlay pick state ----
    // Which on-screen pick is in flight for the overlay:
    // "area" (the section-1 region), "search" (image search region),
    // "ocr" (OCR region), "template", "color", "subArea" (an
    // AREA_CLICK sub-action's region).
    private var pendingRegionKind: String? = null
    // Which sub-action list an overlay tap belongs to:
    // "success", "retry" or "after".
    private var pendingSubList: String? = null
    // Index being re-picked inside that list, or -1 = appending.
    private var pendingSubEditIndex: Int = -1
    // Sub-action swipe / area choices, held while the overlay pick
    // is in flight (the step is built when the pick returns).
    private var pendingSubSwipeDurationMs: Long = 500L
    private var pendingSubAreaMode: String = "RANDOM"
    private var pendingSubRecord: Boolean = false
    // Color-anchor point pick in flight (separate from sub-actions).
    private var pendingAnchorPick: Boolean = false
    private var overlayRequestId: String? = null
    // Nested-recognition editor result target (sub-action list + index).
    private var subRecogListKey: String? = null
    private var subRecogEditIndex: Int = -1
    private val subRecogLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val listKey = subRecogListKey
            subRecogListKey = null
            val editIndex = subRecogEditIndex
            subRecogEditIndex = -1
            if (listKey == null) return@registerForActivityResult
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val json = result.data?.getStringExtra("step_json")
                ?: return@registerForActivityResult
            try {
                putSubAction(listKey, WorkflowStore.stepFromJson(JSONObject(json)), editIndex)
            } catch (e: Exception) {
                Toast.makeText(this, "Recognition step could not be loaded", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    // A pick the user asked for but the overlay permission blocked:
    // the request is kept (not dropped) so that returning from system
    // settings with the permission granted resumes it automatically
    // instead of stranding the user on a dead pick button.
    private var pickAwaitingPermission: Boolean = false

    override fun onResume() {
        super.onResume()
        // OCR test-read result (fix round, item 6): show whatever the
        // read actually found (or that nothing was found) as soon as
        // the editor is visible again.
        OcrTestReadStore.take()?.let { result ->
            textOcrPreview?.let { preview ->
                preview.text = result
                preview.visibility = View.VISIBLE
            }
        }
        // Button capture: a capture stored by ButtonCaptureService
        // is consumed (cleared) here and opens the Button detail
        // dialog (reference flow) instead of silently filling fields
        // below the fold. Only non-empty parts overwrite: an
        // id/description-only capture must not wipe typed text.
        // Null = no capture (cancel / failure) -> nothing changes.
        // CANCEL in the dialog restores the pre-capture values.
        val captured = ButtonCaptureStore.take()
        if (captured != null && stepType == StepType.BUTTON_RECOGNITION &&
            ::editButtonText.isInitialized
        ) {
            showButtonDetailDialog(captured)
        }
        // Resume a permission-blocked pick the moment the grant exists.
        // This must run before the early returns below: on the blocked
        // path no result was ever stored, so take() would return null
        // and the resume would be skipped.
        if (pickAwaitingPermission &&
            android.provider.Settings.canDrawOverlays(this)
        ) {
            pickAwaitingPermission = false
            val kind = pendingRegionKind
            val subList = pendingSubList
            if (pendingAnchorPick) {
                launchAnchorPick()
            } else if (pendingSubRecord && subList != null) {
                launchSubRecord(subList)
            } else if (kind != null) {
                launchPickRegion(kind)
            } else if (subList != null) {
                launchSubPointPick(subList, pendingSubEditIndex)
            }
        }
        val requestId = overlayRequestId ?: return
        val json = OverlayPickStore.take(requestId) ?: return
        // A result arrived: the pick is over, whatever it says.
        overlayRequestId = null
        applyOverlayResult(json)
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        // The overlay moves this task to the back; if Android recreates
        // the editor while a pick is in flight, the pending request must
        // survive, or the stored result would never be consumed.
        outState.putString("overlayRequestId", overlayRequestId)
        outState.putString("pendingRegionKind", pendingRegionKind)
        outState.putString("pendingSubList", pendingSubList)
        outState.putBoolean("pickAwaitingPermission", pickAwaitingPermission)
        outState.putBoolean("pendingAnchorPick", pendingAnchorPick)
        outState.putInt("pendingSubEditIndex", pendingSubEditIndex)
        outState.putBoolean("pendingSubRecord", pendingSubRecord)
    }

    /** Applies one overlay result (region or point pick). */
    private fun applyOverlayResult(json: String) {
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            return
        }
        if (obj.optString("kind") == OverlayPickStore.CANCELLED_KIND) {
            pendingRegionKind = null
            pendingSubList = null
            pendingAnchorPick = false
            pendingSubRecord = false
            pendingSubEditIndex = -1
            return
        }
        when (obj.optString("kind")) {
            "record" -> {
                val listKey = pendingSubList
                pendingSubList = null
                pendingSubRecord = false
                if (listKey == null) return
                val stepsJson = obj.optString("steps_json")
                if (stepsJson.isBlank()) return
                try {
                    val arr = JSONArray(stepsJson)
                    val list = subActionList(listKey)
                    for (i in 0 until arr.length()) {
                        list.add(WorkflowStore.stepFromJson(arr.getJSONObject(i)))
                    }
                    renderSubLists()
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Recorded steps could not be loaded",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            "region" -> {
                val kind = pendingRegionKind
                pendingRegionKind = null
                if (kind == null) return
                val left = obj.optInt("left", -1)
                val top = obj.optInt("top", -1)
                val right = obj.optInt("right", -1)
                val bottom = obj.optInt("bottom", -1)
                if (kind == "subArea") {
                    // AREA_CLICK sub-action region: mode was chosen in
                    // its dialog before the pick.
                    val listKey = pendingSubList
                    pendingSubList = null
                    if (listKey == null) return
                    if (left < 0 || top < 0 || right <= left || bottom <= top) return
                    val editIndex = pendingSubEditIndex
                    pendingSubEditIndex = -1
                    val old = subActionList(listKey).getOrNull(editIndex)
                    val built = (old ?: Step(
                        id = WorkflowStore.newId(),
                        type = StepType.AREA_CLICK,
                        waitAfterMs = WAIT_AFTER_MS
                    )).copy(
                        type = StepType.AREA_CLICK,
                        regionLeft = left, regionTop = top,
                        regionRight = right, regionBottom = bottom,
                        areaClickMode = pendingSubAreaMode
                    )
                    putSubAction(listKey, built, editIndex)
                    refreshSaveEnabled()
                    return
                }
                when (kind) {
                    "template" -> {
                        val path = obj.optString("templatePath", "")
                        if (path.isNotBlank()) {
                            // Preview BEFORE storing: the capture file
                            // already exists on disk (the overlay saved
                            // it), but it only becomes this step's
                            // template when the user taps Save.
                            val bitmap = try {
                                BitmapFactory.decodeFile(
                                    File(filesDir, path).absolutePath
                                )
                            } catch (e: Exception) {
                                null
                            }
                            if (bitmap != null) {
                                showTemplatePreviewDialog(path, bitmap)
                            } else {
                                templatePath = path
                                refreshTemplateViews()
                            }
                        }
                    }
                    "color" -> {
                        if (left >= 0 && top >= 0 && right > left && bottom > top) {
                            setRegion(left, top, right, bottom)
                            colorValue = obj.optInt("colorValue", 0)
                            colorPicked = true
                            refreshColorViews()
                        }
                    }
                    else -> {
                        // "area", "search", "ocr": a picked region, searched
                        // in REGION mode from now on.
                        if (left >= 0 && top >= 0 && right > left && bottom > top) {
                            setRegion(left, top, right, bottom)
                            areaMode = AreaMode.REGION
                            refreshAreaViews()
                            refreshButtonDetails()
                        }
                    }
                }
                refreshSaveEnabled()
            }
            "point" -> {
                val point = Point(obj.optInt("x"), obj.optInt("y"))
                if (pendingAnchorPick) {
                    pendingAnchorPick = false
                    handleAnchorPoint(point)
                    return
                }
                val listKey = pendingSubList
                pendingSubList = null
                if (listKey == null) return
                val editIndex = pendingSubEditIndex
                pendingSubEditIndex = -1
                val old = subActionList(listKey).getOrNull(editIndex)
                val step = (old ?: Step(
                    id = WorkflowStore.newId(),
                    type = StepType.TAP,
                    waitAfterMs = WAIT_AFTER_MS
                )).copy(type = StepType.TAP, point = point)
                putSubAction(listKey, step, editIndex)
            }
            "swipe" -> {
                // Swipe sub-action: duration was chosen in its dialog
                // before the pick.
                val listKey = pendingSubList
                pendingSubList = null
                if (listKey == null) return
                val editIndex = pendingSubEditIndex
                pendingSubEditIndex = -1
                val start = Point(obj.optInt("startX"), obj.optInt("startY"))
                val end = Point(obj.optInt("endX"), obj.optInt("endY"))
                val old = subActionList(listKey).getOrNull(editIndex)
                val step = (old ?: Step(
                    id = WorkflowStore.newId(),
                    type = StepType.SWIPE,
                    waitAfterMs = WAIT_AFTER_MS
                )).copy(
                    type = StepType.SWIPE,
                    start = start, end = end,
                    durationMs = pendingSubSwipeDurationMs
                )
                putSubAction(listKey, step, editIndex)
            }
        }
    }

    // ---------- Lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        // Reference LIGHT theme for this screen only: local night
        // mode, set before super.onCreate, so night-qualified
        // resources resolve to their day values here without
        // changing the rest of the app's theme.
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recognition_edit)

        // Restore an in-flight overlay pick after recreation (see
        // onSaveInstanceState): without this, a recreated editor would
        // never consume the stored pick result.
        if (savedInstanceState != null) {
            overlayRequestId = savedInstanceState.getString("overlayRequestId")
            pendingRegionKind = savedInstanceState.getString("pendingRegionKind")
            pendingSubList = savedInstanceState.getString("pendingSubList")
            pickAwaitingPermission =
                savedInstanceState.getBoolean("pickAwaitingPermission", false)
            pendingAnchorPick =
                savedInstanceState.getBoolean("pendingAnchorPick", false)
            pendingSubEditIndex =
                savedInstanceState.getInt("pendingSubEditIndex", -1)
            pendingSubRecord =
                savedInstanceState.getBoolean("pendingSubRecord", false)
        }

        loadInputs()
        bindViews()
        addProgrammaticSections()
        applyStepTypeVisibility()
        loadStateIntoViews()
        wireButtons()
        refreshAll()
    }

    private fun loadInputs() {
        val stepJson = intent.getStringExtra("step_json")
        originalStep = if (!stepJson.isNullOrBlank()) {
            try {
                WorkflowStore.stepFromJson(JSONObject(stepJson))
            } catch (e: Exception) {
                null
            }
        } else {
            null
        } ?: run {
            val typeName = intent.getStringExtra("step_type") ?: "BUTTON_RECOGNITION"
            val type = try {
                StepType.valueOf(typeName)
            } catch (e: Exception) {
                StepType.BUTTON_RECOGNITION
            }
            Step(id = WorkflowStore.newId(), type = type)
        }
        stepType = originalStep.type

        // Existing steps for the jump pickers, minus this step itself.
        val stepsJson = intent.getStringExtra("workflow_steps_json")
        if (!stepsJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(stepsJson)
                for (i in 0 until arr.length()) {
                    val step = WorkflowStore.stepFromJson(arr.getJSONObject(i))
                    if (step.id != originalStep.id) {
                        jumpChoices.add(i to step)
                    }
                }
            } catch (e: Exception) {
                // A corrupt list just means no jump targets, handled below.
            }
        }

        matchMode = originalStep.matchMode
        areaMode = originalStep.areaMode
        regionLeft = originalStep.regionLeft
        regionTop = originalStep.regionTop
        regionRight = originalStep.regionRight
        regionBottom = originalStep.regionBottom
        templatePath = originalStep.templatePath
        colorValue = originalStep.colorValue
        anchorX = originalStep.anchorX
        anchorY = originalStep.anchorY
        stepCycles = originalStep.stepCycles
        // A color step that already has a picked region was picked
        // on screen before; treat its area as picked. A stored anchor
        // counts as picked too (it carries its own color).
        colorPicked = stepType == StepType.COLOR_RECOGNITION &&
            (hasRegion || anchorPicked)
        successMode = originalStep.successMode
        jumpTarget = originalStep.jumpTarget
        afterRetryMode = originalStep.afterRetryMode
        afterRetryJumpTarget = originalStep.afterRetryJumpTarget
        successActions.addAll(originalStep.customSteps)
        retryActions.addAll(originalStep.retrySteps)
        afterActions.addAll(originalStep.afterRetrySteps)

        // A stored jump target that no longer points at another existing
        // step is dropped rather than saved back as an invalid jump.
        if (jumpTarget !in jumpChoices.map { it.first }) jumpTarget = -1
        if (afterRetryJumpTarget !in jumpChoices.map { it.first }) {
            afterRetryJumpTarget = -1
        }

        // Loop-action targets: the explicit extra first, else the jump
        // choices (same workflow steps, self already excluded).
        val loopJson = intent.getStringExtra("loop_steps_json")
        if (!loopJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(loopJson)
                for (i in 0 until arr.length()) {
                    loopChoices.add(i to WorkflowStore.stepFromJson(arr.getJSONObject(i)))
                }
            } catch (e: Exception) {
                // A corrupt list just means no loop targets, handled below.
            }
        }
        if (loopChoices.isEmpty()) {
            loopChoices.addAll(jumpChoices)
        }
        intent.getStringExtra("loop_exclude_ids")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.let { loopExcludeIds.addAll(it) }
    }

    private fun bindViews() {
        btnJudgment = findViewById(R.id.btn_judgment)
        judgmentLabel = findViewById(R.id.rec_judgment_label)
        btnJudgmentHelp = findViewById(R.id.btn_judgment_help)
        btnArea = findViewById(R.id.btn_area)
        editButtonText = findViewById(R.id.edit_button_text)
        editButtonId = findViewById(R.id.edit_button_id)
        editButtonDesc = findViewById(R.id.edit_button_desc)
        imgTemplatePreview = findViewById(R.id.img_template_preview)
        textTemplateStatus = findViewById(R.id.text_template_status)
        viewColorSwatch = findViewById(R.id.view_color_swatch)
        textColorStatus = findViewById(R.id.text_color_status)
        textToleranceValue = findViewById(R.id.text_tolerance_value)
        editOcrMatch = findViewById(R.id.edit_ocr_match)
        editOcrVariable = findViewById(R.id.edit_ocr_variable)
        radioSuccessClick = findViewById(R.id.radio_success_click)
        radioSuccessCustom = findViewById(R.id.radio_success_custom)
        radioSuccessJump = findViewById(R.id.radio_success_jump)
        textClickCountBadge = findViewById(R.id.text_click_count_badge)
        successActionsGroup = findViewById(R.id.success_actions_group)
        switchClickFirst = findViewById(R.id.switch_click_first)
        listSuccessActions = findViewById(R.id.list_success_actions)
        textSuccessEmpty = findViewById(R.id.text_success_empty)
        successJumpGroup = findViewById(R.id.success_jump_group)
        textSuccessJumpTarget = findViewById(R.id.btn_success_jump_target)
        listRetryActions = findViewById(R.id.list_retry_actions)
        textRetryEmpty = findViewById(R.id.text_retry_empty)
        textRetryCountValue = findViewById(R.id.text_retry_count_value)
        textRetryDelayValue = findViewById(R.id.text_retry_delay_value)
        radioAfterIgnore = findViewById(R.id.radio_after_ignore)
        radioAfterCustom = findViewById(R.id.radio_after_custom)
        radioAfterJump = findViewById(R.id.radio_after_jump)
        afterActionsGroup = findViewById(R.id.after_actions_group)
        listAfterActions = findViewById(R.id.list_after_actions)
        textAfterEmpty = findViewById(R.id.text_after_empty)
        afterJumpGroup = findViewById(R.id.after_jump_group)
        textAfterJumpTarget = findViewById(R.id.btn_after_jump_target)
        checkBreakLoopSuccess = findViewById(R.id.check_break_loop_success)
        checkBreakLoopFailure = findViewById(R.id.check_break_loop_failure)
        btnSave = findViewById(R.id.btn_rec_save)
        textCyclesValue = findViewById(R.id.text_cycles_value)
    }

    /**
     * Programmatic UI: "Pick anchor point" + anchor status inside the
     * Color group, the Button detail summary + capture button, and
     * the OCR test-read button + preview. The Cycles inline value
     * lives in the layout file (before the Save / Close row, all four
     * recognition types) and is bound in bindViews().
     */
    private fun addProgrammaticSections() {
        val colorGroup = findViewById<LinearLayout>(R.id.group_color)
        btnPickAnchor = Button(this).apply {
            text = getString(R.string.color_anchor_pick)
        }
        textAnchorStatus = TextView(this).apply {
            text = getString(R.string.color_anchor_none)
            val pad = (4 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, 0)
        }
        colorGroup.addView(btnPickAnchor)
        colorGroup.addView(textAnchorStatus)

        // Button capture (item 3, Button steps only): opens the
        // on-screen capture overlay; the result returns via
        // ButtonCaptureStore and is consumed in onResume().
        if (stepType == StepType.BUTTON_RECOGNITION) {
            val buttonGroup = findViewById<LinearLayout>(R.id.group_button)
            // Detail summary (fix round, item 4): after a capture, the
            // captured element's details show here, clearly labelled,
            // in the reference order — Judgment method, Recognition
            // area, Button text, Button ID, Button description — above
            // the editable fields. The success / failure sections below
            // are untouched.
            textButtonDetails = TextView(this).apply {
                textSize = 14f
                val pad = (4 * resources.displayMetrics.density).toInt()
                setPadding(0, 0, 0, pad)
                visibility = View.GONE
            }
            buttonGroup.addView(textButtonDetails, 0)
            buttonGroup.addView(
                Button(this).apply {
                    text = getString(R.string.btn_capture_from_screen)
                    setOnClickListener { launchButtonCapture() }
                }
            )
        }
        // OCR test read (fix round, item 6): reads the picked region
        // (or the whole screen in Fullscreen mode) right now and shows
        // what was actually detected, instead of storing it silently.
        if (stepType == StepType.OCR_READ) {
            val ocrGroup = findViewById<LinearLayout>(R.id.group_ocr)
            textOcrPreview = TextView(this).apply {
                textSize = 14f
                visibility = View.GONE
            }
            ocrGroup.addView(
                Button(this).apply {
                    text = getString(R.string.ocr_test_read)
                    setOnClickListener { startOcrTestRead() }
                }
            )
            ocrGroup.addView(textOcrPreview)
        }
    }

    /** OCR test read (fix round, item 6). The editor moves its task to
     * the back so the screenshot shows the real screen, not this
     * editor (the same reason template capture hides its overlay);
     * after a short delay the screenshot is read and the editor is
     * brought back with the result, shown in the preview line. */
    private fun startOcrTestRead() {
        val preview = textOcrPreview ?: return
        val region = when {
            hasRegion -> android.graphics.Rect(
                regionLeft, regionTop, regionRight, regionBottom
            )
            areaMode == AreaMode.FULLSCREEN -> null
            else -> {
                Toast.makeText(this, R.string.ocr_test_no_region, Toast.LENGTH_SHORT).show()
                return
            }
        }
        OcrTestReadStore.active = true
        OcrTestReadStore.result = null
        preview.visibility = View.GONE
        moveTaskToBack(true)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Thread {
                val resultText: String = run {
                    val service = AutomationService.instance
                        ?: return@run getString(R.string.ocr_test_no_service)
                    val shot = try {
                        service.takeScreenshotBitmap()
                    } catch (e: Exception) {
                        null
                    } ?: return@run getString(
                        R.string.ocr_test_no_shot,
                        service.lastScreenshotError ?: "unknown"
                    )
                    try {
                        val read = OcrEngine.read(shot, region).text.trim()
                        if (read.isEmpty()) {
                            getString(R.string.ocr_test_no_text)
                        } else {
                            getString(R.string.ocr_test_detected, read.take(200))
                        }
                    } catch (e: Exception) {
                        getString(R.string.ocr_test_no_text)
                    } finally {
                        try {
                            shot.recycle()
                        } catch (ignored: Exception) {
                        }
                    }
                }
                runOnUiThread {
                    if (!OcrTestReadStore.active) return@runOnUiThread
                    OcrTestReadStore.result = resultText
                    // Same CLEAR_TOP + SINGLE_TOP reuse as the overlay
                    // finish path: the existing editor comes forward.
                    val intent = Intent(this, RecognitionEditActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    }
                    startActivity(intent)
                }
            }.start()
        }, 700)
    }

    override fun onDestroy() {
        // A finished editor must never be yanked back by a test read
        // that completes after the user left.
        OcrTestReadStore.active = false
        super.onDestroy()
    }

    /** Button detail summary (fix round, item 4). Shows the captured
     * element's identification, clearly labelled, in the reference
     * order. Shown whenever anything is captured or picked; hidden for
     * a fresh, still-empty Button step. */
    private fun refreshButtonDetails() {
        val summary = textButtonDetails ?: return
        if (stepType != StepType.BUTTON_RECOGNITION ||
            !::editButtonText.isInitialized
        ) {
            summary.visibility = View.GONE
            return
        }
        val empty = getString(R.string.rec_details_empty)
        val text = editButtonText.text.toString().trim()
        val id = editButtonId.text.toString().trim()
        val desc = editButtonDesc.text.toString().trim()
        if (text.isEmpty() && id.isEmpty() && desc.isEmpty() && !hasRegion) {
            summary.visibility = View.GONE
            return
        }
        val judgment = judgmentLabel(matchMode)
        val area = when (areaMode) {
            AreaMode.FULLSCREEN -> getString(R.string.rec_area_fullscreen)
            AreaMode.REGION -> if (hasRegion) {
                "${getString(R.string.rec_area_region)} " +
                    "($regionLeft, $regionTop, $regionRight, $regionBottom)"
            } else {
                getString(R.string.rec_area_region)
            }
        }
        summary.text = getString(R.string.rec_button_details_title) + "\n" +
            "${getString(R.string.rec_judgment_label)}: $judgment\n" +
            "${getString(R.string.rec_area_label)}: $area\n" +
            "${getString(R.string.rec_button_text_label)}: ${text.ifEmpty { empty }}\n" +
            "${getString(R.string.rec_button_id_label)}: ${id.ifEmpty { empty }}\n" +
            "${getString(R.string.rec_button_desc_label)}: ${desc.ifEmpty { empty }}"
        summary.visibility = View.VISIBLE
    }

    /**
     * Button detail dialog (reference flow), shown after a screen
     * capture returns. Order matches the reference: Judgment method,
     * Recognition area (Fullscreen / Region + pick-region control
     * that launches the existing overlay region pick), button text
     * (with the manual-entry note), button ID, button description,
     * then CANCEL / OK.
     *
     * Captured values are applied to the form first (non-empty parts
     * only, as before) so the dialog edits real values; CANCEL
     * restores the pre-capture judgment, area, region and fields, so
     * a discarded capture changes nothing. OK applies the dialog's
     * edits. "Pick region on screen" applies the dialog's edits,
     * closes the dialog and launches the existing overlay pick.
     * Nothing here changes stored semantics: judgment still maps to
     * MatchMode, area to AreaMode, region to the picked region.
     */
    private fun showButtonDetailDialog(
        captured: ButtonCaptureStore.CapturedButton
    ) {
        val oldText = editButtonText.text.toString()
        val oldId = editButtonId.text.toString()
        val oldDesc = editButtonDesc.text.toString()
        val oldMatchMode = matchMode
        val oldAreaMode = areaMode
        val oldLeft = regionLeft
        val oldTop = regionTop
        val oldRight = regionRight
        val oldBottom = regionBottom
        if (captured.text.isNotEmpty()) {
            editButtonText.setText(captured.text)
        }
        if (captured.viewId.isNotEmpty()) {
            editButtonId.setText(captured.viewId)
        }
        if (captured.description.isNotEmpty()) {
            editButtonDesc.setText(captured.description)
        }
        refreshButtonDetails()

        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val smallPad = (4 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun addLabel(text: String) {
            layout.addView(
                TextView(this).apply {
                    this.text = text
                    setPadding(0, smallPad, 0, smallPad)
                }
            )
        }
        addLabel(getString(R.string.add_detail_judgment))
        val judgmentGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val sameButton = android.widget.RadioButton(this).apply {
            text = getString(R.string.add_judgment_same)
            isChecked = matchMode == MatchMode.EXACT
        }
        val containsButton = android.widget.RadioButton(this).apply {
            text = getString(R.string.add_judgment_contains)
            isChecked = matchMode == MatchMode.PARTIAL
        }
        judgmentGroup.addView(sameButton)
        judgmentGroup.addView(containsButton)
        layout.addView(judgmentGroup)

        addLabel(getString(R.string.add_detail_area))
        val areaGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val fullscreenButton = android.widget.RadioButton(this).apply {
            text = getString(R.string.rec_area_fullscreen)
            isChecked = areaMode != AreaMode.REGION
        }
        val regionButton = android.widget.RadioButton(this).apply {
            text = getString(R.string.rec_area_region)
            isChecked = areaMode == AreaMode.REGION
        }
        areaGroup.addView(fullscreenButton)
        areaGroup.addView(regionButton)
        layout.addView(areaGroup)
        val regionStatus = TextView(this).apply {
            text = if (hasRegion) {
                getString(R.string.rec_region_picked)
            } else {
                getString(R.string.rec_region_not_picked)
            }
            setPadding(0, smallPad, 0, smallPad)
        }
        layout.addView(regionStatus)
        val pickRegionButton = Button(this).apply {
            text = getString(R.string.rec_pick_region)
        }
        layout.addView(pickRegionButton)

        addLabel(getString(R.string.add_detail_button_text))
        val textInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(editButtonText.text.toString())
        }
        layout.addView(textInput)
        layout.addView(
            TextView(this).apply {
                text = getString(R.string.rec_button_text_help)
                setPadding(0, smallPad, 0, smallPad)
            }
        )
        addLabel(getString(R.string.add_detail_button_id))
        val idInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(editButtonId.text.toString())
        }
        layout.addView(idInput)
        addLabel(getString(R.string.add_detail_button_desc))
        val descInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(editButtonDesc.text.toString())
        }
        layout.addView(descInput)

        fun applyDialogEdits() {
            matchMode =
                if (containsButton.isChecked) MatchMode.PARTIAL else MatchMode.EXACT
            areaMode =
                if (regionButton.isChecked) AreaMode.REGION else AreaMode.FULLSCREEN
            editButtonText.setText(textInput.text.toString())
            editButtonId.setText(idInput.text.toString())
            editButtonDesc.setText(descInput.text.toString())
            refreshJudgmentButton()
            refreshAreaViews()
            refreshButtonDetails()
            refreshSaveEnabled()
        }
        fun restorePreCapture() {
            matchMode = oldMatchMode
            areaMode = oldAreaMode
            regionLeft = oldLeft
            regionTop = oldTop
            regionRight = oldRight
            regionBottom = oldBottom
            editButtonText.setText(oldText)
            editButtonId.setText(oldId)
            editButtonDesc.setText(oldDesc)
            refreshJudgmentButton()
            refreshAreaViews()
            refreshButtonDetails()
            refreshSaveEnabled()
        }
        val scroll = android.widget.ScrollView(this)
        scroll.addView(layout)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.add_detail_title)
            .setView(scroll)
            .setPositiveButton(R.string.ok) { _, _ -> applyDialogEdits() }
            .setNegativeButton(R.string.cancel) { _, _ -> restorePreCapture() }
            .create()
        pickRegionButton.setOnClickListener {
            applyDialogEdits()
            dialog.dismiss()
            launchPickRegion("area")
        }
        dialog.show()
    }

    private fun refreshAnchorViews() {
        if (!::textAnchorStatus.isInitialized) return
        textAnchorStatus.text = if (anchorPicked) {
            getString(R.string.color_anchor_picked, anchorX, anchorY)
        } else {
            getString(R.string.color_anchor_none)
        }
    }

    /** Shows only the identification group for this step type. */
    private fun applyStepTypeVisibility() {
        val titleView = findViewById<TextView>(R.id.rec_title)
        titleView.text = when (stepType) {
            StepType.BUTTON_RECOGNITION -> getString(R.string.rec_title_button)
            StepType.IMAGE_RECOGNITION -> getString(R.string.rec_title_image)
            StepType.COLOR_RECOGNITION -> getString(R.string.rec_title_color)
            else -> getString(R.string.rec_title_ocr)
        }
        title = titleView.text
        findViewById<View>(R.id.group_button).visibility =
            if (stepType == StepType.BUTTON_RECOGNITION) View.VISIBLE else View.GONE
        findViewById<View>(R.id.group_image).visibility =
            if (stepType == StepType.IMAGE_RECOGNITION) View.VISIBLE else View.GONE
        findViewById<View>(R.id.group_color).visibility =
            if (stepType == StepType.COLOR_RECOGNITION) View.VISIBLE else View.GONE
        findViewById<View>(R.id.group_ocr).visibility =
            if (stepType == StepType.OCR_READ) View.VISIBLE else View.GONE
        // Judgment method only means something for Button and OCR text
        // matching; showing it for Image/Color would promise nothing.
        val judgmentVisible = stepType == StepType.BUTTON_RECOGNITION ||
            stepType == StepType.OCR_READ
        judgmentLabel.visibility = if (judgmentVisible) View.VISIBLE else View.GONE
        btnJudgment.visibility = if (judgmentVisible) View.VISIBLE else View.GONE
        btnJudgmentHelp.visibility = if (judgmentVisible) View.VISIBLE else View.GONE
    }

    private fun loadStateIntoViews() {
        editButtonText.setText(originalStep.matchText)
        editButtonId.setText(originalStep.buttonId)
        editButtonDesc.setText(originalStep.buttonDesc)
        // For OCR steps the step's match text lives in the OCR match
        // field (button steps use the button text field instead).
        if (stepType == StepType.OCR_READ) {
            editOcrMatch.setText(originalStep.matchText)
        }
        editOcrVariable.setText(
            originalStep.ocrVariable.ifBlank { "OCR_TEXT" }
        )

        // Inline values, same ranges the pickers had.
        stepCycles = stepCycles.coerceIn(1, 99)
        clickCountVal = originalStep.clickCount.coerceIn(1, 10)
        toleranceVal = originalStep.colorTolerance.coerceIn(0, 100)
        retryCountVal = originalStep.retryCount.coerceIn(0, 10)
        retryDelayMsVal = originalStep.retryDelayMs.coerceIn(0L, 30000L)
        refreshInlineValues()

        switchClickFirst.isChecked = originalStep.clickFirst
        checkBreakLoopSuccess.isChecked = originalStep.breakLoopOnSuccess
        checkBreakLoopFailure.isChecked = originalStep.breakLoopOnFailure

        // Initial radio states, then click listeners (tapping a card
        // or its radio selects that mode; see wireButtons for cards).
        radioSuccessClick.setOnClickListener { setSuccessMode(SuccessMode.CLICK) }
        radioSuccessCustom.setOnClickListener { setSuccessMode(SuccessMode.CUSTOM) }
        radioSuccessJump.setOnClickListener { setSuccessMode(SuccessMode.JUMP) }
        radioAfterIgnore.setOnClickListener { setAfterRetryMode(AfterRetryMode.IGNORE) }
        radioAfterCustom.setOnClickListener { setAfterRetryMode(AfterRetryMode.CUSTOM) }
        radioAfterJump.setOnClickListener { setAfterRetryMode(AfterRetryMode.JUMP) }
        setSuccessMode(successMode)
        setAfterRetryMode(afterRetryMode)
    }

    /** Mode-card exclusivity, replacing the old RadioGroups. */
    private fun setSuccessMode(mode: SuccessMode) {
        successMode = mode
        radioSuccessClick.isChecked = mode == SuccessMode.CLICK
        radioSuccessCustom.isChecked = mode == SuccessMode.CUSTOM
        radioSuccessJump.isChecked = mode == SuccessMode.JUMP
        refreshSuccessVisibility()
        refreshSaveEnabled()
    }

    private fun setAfterRetryMode(mode: AfterRetryMode) {
        afterRetryMode = mode
        radioAfterIgnore.isChecked = mode == AfterRetryMode.IGNORE
        radioAfterCustom.isChecked = mode == AfterRetryMode.CUSTOM
        radioAfterJump.isChecked = mode == AfterRetryMode.JUMP
        refreshAfterVisibility()
        refreshSaveEnabled()
    }

    private fun wireButtons() {
        btnJudgment.setOnClickListener { showJudgmentDialog() }
        btnJudgmentHelp.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.rec_judgment_label)
                .setMessage(R.string.rec_judgment_help)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
        btnArea.setOnClickListener { showAreaDialog() }
        findViewById<View>(R.id.btn_area_help).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.rec_area_label)
                .setMessage(R.string.rec_area_help)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
        // Button values: the text row keeps its pencil; the ID and
        // description rows have no pencil — tapping the row itself
        // opens the same small text edit dialog.
        findViewById<View>(R.id.btn_edit_button_text).setOnClickListener {
            showButtonValueEditDialog(editButtonText)
        }
        findViewById<View>(R.id.row_button_text).setOnClickListener {
            showButtonValueEditDialog(editButtonText)
        }
        findViewById<View>(R.id.row_button_id).setOnClickListener {
            showButtonValueEditDialog(editButtonId)
        }
        findViewById<View>(R.id.row_button_desc).setOnClickListener {
            showButtonValueEditDialog(editButtonDesc)
        }
        // Mode cards: tapping a card selects its mode.
        findViewById<View>(R.id.card_success_click).setOnClickListener {
            setSuccessMode(SuccessMode.CLICK)
        }
        findViewById<View>(R.id.card_success_custom).setOnClickListener {
            setSuccessMode(SuccessMode.CUSTOM)
        }
        findViewById<View>(R.id.card_success_jump).setOnClickListener {
            setSuccessMode(SuccessMode.JUMP)
        }
        findViewById<View>(R.id.card_after_ignore).setOnClickListener {
            setAfterRetryMode(AfterRetryMode.IGNORE)
        }
        findViewById<View>(R.id.card_after_custom).setOnClickListener {
            setAfterRetryMode(AfterRetryMode.CUSTOM)
        }
        findViewById<View>(R.id.card_after_jump).setOnClickListener {
            setAfterRetryMode(AfterRetryMode.JUMP)
        }
        // Perform-click count: tapping the inline "xN" badge edits it.
        textClickCountBadge.setOnClickListener { showClickCountDialog() }
        // Inline-value edit icons (validated dialogs, picker ranges).
        findViewById<View>(R.id.btn_edit_tolerance).setOnClickListener {
            showNumberEditDialog(
                R.string.rec_tolerance_label, toleranceVal, 0, 100
            ) { toleranceVal = it; refreshInlineValues() }
        }
        findViewById<View>(R.id.btn_edit_retry_count).setOnClickListener {
            showNumberEditDialog(
                R.string.rec_retry_count_label, retryCountVal, 0, 10
            ) { retryCountVal = it; refreshInlineValues() }
        }
        findViewById<View>(R.id.btn_edit_retry_delay).setOnClickListener {
            showNumberEditDialog(
                R.string.rec_retry_delay_label, retryDelayMsVal.toInt(), 0, 30000
            ) { retryDelayMsVal = it.toLong(); refreshInlineValues() }
        }
        findViewById<View>(R.id.btn_edit_cycles).setOnClickListener {
            showNumberEditDialog(
                R.string.rec_cycles_title, stepCycles, 1, 99
            ) { stepCycles = it; refreshInlineValues() }
        }
        findViewById<Button>(R.id.btn_capture_template).setOnClickListener {
            launchPickRegion("template")
        }
        findViewById<Button>(R.id.btn_pick_search_region).setOnClickListener {
            launchPickRegion("search")
        }
        findViewById<Button>(R.id.btn_pick_color).setOnClickListener {
            launchPickRegion("color")
        }
        btnPickAnchor.setOnClickListener {
            maybeShowAnchorGuideThenPick()
        }
        // Small "+" rows open the same full ADD menu as before.
        // (No separate pick-region button: OCR regions are picked
        // through the Recognition area pill in the top card.)
        findViewById<View>(R.id.row_add_success_action).setOnClickListener {
            openSubActionSheet("success")
        }
        findViewById<View>(R.id.row_add_retry_action).setOnClickListener {
            openSubActionSheet("retry")
        }
        findViewById<View>(R.id.row_add_after_action).setOnClickListener {
            openSubActionSheet("after")
        }
        findViewById<View>(R.id.btn_success_jump_target).setOnClickListener {
            showJumpDialog(isAfterRetry = false)
        }
        findViewById<View>(R.id.btn_after_jump_target).setOnClickListener {
            showJumpDialog(isAfterRetry = true)
        }
        findViewById<Button>(R.id.btn_rec_cancel).setOnClickListener {
            // CLOSE affordance: finish without saving, always.
            setResult(RESULT_CANCELED)
            finish()
        }
        btnSave.setOnClickListener { saveAndFinish() }
    }

    // ---------- Refresh ----------

    private fun refreshAll() {
        refreshJudgmentButton()
        refreshAreaViews()
        refreshButtonDetails()
        refreshTemplateViews()
        refreshColorViews()
        refreshAnchorViews()
        refreshSuccessVisibility()
        refreshAfterVisibility()
        refreshJumpStatuses()
        renderSubLists()
        refreshSaveEnabled()
    }

    /**
     * Judgment method, reference labels. The stored semantics are
     * unchanged: EXACT = the text must be exactly the same ("Same"),
     * PARTIAL = the text must contain the value ("Contains"). The
     * step default is EXACT, so a new step shows "Same".
     */
    private fun judgmentLabel(mode: MatchMode): String = when (mode) {
        MatchMode.EXACT -> getString(R.string.add_judgment_same)
        MatchMode.PARTIAL -> getString(R.string.add_judgment_contains)
    }

    private fun refreshJudgmentButton() {
        // Dropdown pill: value + arrow glyph.
        btnJudgment.text = judgmentLabel(matchMode) + " \u25BE"
    }

    private fun refreshAreaViews() {
        btnArea.text = when (areaMode) {
            AreaMode.FULLSCREEN -> getString(R.string.rec_area_fullscreen)
            AreaMode.REGION -> getString(R.string.rec_area_region)
        } + " \u25BE"
    }

    private fun refreshTemplateViews() {
        if (templatePath.isBlank()) {
            textTemplateStatus.text = getString(R.string.rec_template_none)
            imgTemplatePreview.visibility = View.GONE
            return
        }
        textTemplateStatus.text = getString(R.string.rec_template_saved)
        try {
            val bitmap = BitmapFactory.decodeFile(
                File(filesDir, templatePath).absolutePath
            )
            if (bitmap != null) {
                imgTemplatePreview.setImageBitmap(bitmap)
                imgTemplatePreview.visibility = View.VISIBLE
            } else {
                imgTemplatePreview.visibility = View.GONE
            }
        } catch (e: Exception) {
            imgTemplatePreview.visibility = View.GONE
        }
    }

    private fun refreshColorViews() {
        if (colorPicked) {
            textColorStatus.text = getString(R.string.rec_color_picked)
            viewColorSwatch.setBackgroundColor(colorValue)
        } else {
            textColorStatus.text = getString(R.string.rec_color_none)
            viewColorSwatch.setBackgroundColor(
                resources.getColor(R.color.divider, theme)
            )
        }
        refreshInlineValues()
    }

    /** Writes every inline numeric value to its page view. */
    private fun refreshInlineValues() {
        if (!::textToleranceValue.isInitialized) return
        textToleranceValue.text = toleranceVal.toString()
        textClickCountBadge.text = "x$clickCountVal"
        textRetryCountValue.text = retryCountVal.toString()
        textRetryDelayValue.text = retryDelayMsVal.toString()
        if (::textCyclesValue.isInitialized) {
            textCyclesValue.text = stepCycles.toString()
        }
    }

    private fun refreshSuccessVisibility() {
        // One shared list: Custom actions, and "actions before jumping".
        successActionsGroup.visibility =
            if (successMode == SuccessMode.CUSTOM || successMode == SuccessMode.JUMP) {
                View.VISIBLE
            } else {
                View.GONE
            }
        // The click-first switch shows in Custom mode and in Jump &
        // Continue mode (reference flow). The runner honours it in
        // both modes: tap first, then the custom actions / the jump
        // (see WorkflowRunner.executeRecognition).
        switchClickFirst.visibility =
            if (successMode == SuccessMode.CUSTOM ||
                successMode == SuccessMode.JUMP
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
        successJumpGroup.visibility =
            if (successMode == SuccessMode.JUMP) View.VISIBLE else View.GONE
    }

    private fun refreshAfterVisibility() {
        afterActionsGroup.visibility =
            if (afterRetryMode == AfterRetryMode.CUSTOM ||
                afterRetryMode == AfterRetryMode.JUMP
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
        afterJumpGroup.visibility =
            if (afterRetryMode == AfterRetryMode.JUMP) View.VISIBLE else View.GONE
    }

    private fun jumpLabel(index: Int): String {
        val step = jumpChoices.firstOrNull { it.first == index }?.second
        return if (step != null) {
            "Step ${index + 1}: ${displayName(step.type)}"
        } else {
            ""
        }
    }

    /**
     * Jump dropdown pills: the chosen step's label, or empty when
     * unset (reference-style empty state — no status line). With no
     * other step in the workflow the picker dialog itself shows the
     * hint when tapped.
     */
    private fun refreshJumpStatuses() {
        textSuccessJumpTarget.text =
            if (jumpTarget >= 0) jumpLabel(jumpTarget) else ""
        textAfterJumpTarget.text =
            if (afterRetryJumpTarget >= 0) jumpLabel(afterRetryJumpTarget) else ""
    }

    /**
     * Save stays enabled except while a Jump mode is selected with no
     * valid target to jump to — saving that would create a step the
     * runner cannot run. Switching back to another mode re-enables it.
     */
    private fun refreshSaveEnabled() {
        val jumpBlocked = (successMode == SuccessMode.JUMP && !hasJumpTargets) ||
            (afterRetryMode == AfterRetryMode.JUMP && !hasJumpTargets)
        btnSave.isEnabled = !jumpBlocked
    }

    // ---------- Region / point picking ----------

    private fun setRegion(left: Int, top: Int, right: Int, bottom: Int) {
        regionLeft = left
        regionTop = top
        regionRight = right
        regionBottom = bottom
    }

    private fun launchPickRegion(kind: String) {
        pendingRegionKind = kind
        // Button area picks: tapping "Pick" is itself the user choosing
        // a region, so a Fullscreen area becomes Region first. Without
        // this, a new Button step showed no pick button at all (the
        // button is only meaningful once a region is being picked).
        if (kind == "area" && areaMode != AreaMode.REGION) {
            areaMode = AreaMode.REGION
            refreshAreaViews()
        }
        val regionMode =
            if (kind == "template") "template" else if (kind == "color") "color" else "region"
        val requestId = WorkflowStore.newId()
        overlayRequestId = requestId
        if (!OverlayPickService.startRegion(
                this, requestId, regionMode, RecognitionEditActivity::class.java.name
            )
        ) {
            // Permission gate: KEEP the request so onResume() can resume
            // it automatically once the user grants the permission.
            // Clearing it here stranded the user on a dead pick button.
            pickAwaitingPermission = true
        } else {
            pickAwaitingPermission = false
        }
    }

    /** Sub-action Tap pick (shared by the auto-resume in onResume). */
    private fun launchSubPointPick(listKey: String, editIndex: Int = -1) {
        pendingSubList = listKey
        pendingSubEditIndex = editIndex
        val requestId = WorkflowStore.newId()
        overlayRequestId = requestId
        if (!OverlayPickService.startPoint(
                this, requestId, getString(R.string.pick_hint),
                RecognitionEditActivity::class.java.name
            )
        ) {
            pickAwaitingPermission = true
        } else {
            pickAwaitingPermission = false
        }
    }

    /** Sub-action Swipe pick (duration chosen in its dialog first). */
    private fun launchSubSwipePick(listKey: String, editIndex: Int, durationMs: Long) {
        pendingSubList = listKey
        pendingSubEditIndex = editIndex
        pendingSubSwipeDurationMs = durationMs
        val requestId = WorkflowStore.newId()
        overlayRequestId = requestId
        if (!OverlayPickService.startSwipe(
                this, requestId, RecognitionEditActivity::class.java.name
            )
        ) {
            pickAwaitingPermission = true
        } else {
            pickAwaitingPermission = false
        }
    }

    /** Sub-action AREA_CLICK region pick (mode chosen first). */
    private fun launchSubAreaPick(listKey: String, editIndex: Int, mode: String) {
        pendingSubList = listKey
        pendingSubEditIndex = editIndex
        pendingSubAreaMode = mode
        pendingRegionKind = "subArea"
        val requestId = WorkflowStore.newId()
        overlayRequestId = requestId
        if (!OverlayPickService.startRegion(
                this, requestId, "region", RecognitionEditActivity::class.java.name
            )
        ) {
            pickAwaitingPermission = true
        } else {
            pickAwaitingPermission = false
        }
    }

    /** Record-gesture row of the sub-action ADD menu: recorded steps append. */
    private fun launchSubRecord(listKey: String) {
        pendingSubList = listKey
        pendingSubRecord = true
        val requestId = WorkflowStore.newId()
        overlayRequestId = requestId
        if (!OverlayPickService.startRecord(
                this, requestId, RecognitionEditActivity::class.java.name
            )
        ) {
            pickAwaitingPermission = true
        } else {
            pickAwaitingPermission = false
        }
    }

    // ---------- Color anchor ----------

    /**
     * One-time "How to set up" guide, shown on the first anchor pick
     * unless "Never show again" was checked (persisted in the existing
     * SharedPreferences file). Cancel stops without picking anything;
     * Save starts the on-screen pick.
     */
    private fun maybeShowAnchorGuideThenPick() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ANCHOR_GUIDE_DISMISSED, false)) {
            launchAnchorPick()
            return
        }
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(
            TextView(this).apply {
                text = getString(R.string.color_anchor_guide_message)
            }
        )
        val neverAgain = CheckBox(this).apply {
            text = getString(R.string.color_anchor_never_show)
        }
        layout.addView(neverAgain)
        fun persistChoice() {
            if (neverAgain.isChecked) {
                prefs.edit().putBoolean(KEY_ANCHOR_GUIDE_DISMISSED, true).apply()
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.color_anchor_guide_title)
            .setView(layout)
            .setPositiveButton(R.string.rec_template_save) { _, _ ->
                persistChoice()
                launchAnchorPick()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                persistChoice()
            }
            .show()
    }

    /** Overlay point pick for the color anchor (not a sub-action). */
    private fun launchAnchorPick() {
        pendingAnchorPick = true
        val requestId = WorkflowStore.newId()
        overlayRequestId = requestId
        if (!OverlayPickService.startPoint(
                this, requestId, getString(R.string.pick_hint),
                RecognitionEditActivity::class.java.name
            )
        ) {
            pickAwaitingPermission = true
        } else {
            pickAwaitingPermission = false
        }
    }

    /**
     * Button capture (item 3): overlay over the real screen showing
     * every clickable button outlined in red. False = overlay
     * permission missing: the guide dialog is already shown by the
     * service launcher and nothing is captured. The captured button
     * (if any) arrives via ButtonCaptureStore in onResume(); this
     * method keeps no pending state of its own.
     */
    private fun launchButtonCapture() {
        ButtonCaptureService.startCapture(
            this, RecognitionEditActivity::class.java.name
        )
    }

    /**
     * Anchor point returned: read the live pixel via the accessibility
     * service and store the point + ARGB. Nothing is stored when the
     * service is unavailable or the pixel cannot be read — the step
     * must never pretend an anchor exists that was never measured.
     */
    private fun handleAnchorPoint(point: Point) {
        val service = AutomationService.instance
        if (service == null) {
            Toast.makeText(
                this, R.string.color_anchor_service_missing, Toast.LENGTH_LONG
            ).show()
            return
        }
        val argb = service.pickColorAt(point)
        if (argb == null) {
            Toast.makeText(
                this, R.string.color_anchor_failed, Toast.LENGTH_LONG
            ).show()
            return
        }
        anchorX = point.x
        anchorY = point.y
        colorValue = argb
        colorPicked = true
        refreshColorViews()
        refreshAnchorViews()
        refreshSaveEnabled()
    }

    // ---------- Image template preview ----------

    /**
     * Preview shown AFTER capture and BEFORE storing: Save makes the
     * capture this step's template; Discard deletes the temporary
     * capture file and keeps the previous template untouched.
     */
    private fun showTemplatePreviewDialog(
        path: String,
        bitmap: android.graphics.Bitmap
    ) {
        val preview = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rec_template_preview_title)
            .setView(preview)
            .setPositiveButton(R.string.rec_template_save) { _, _ ->
                templatePath = path
                refreshTemplateViews()
                refreshSaveEnabled()
            }
            .setNegativeButton(R.string.rec_template_discard) { _, _ ->
                try {
                    File(filesDir, path).delete()
                } catch (e: Exception) {
                    // Best-effort cleanup; the old template is untouched.
                }
            }
            .show()
    }

    // ---------- Dialogs ----------

    /**
     * Pencil edit for one button value row (text / ID / description).
     * OK writes the typed value into the row and refreshes the
     * captured-details summary; Cancel changes nothing.
     */
    private fun showButtonValueEditDialog(valueView: TextView) {
        val input = EditText(this).apply {
            setText(valueView.text.toString())
            inputType = InputType.TYPE_CLASS_TEXT
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rec_edit_value)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                valueView.text = input.text.toString()
                refreshButtonDetails()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Small validated number edit dialog, replacing the page's old
     * NumberPickers. Out-of-range or non-numeric input toasts the
     * allowed range and keeps the dialog open; Cancel changes
     * nothing. [onValid] runs only with a value inside [min]..[max].
     */
    private fun showNumberEditDialog(
        titleRes: Int,
        current: Int,
        min: Int,
        max: Int,
        onValid: (Int) -> Unit
    ) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(input)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().toIntOrNull()
            if (value == null || value < min || value > max) {
                Toast.makeText(
                    this, getString(R.string.rec_number_invalid, min, max),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            onValid(value)
            dialog.dismiss()
        }
    }

    /** Click count behind the Perform-click card's inline "xN" badge. */
    private fun showClickCountDialog() {
        showNumberEditDialog(
            R.string.rec_click_count_label, clickCountVal, 1, 10
        ) { clickCountVal = it; refreshInlineValues() }
    }

    private fun showJudgmentDialog() {
        val labels = arrayOf(
            getString(R.string.add_judgment_same),
            getString(R.string.add_judgment_contains)
        )
        var selected = if (matchMode == MatchMode.EXACT) 0 else 1
        AlertDialog.Builder(this)
            .setTitle(R.string.rec_judgment_dialog_title)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.ok) { _, _ ->
                matchMode = if (selected == 0) MatchMode.EXACT else MatchMode.PARTIAL
                refreshJudgmentButton()
                refreshButtonDetails()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAreaDialog() {
        val labels = arrayOf(
            getString(R.string.rec_area_fullscreen),
            getString(R.string.rec_area_region)
        )
        var selected = if (areaMode == AreaMode.REGION) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(R.string.rec_area_dialog_title)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.ok) { _, _ ->
                areaMode = if (selected == 1) AreaMode.REGION else AreaMode.FULLSCREEN
                refreshAreaViews()
                refreshButtonDetails()
                refreshSaveEnabled()
                // The area pill IS the region control now: choosing
                // Region launches the overlay pick at once (the old
                // full-width pick button is gone). Cancelling the
                // overlay leaves the picked region untouched; choosing
                // Region again re-picks. Fullscreen needs no pick.
                if (selected == 1) launchPickRegion("area")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Jump picker: existing steps only, by number and type. The step
     * being edited is already excluded from [jumpChoices]. With no other
     * step, the dialog shows the hint instead of an empty list.
     */
    private fun showJumpDialog(isAfterRetry: Boolean) {
        if (!hasJumpTargets) {
            AlertDialog.Builder(this)
                .setMessage(R.string.rec_jump_no_steps)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        val labels = jumpChoices.map { (index, step) ->
            "Step ${index + 1}: ${displayName(step.type)}"
        }.toTypedArray()
        val current = if (isAfterRetry) afterRetryJumpTarget else jumpTarget
        var selected = jumpChoices.indexOfFirst { it.first == current }
        // OK is wired after show(): with nothing selected it toasts
        // "Pick a step first" and keeps the dialog open instead of
        // silently dismissing with no change (QA Gate 3). A valid
        // current target still pre-selects, so this path is only
        // reachable when no target was previously set.
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rec_jump_target_dialog)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (selected < 0) {
                Toast.makeText(this, R.string.pick_step_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val target = jumpChoices[selected].first
            if (isAfterRetry) {
                afterRetryJumpTarget = target
            } else {
                jumpTarget = target
            }
            refreshJumpStatuses()
            refreshSaveEnabled()
            dialog.dismiss()
        }
    }

    // ---------- Sub-action lists ----------

    private fun subActionList(listKey: String): MutableList<Step> = when (listKey) {
        "retry" -> retryActions
        "after" -> afterActions
        else -> successActions
    }

    private fun addSubAction(listKey: String, step: Step) {
        subActionList(listKey).add(step)
        renderSubLists()
    }

    /**
     * Replace at [index] when editing an existing sub-action, else
     * append. Every sub-action dialog and overlay return goes through
     * here, so a cancelled configuration adds nothing.
     */
    private fun putSubAction(listKey: String, step: Step, index: Int) {
        val list = subActionList(listKey)
        if (index in list.indices) {
            list[index] = step
        } else {
            list.add(step)
        }
        renderSubLists()
    }

    private fun renderSubLists() {
        renderSubList(listSuccessActions, textSuccessEmpty, successActions, "success")
        renderSubList(listRetryActions, textRetryEmpty, retryActions, "retry")
        renderSubList(listAfterActions, textAfterEmpty, afterActions, "after")
    }

    private fun renderSubList(
        container: LinearLayout,
        emptyView: TextView,
        list: MutableList<Step>,
        listKey: String
    ) {
        container.removeAllViews()
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for ((index, step) in list.withIndex()) {
            val row = inflater.inflate(R.layout.item_sub_action_row, container, false)
            row.findViewById<TextView>(R.id.sub_action_title).text =
                subActionSummary(step)
            row.findViewById<ImageView>(R.id.sub_action_icon)
                .setImageResource(subActionIcon(step.type))
            // Tapping a row (or its list icon) reopens its
            // configuration dialog — the row's picker.
            val editThis = {
                if (index in list.indices) {
                    editSubAction(listKey, index)
                }
            }
            row.setOnClickListener { editThis() }
            row.findViewById<View>(R.id.sub_action_list).setOnClickListener {
                editThis()
            }
            row.findViewById<View>(R.id.sub_action_delete).setOnClickListener {
                if (index in list.indices) {
                    list.removeAt(index)
                    renderSubLists()
                }
            }
            container.addView(row)
        }
    }

    /** Round row icon per sub-action type (same icons as the editor). */
    private fun subActionIcon(type: StepType): Int = when (type) {
        StepType.TAP -> R.drawable.ic_step_tap
        StepType.SWIPE -> R.drawable.ic_step_swipe
        StepType.TYPE_TEXT -> R.drawable.ic_step_typetext
        StepType.WAIT -> R.drawable.ic_step_wait
        StepType.NAVIGATION -> R.drawable.ic_step_nav
        StepType.LAUNCH_APP -> R.drawable.ic_step_launch
        StepType.SUB_TASK -> R.drawable.ic_step_subtask
        StepType.TYPE_CODE -> R.drawable.ic_step_code
        StepType.TYPE_EXCEL_ROW -> R.drawable.ic_step_excel
        StepType.BUTTON_RECOGNITION -> R.drawable.ic_step_button
        StepType.IMAGE_RECOGNITION -> R.drawable.ic_step_image
        StepType.COLOR_RECOGNITION -> R.drawable.ic_step_color
        StepType.OCR_READ -> R.drawable.ic_step_ocr
        StepType.SCREENSHOT -> R.drawable.ic_step_screenshot
        StepType.PASTE -> R.drawable.ic_step_paste
        StepType.VIBRATION -> R.drawable.ic_step_vibration
        StepType.SOUND -> R.drawable.ic_step_sound
        StepType.SCREEN_OFF -> R.drawable.ic_step_screenoff
        StepType.AREA_CLICK -> R.drawable.ic_step_area
        StepType.WAIT_RANDOM -> R.drawable.ic_step_waitrandom
        StepType.STOP_TASK -> R.drawable.ic_step_stop
        StepType.INTENT_URI -> R.drawable.ic_step_intent
        StepType.NETWORK_GET -> R.drawable.ic_step_network
        StepType.SLIDE_LIST -> R.drawable.ic_step_slide
        StepType.QS_TILE -> R.drawable.ic_step_qs
        StepType.POWER_DIALOG -> R.drawable.ic_step_power
        StepType.LOOP_ACTION -> R.drawable.ic_step_loop
    }

    private fun subActionSummary(step: Step): String = when (step.type) {
        StepType.TAP -> getString(R.string.rec_action_tap)
        StepType.TYPE_TEXT -> {
            val text = step.text.take(40)
            getString(R.string.rec_action_type_text) + ": " + text
        }
        StepType.WAIT -> {
            getString(R.string.rec_action_wait) + ": " + step.durationMs + " ms"
        }
        else -> displayName(step.type)
    }

    /**
     * Custom-action ADD: the SAME full grouped AddStepBottomSheet as
     * the main editor — all groups, all step types, including nested
     * recognition steps — not the old Tap/Type text/Wait-only menu.
     * The runner's depth guard (top level + 2 nested levels, then
     * "Recognition nesting too deep") prevents infinite nesting.
     * Sub-action dialogs deliberately offer NO cycles picker: the
     * runner repeats only top-level steps, so a cycles value stored
     * on a sub-action would never run and would be fake UI.
     */
    private fun openSubActionSheet(listKey: String) {
        val sheet = AddStepBottomSheet()
        sheet.onStepChosenListener =
            AddStepBottomSheet.OnStepChosenListener { type ->
                showSubActionConfig(type, listKey, null, -1)
            }
        sheet.onRecordRequested = { launchSubRecord(listKey) }
        sheet.show(supportFragmentManager, "add_sub_action")
    }

    /** Row tap: reopen that sub-action's configuration dialog. */
    private fun editSubAction(listKey: String, index: Int) {
        val step = subActionList(listKey).getOrNull(index) ?: return
        if (step.type == StepType.TAP) {
            launchSubPointPick(listKey, index)
        } else {
            showSubActionConfig(step.type, listKey, step, index)
        }
    }

    /**
     * Generic sub-action configuration dialog, covering every type.
     * Point/region picks go through the overlay (never typed);
     * no-config types are added immediately; cancelling any dialog
     * adds nothing (the caller only puts the step on OK / pick return).
     */
    private fun showSubActionConfig(
        type: StepType,
        listKey: String,
        existing: Step?,
        index: Int
    ) {
        when (type) {
            StepType.TAP -> launchSubPointPick(listKey, index)
            StepType.SWIPE -> showSubSwipeDialog(listKey, existing, index)
            StepType.TYPE_TEXT -> showTypeTextSubDialog(listKey, existing, index)
            StepType.WAIT -> showWaitSubDialog(listKey, existing, index)
            StepType.NAVIGATION -> showSubNavDialog(listKey, existing, index)
            StepType.LAUNCH_APP -> showSubLaunchAppDialog(listKey, existing, index)
            StepType.SUB_TASK -> showSubTaskDialog(listKey, existing, index)
            StepType.TYPE_CODE -> showSubSnippetDialog(listKey, existing, index)
            StepType.TYPE_EXCEL_ROW -> showSubMistakesDialog(listKey, existing, index)
            StepType.BUTTON_RECOGNITION,
            StepType.IMAGE_RECOGNITION,
            StepType.COLOR_RECOGNITION,
            StepType.OCR_READ -> launchSubRecognition(type, listKey, existing, index)
            StepType.PASTE -> showSubPasteDialog(listKey, existing, index)
            StepType.AREA_CLICK -> showSubAreaDialog(listKey, existing, index)
            StepType.WAIT_RANDOM -> showSubWaitRandomDialog(listKey, existing, index)
            StepType.INTENT_URI -> showSubUriDialog(listKey, existing, index, false)
            StepType.NETWORK_GET -> showSubUriDialog(listKey, existing, index, true)
            StepType.SLIDE_LIST -> showSubSlideDialog(listKey, existing, index)
            StepType.QS_TILE -> showSubQsDialog(listKey, existing, index)
            StepType.LOOP_ACTION -> showSubLoopDialog(listKey, existing, index)
            StepType.SCREENSHOT,
            StepType.VIBRATION,
            StepType.SOUND,
            StepType.SCREEN_OFF,
            StepType.STOP_TASK,
            StepType.POWER_DIALOG -> {
                if (existing == null) {
                    putSubAction(
                        listKey,
                        Step(
                            id = WorkflowStore.newId(),
                            type = type,
                            waitAfterMs = WAIT_AFTER_MS
                        ),
                        index
                    )
                } else {
                    Toast.makeText(
                        this, R.string.step_no_settings, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /** Nested recognition editor for a sub-action (full editor, same activity). */
    private fun launchSubRecognition(
        type: StepType,
        listKey: String,
        existing: Step?,
        index: Int
    ) {
        subRecogListKey = listKey
        subRecogEditIndex = index
        val intent = Intent(this, RecognitionEditActivity::class.java)
        intent.putExtra("step_type", type.name)
        if (existing != null) {
            intent.putExtra("step_json", WorkflowStore.stepToJson(existing).toString())
        }
        // No jump targets inside a sub-action list (the runner ignores
        // jumps there), so the nested editor gets an empty list and its
        // Jump modes stay safely unusable.
        intent.putExtra("workflow_steps_json", "[]")
        // Loop targets ARE forwarded, unlike jumps: a loop sub-action
        // resolves against the top-level list at run time, so the
        // nested editor needs that list. The enclosing steps are
        // forwarded as exclusions (this editor's own step plus any
        // exclusion it inherited), so a loop can never target an
        // enclosing recognition step.
        val loopStepsJson = JSONArray()
        val loopSource = if (loopChoices.isNotEmpty()) loopChoices else jumpChoices
        loopSource.sortedBy { it.first }.forEach { (_, step) ->
            loopStepsJson.put(WorkflowStore.stepToJson(step))
        }
        intent.putExtra("loop_steps_json", loopStepsJson.toString())
        val exclude = (loopExcludeIds + originalStep.id).joinToString(",")
        intent.putExtra("loop_exclude_ids", exclude)
        subRecogLauncher.launch(intent)
    }

    /**
     * Loop-action sub-action (reference-workflow clone): pick an
     * existing top-level step from [loopTargetChoices] and set the
     * loop times (1..99, default 2 for a new loop). No targets means
     * a message, never an empty picker. Cancel adds nothing; a stored
     * target that no longer exists pre-selects nothing, so OK without
     * a fresh pick saves nothing (a dangling target is never written
     * back). The runner resolves the target by id at run time.
     */
    private fun showSubLoopDialog(listKey: String, existing: Step?, index: Int) {
        val candidates = loopTargetChoices()
        if (candidates.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage(R.string.loop_no_steps)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val targetGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        candidates.forEach { (stepIndex, step) ->
            targetGroup.addView(
                android.widget.RadioButton(this).apply {
                    text = "Step ${stepIndex + 1}: ${displayName(step.type)}"
                    isChecked = step.id == existing?.loopTargetId
                    tag = step.id
                }
            )
        }
        layout.addView(targetGroup)
        layout.addView(
            TextView(this).apply {
                text = getString(R.string.loop_times_title)
                setPadding(0, pad, 0, 0)
            }
        )
        val timesPicker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 99
            value = (existing?.loopCount ?: 2).coerceIn(1, 99)
        }
        layout.addView(timesPicker)
        // OK is wired after show(): with no target selected it toasts
        // "Pick a step first" and keeps the dialog open instead of
        // silently dismissing with no change (QA Gate 3). Selection
        // behaviour is unchanged, in both add and replace modes.
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.step_loop)
            .setView(layout)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val checkedId = targetGroup.checkedRadioButtonId
            if (checkedId < 0) {
                Toast.makeText(this, R.string.pick_step_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val targetId = targetGroup.findViewById<android.widget.RadioButton>(checkedId)
                ?.tag as? String ?: run {
                    Toast.makeText(this, R.string.pick_step_first, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            val old = subActionList(listKey).getOrNull(index)
            putSubAction(
                listKey,
                (old ?: Step(
                    id = WorkflowStore.newId(),
                    type = StepType.LOOP_ACTION,
                    waitAfterMs = WAIT_AFTER_MS
                )).copy(
                    type = StepType.LOOP_ACTION,
                    loopTargetId = targetId,
                    loopCount = timesPicker.value
                ),
                index
            )
            dialog.dismiss()
        }
    }

    private fun showSubSwipeDialog(listKey: String, existing: Step?, index: Int) {
        val picker = NumberPicker(this).apply {
            minValue = 0
            maxValue = MAX_PICKER_100MS
            value = ((existing?.durationMs ?: 500L) / 100).toInt()
                .coerceIn(0, MAX_PICKER_100MS)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rec_type_swipe)
            .setView(picker)
            .setPositiveButton(R.string.ok) { _, _ ->
                launchSubSwipePick(listKey, index, picker.value * 100L)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSubNavDialog(listKey: String, existing: Step?, index: Int) {
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
            .setTitle(R.string.rec_type_navigation)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.ok) { _, _ ->
                if (selected < 0) return@setPositiveButton
                val navKey = when (selected) {
                    0 -> NavKey.BACK
                    1 -> NavKey.HOME
                    else -> NavKey.RECENTS
                }
                val old = subActionList(listKey).getOrNull(index)
                putSubAction(
                    listKey,
                    (old ?: Step(
                        id = WorkflowStore.newId(),
                        type = StepType.NAVIGATION,
                        waitAfterMs = WAIT_AFTER_MS
                    )).copy(type = StepType.NAVIGATION, navKey = navKey),
                    index
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSubLaunchAppDialog(listKey: String, existing: Step?, index: Int) {
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
                val old = subActionList(listKey).getOrNull(index)
                putSubAction(
                    listKey,
                    (old ?: Step(
                        id = WorkflowStore.newId(),
                        type = StepType.LAUNCH_APP,
                        waitAfterMs = WAIT_AFTER_MS
                    )).copy(type = StepType.LAUNCH_APP, packageName = packageName),
                    index
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSubTaskDialog(listKey: String, existing: Step?, index: Int) {
        val others = WorkflowStore.loadActive(this)
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
                val old = subActionList(listKey).getOrNull(index)
                putSubAction(
                    listKey,
                    (old ?: Step(
                        id = WorkflowStore.newId(),
                        type = StepType.SUB_TASK,
                        waitAfterMs = WAIT_AFTER_MS
                    )).copy(type = StepType.SUB_TASK, subWorkflowId = subWorkflowId),
                    index
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSubSnippetDialog(listKey: String, existing: Step?, index: Int) {
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
                val old = subActionList(listKey).getOrNull(index)
                putSubAction(
                    listKey,
                    (old ?: Step(
                        id = WorkflowStore.newId(),
                        type = StepType.TYPE_CODE,
                        waitAfterMs = WAIT_AFTER_MS
                    )).copy(type = StepType.TYPE_CODE, snippetId = snippetId),
                    index
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSubMistakesDialog(listKey: String, existing: Step?, index: Int) {
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
                val old = subActionList(listKey).getOrNull(index)
                putSubAction(
                    listKey,
                    (old ?: Step(
                        id = WorkflowStore.newId(),
                        type = StepType.TYPE_EXCEL_ROW,
                        waitAfterMs = WAIT_AFTER_MS
                    )).copy(type = StepType.TYPE_EXCEL_ROW, mistakeLevel = level),
                    index
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Paste sub-action: Clipboard / Typed text / Library, like the main editor. */
    private fun showSubPasteDialog(listKey: String, existing: Step?, index: Int) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val hasLibrary = (existing?.dataSourceId ?: "").isNotBlank()
        val hasTyped = !hasLibrary && (existing?.text ?: "").isNotBlank()
        val sourceGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
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
        val files = DataFileStore.loadAll(this)
        val fileSpinner = android.widget.Spinner(this)
        val fileLabels = mutableListOf(getString(R.string.type_data_none))
        fileLabels.addAll(files.map { it.name })
        fileSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, fileLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        layout.addView(fileSpinner)
        val columnSpinner = android.widget.Spinner(this)
        layout.addView(columnSpinner)
        fun refreshColumns() {
            val pos = fileSpinner.selectedItemPosition
            val file = if (pos > 0) files[pos - 1] else null
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
            if (file != null && file.id == existing?.dataSourceId &&
                (existing?.dataColumn ?: "") in columns
            ) {
                columnSpinner.setSelection(columns.indexOf(existing?.dataColumn ?: ""))
            }
        }
        fileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                refreshColumns()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val existingIndex = files.indexOfFirst { it.id == existing?.dataSourceId }
        if (existingIndex >= 0) {
            fileSpinner.setSelection(existingIndex + 1)
        }
        refreshColumns()
        fun refreshEnabled() {
            val library = libraryButton.isChecked
            fileSpinner.isEnabled = library
            columnSpinner.isEnabled = library
            typedInput.isEnabled = typedButton.isChecked
        }
        sourceGroup.setOnCheckedChangeListener { _, _ -> refreshEnabled() }
        refreshEnabled()
        AlertDialog.Builder(this)
            .setTitle(R.string.paste_dialog_title)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val useLibrary = libraryButton.isChecked
                val useTyped = typedButton.isChecked
                val pos = fileSpinner.selectedItemPosition
                val dataSourceId = if (useLibrary && pos > 0) files[pos - 1].id else ""
                val dataColumn = if (useLibrary) {
                    columnSpinner.selectedItem as? String ?: ""
                } else {
                    ""
                }
                val text = if (useTyped) typedInput.text.toString() else ""
                val old = subActionList(listKey).getOrNull(index)
                putSubAction(
                    listKey,
                    (old ?: Step(
                        id = WorkflowStore.newId(),
                        type = StepType.PASTE,
                        waitAfterMs = WAIT_AFTER_MS
                    )).copy(
                        type = StepType.PASTE,
                        text = text,
                        dataSourceId = dataSourceId,
                        dataColumn = dataColumn
                    ),
                    index
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSubAreaDialog(listKey: String, existing: Step?, index: Int) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(
            TextView(this).apply {
                text = getString(R.string.area_mode_title)
            }
        )
        val modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
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
        AlertDialog.Builder(this)
            .setTitle(R.string.step_area_click)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val checkedId = modeGroup.checkedRadioButtonId
                val mode = if (checkedId >= 0) {
                    modeGroup.findViewById<android.widget.RadioButton>(checkedId)
                        ?.tag as? String ?: "RANDOM"
                } else {
                    "RANDOM"
                }
                launchSubAreaPick(listKey, index, mode)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Sub-action WAIT_RANDOM, same reference content as the main
     * editor: "Minimum value" + number + "Seconds", "Maximum value"
     * + number + "Seconds", defaults 1 / 3.
     *
     * Seconds display truncates stored milliseconds (1500 ms shows
     * as 1 s), so an unchanged value must NOT be rewritten as
     * seconds * 1000 on OK — that would silently corrupt the stored
     * value (1500 -> 1000 ms). A bound whose entered seconds equal
     * its displayed seconds keeps its exact stored milliseconds;
     * only a changed bound is stored as whole seconds.
     */
    private fun showSubWaitRandomDialog(listKey: String, existing: Step?, index: Int) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun secondsRow(labelRes: Int, initialSeconds: Int): EditText {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(
                TextView(this).apply {
                    text = getString(labelRes)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
            )
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                gravity = android.view.Gravity.CENTER
                setText(initialSeconds.toString())
                minWidth = (48 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(input)
            row.addView(
                TextView(this).apply {
                    text = getString(R.string.wait_seconds)
                    setPadding((8 * density).toInt(), 0, 0, 0)
                }
            )
            layout.addView(row)
            return input
        }
        val initialMinSec =
            ((existing?.waitMinMs ?: 1000) / 1000).toInt().coerceIn(0, 3600)
        val initialMaxSec =
            ((existing?.waitMaxMs ?: 3000) / 1000).toInt().coerceIn(0, 3600)
        val minInput = secondsRow(R.string.wait_min_label, initialMinSec)
        val maxInput = secondsRow(R.string.wait_max_label, initialMaxSec)
        AlertDialog.Builder(this)
            .setTitle(R.string.step_wait_random)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val minSec = minInput.text.toString().toIntOrNull()
                    ?.coerceIn(0, 3600) ?: 1
                val maxSecRaw = maxInput.text.toString().toIntOrNull()
                    ?.coerceIn(0, 3600) ?: 3
                // Unchanged bounds keep their exact stored ms (see the
                // KDoc above); changed bounds become whole seconds.
                val minMs = if (existing != null && minSec == initialMinSec) {
                    existing.waitMinMs
                } else {
                    minSec * 1000L
                }
                var maxMs = if (existing != null && maxSecRaw == initialMaxSec) {
                    existing.waitMaxMs
                } else {
                    maxSecRaw * 1000L
                }
                // A raised minimum must not leave max below min: the
                // runner coerces anyway, but the stored pair stays valid.
                if (maxMs < minMs) maxMs = minMs
                val old = subActionList(listKey).getOrNull(index)
                putSubAction(
                    listKey,
                    (old ?: Step(
                        id = WorkflowStore.newId(),
                        type = StepType.WAIT_RANDOM,
                        waitAfterMs = WAIT_AFTER_MS
                    )).copy(
                        type = StepType.WAIT_RANDOM,
                        waitMinMs = minMs, waitMaxMs = maxMs
                    ),
                    index
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSubUriDialog(
        listKey: String,
        existing: Step?,
        index: Int,
        isNetwork: Boolean
    ) {
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
                val old = subActionList(listKey).getOrNull(index)
                putSubAction(
                    listKey,
                    (old ?: Step(
                        id = WorkflowStore.newId(),
                        type = if (isNetwork) StepType.NETWORK_GET else StepType.INTENT_URI,
                        waitAfterMs = WAIT_AFTER_MS
                    )).copy(
                        type = if (isNetwork) StepType.NETWORK_GET else StepType.INTENT_URI,
                        networkUrl = if (isNetwork) value else "",
                        intentUri = if (isNetwork) "" else value
                    ),
                    index
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSubSlideDialog(listKey: String, existing: Step?, index: Int) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(
            TextView(this).apply {
                text = getString(R.string.slide_direction_title)
            }
        )
        val directionGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
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
            TextView(this).apply {
                text = getString(R.string.slide_count_title)
            }
        )
        val countPicker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 20
            value = (existing?.slideCount ?: 1).coerceIn(1, 20)
        }
        layout.addView(countPicker)
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
                val old = subActionList(listKey).getOrNull(index)
                putSubAction(
                    listKey,
                    (old ?: Step(
                        id = WorkflowStore.newId(),
                        type = StepType.SLIDE_LIST,
                        waitAfterMs = WAIT_AFTER_MS
                    )).copy(
                        type = StepType.SLIDE_LIST,
                        slideDirection = direction,
                        slideCount = countPicker.value
                    ),
                    index
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSubQsDialog(listKey: String, existing: Step?, index: Int) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val tileGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        TaskEditorActivity.QS_TILE_LABELS.forEach { label ->
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
            TextView(this).apply {
                text = getString(R.string.qs_tile_note)
            }
        )
        val scroll = android.widget.ScrollView(this)
        scroll.addView(layout)
        AlertDialog.Builder(this)
            .setTitle(R.string.qs_tile_title)
            .setView(scroll)
            .setPositiveButton(R.string.ok) { _, _ ->
                val checkedId = tileGroup.checkedRadioButtonId
                if (checkedId < 0) return@setPositiveButton
                val label = tileGroup.findViewById<android.widget.RadioButton>(checkedId)
                    ?.tag as? String ?: return@setPositiveButton
                val old = subActionList(listKey).getOrNull(index)
                putSubAction(
                    listKey,
                    (old ?: Step(
                        id = WorkflowStore.newId(),
                        type = StepType.QS_TILE,
                        waitAfterMs = WAIT_AFTER_MS
                    )).copy(type = StepType.QS_TILE, qsTileLabel = label),
                    index
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Type text content is text, not a location, so an EditText is fine.
     * Also exposes wait-after and typing-mistakes (the runner already
     * applies mistakeLevel for TYPE_TEXT); defaults keep old behaviour:
     * wait 2000 ms, mistakes Off. */
    private fun showTypeTextSubDialog(
        listKey: String,
        existing: Step? = null,
        index: Int = -1
    ) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.text ?: "")
        }
        layout.addView(input)
        val waitLabel = TextView(this).apply {
            text = getString(R.string.type_wait_after_title)
        }
        layout.addView(waitLabel)
        val waitPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = MAX_PICKER_100MS
            value = ((existing?.waitAfterMs ?: WAIT_AFTER_MS) / 100).toInt()
                .coerceIn(0, MAX_PICKER_100MS)
        }
        layout.addView(waitPicker)
        val mistakesLabel = TextView(this).apply {
            text = getString(R.string.type_mistakes_title)
        }
        layout.addView(mistakesLabel)
        val mistakesGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
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
                    isChecked = level == (existing?.mistakeLevel ?: MistakeLevel.OFF)
                    tag = level
                }
            )
        }
        layout.addView(mistakesGroup)
        AlertDialog.Builder(this)
            .setTitle(R.string.rec_type_text_title)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val checkedId = mistakesGroup.checkedRadioButtonId
                val level = if (checkedId >= 0) {
                    (mistakesGroup.findViewById<android.widget.RadioButton>(checkedId)?.tag
                        as? MistakeLevel) ?: MistakeLevel.OFF
                } else {
                    MistakeLevel.OFF
                }
                val old = subActionList(listKey).getOrNull(index)
                putSubAction(
                    listKey,
                    (old ?: Step(
                        id = WorkflowStore.newId(),
                        type = StepType.TYPE_TEXT,
                        waitAfterMs = WAIT_AFTER_MS
                    )).copy(
                        type = StepType.TYPE_TEXT,
                        text = input.text.toString(),
                        waitAfterMs = waitPicker.value * 100L,
                        mistakeLevel = level
                    ),
                    index
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showWaitSubDialog(
        listKey: String,
        existing: Step? = null,
        index: Int = -1
    ) {
        val picker = NumberPicker(this).apply {
            minValue = 0
            maxValue = MAX_PICKER_100MS
            value = ((existing?.durationMs ?: 1000L) / 100).toInt()
                .coerceIn(0, MAX_PICKER_100MS)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rec_wait_title)
            .setView(picker)
            .setPositiveButton(R.string.ok) { _, _ ->
                val old = subActionList(listKey).getOrNull(index)
                putSubAction(
                    listKey,
                    (old ?: Step(
                        id = WorkflowStore.newId(),
                        type = StepType.WAIT,
                        waitAfterMs = WAIT_AFTER_MS
                    )).copy(
                        type = StepType.WAIT,
                        durationMs = picker.value * 100L
                    ),
                    index
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- Save ----------

    private fun saveAndFinish() {
        val buttonText = editButtonText.text.toString().trim()
        val buttonId = editButtonId.text.toString().trim()
        val buttonDesc = editButtonDesc.text.toString().trim()
        val ocrMatchText = editOcrMatch.text.toString().trim()
        val ocrVariable = editOcrVariable.text.toString().trim()
        // OCR match text is the step's match text; every other type
        // keeps its match text from the button text field (blank for
        // Image / Color steps, which do not match on text).
        val matchTextToSave =
            if (stepType == StepType.OCR_READ) ocrMatchText else buttonText

        when (stepType) {
            StepType.BUTTON_RECOGNITION -> {
                if (buttonText.isEmpty() && buttonId.isEmpty() && buttonDesc.isEmpty()) {
                    Toast.makeText(this, R.string.rec_error_button_empty, Toast.LENGTH_LONG).show()
                    return
                }
            }
            StepType.IMAGE_RECOGNITION -> {
                if (templatePath.isBlank()) {
                    Toast.makeText(this, R.string.rec_error_no_template, Toast.LENGTH_LONG).show()
                    return
                }
            }
            StepType.COLOR_RECOGNITION -> {
                // An anchor counts as picked: the anchor branch needs
                // no region at all (it checks one live pixel).
                if (!colorPicked || (!hasRegion && !anchorPicked)) {
                    Toast.makeText(this, R.string.rec_error_no_color, Toast.LENGTH_LONG).show()
                    return
                }
            }
            else -> {
                // OCR_READ: nothing mandatory.
            }
        }
        if (areaMode == AreaMode.REGION && !hasRegion) {
            Toast.makeText(this, R.string.rec_error_no_region, Toast.LENGTH_LONG).show()
            return
        }
        if (successMode == SuccessMode.JUMP) {
            if (!hasJumpTargets) {
                Toast.makeText(this, R.string.rec_error_jump_no_steps, Toast.LENGTH_LONG).show()
                return
            }
            if (jumpTarget !in jumpChoices.map { it.first }) {
                Toast.makeText(this, R.string.rec_error_no_jump_target, Toast.LENGTH_LONG).show()
                return
            }
        }
        if (afterRetryMode == AfterRetryMode.JUMP) {
            if (!hasJumpTargets) {
                Toast.makeText(this, R.string.rec_error_jump_no_steps, Toast.LENGTH_LONG).show()
                return
            }
            if (afterRetryJumpTarget !in jumpChoices.map { it.first }) {
                Toast.makeText(this, R.string.rec_error_no_jump_target, Toast.LENGTH_LONG).show()
                return
            }
        }

        val built = originalStep.copy(
            type = stepType,
            matchText = matchTextToSave,
            matchMode = matchMode,
            buttonId = buttonId,
            buttonDesc = buttonDesc,
            templatePath = templatePath,
            areaMode = areaMode,
            regionLeft = if (hasRegion) regionLeft else -1,
            regionTop = if (hasRegion) regionTop else -1,
            regionRight = if (hasRegion) regionRight else -1,
            regionBottom = if (hasRegion) regionBottom else -1,
            colorValue = colorValue,
            colorTolerance = toleranceVal,
            anchorX = anchorX,
            anchorY = anchorY,
            stepCycles = stepCycles,
            ocrVariable = ocrVariable.ifBlank { "OCR_TEXT" },
            successMode = successMode,
            clickCount = clickCountVal,
            customSteps = successActions.toList(),
            clickFirst = switchClickFirst.isChecked,
            jumpTarget = if (successMode == SuccessMode.JUMP) jumpTarget else -1,
            retryCount = retryCountVal,
            retryDelayMs = retryDelayMsVal,
            retrySteps = retryActions.toList(),
            afterRetryMode = afterRetryMode,
            afterRetrySteps = afterActions.toList(),
            afterRetryJumpTarget =
                if (afterRetryMode == AfterRetryMode.JUMP) afterRetryJumpTarget else -1,
            breakLoopOnSuccess = checkBreakLoopSuccess.isChecked,
            breakLoopOnFailure = checkBreakLoopFailure.isChecked
        )
        val result = Intent().apply {
            putExtra("step_json", WorkflowStore.stepToJson(built).toString())
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun displayName(type: StepType): String = when (type) {
        StepType.TAP -> getString(R.string.rec_action_tap)
        StepType.SWIPE -> getString(R.string.rec_type_swipe)
        StepType.TYPE_TEXT -> getString(R.string.rec_action_type_text)
        StepType.WAIT -> getString(R.string.rec_action_wait)
        StepType.NAVIGATION -> getString(R.string.rec_type_navigation)
        StepType.LAUNCH_APP -> getString(R.string.rec_type_launch_app)
        StepType.SUB_TASK -> getString(R.string.rec_type_sub_task)
        StepType.TYPE_CODE -> getString(R.string.rec_type_code)
        StepType.TYPE_EXCEL_ROW -> getString(R.string.rec_type_excel)
        StepType.BUTTON_RECOGNITION -> getString(R.string.rec_title_button)
        StepType.IMAGE_RECOGNITION -> getString(R.string.rec_title_image)
        StepType.COLOR_RECOGNITION -> getString(R.string.rec_title_color)
        StepType.OCR_READ -> getString(R.string.rec_title_ocr)
        StepType.SCREENSHOT -> getString(R.string.rec_type_screenshot)
        StepType.PASTE -> getString(R.string.rec_type_paste)
        StepType.VIBRATION -> getString(R.string.rec_type_vibration)
        StepType.SOUND -> getString(R.string.rec_type_sound)
        StepType.SCREEN_OFF -> getString(R.string.rec_type_screen_off)
        StepType.AREA_CLICK -> getString(R.string.rec_type_area_click)
        StepType.WAIT_RANDOM -> getString(R.string.rec_type_wait_random)
        StepType.STOP_TASK -> getString(R.string.rec_type_stop_task)
        StepType.INTENT_URI -> getString(R.string.rec_type_intent)
        StepType.NETWORK_GET -> getString(R.string.rec_type_network)
        StepType.SLIDE_LIST -> getString(R.string.step_slide_list)
        StepType.QS_TILE -> getString(R.string.step_qs_tile)
        StepType.POWER_DIALOG -> getString(R.string.step_power_dialog)
        StepType.LOOP_ACTION -> getString(R.string.step_loop)
    }
}
