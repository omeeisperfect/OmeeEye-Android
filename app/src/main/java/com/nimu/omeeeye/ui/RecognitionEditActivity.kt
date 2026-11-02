package com.nimu.omeeeye.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.WorkflowStore
import com.nimu.omeeeye.model.AfterRetryMode
import com.nimu.omeeeye.model.AreaMode
import com.nimu.omeeeye.model.MatchMode
import com.nimu.omeeeye.model.MistakeLevel
import com.nimu.omeeeye.model.Point
import com.nimu.omeeeye.model.Step
import com.nimu.omeeeye.model.StepType
import com.nimu.omeeeye.model.SuccessMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Editor for ONE recognition step (Button / Image / Color / OCR), in
 * this app's own UI identity. Sections in order: Identification, actions
 * after successful recognition, retry, actions after the retry, then
 * Save / Close.
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
class RecognitionEditActivity : AppCompatActivity() {

    companion object {
        private const val WAIT_AFTER_MS = 2000L
        private const val MAX_PICKER_100MS = 300 // 300 x 100 ms = 30,000 ms
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
    private lateinit var btnJudgment: Button
    private lateinit var judgmentLabel: TextView
    private lateinit var btnArea: Button
    private lateinit var btnPickRegion: Button
    private lateinit var textRegionStatus: TextView
    private lateinit var editButtonText: EditText
    private lateinit var editButtonId: EditText
    private lateinit var editButtonDesc: EditText
    private lateinit var imgTemplatePreview: ImageView
    private lateinit var textTemplateStatus: TextView
    private lateinit var textImageRegionStatus: TextView
    private lateinit var viewColorSwatch: View
    private lateinit var textColorStatus: TextView
    private lateinit var pickerTolerance: NumberPicker
    private lateinit var textToleranceValue: TextView
    private lateinit var textOcrRegionStatus: TextView
    private lateinit var editOcrMatch: EditText
    private lateinit var editOcrVariable: EditText
    private lateinit var radioSuccessGroup: RadioGroup
    private lateinit var successClickGroup: View
    private lateinit var pickerClickCount: NumberPicker
    private lateinit var textClickCountValue: TextView
    private lateinit var successActionsGroup: View
    private lateinit var switchClickFirst: Switch
    private lateinit var listSuccessActions: LinearLayout
    private lateinit var textSuccessEmpty: TextView
    private lateinit var successJumpGroup: View
    private lateinit var textSuccessJumpStatus: TextView
    private lateinit var listRetryActions: LinearLayout
    private lateinit var textRetryEmpty: TextView
    private lateinit var pickerRetryCount: NumberPicker
    private lateinit var textRetryCountValue: TextView
    private lateinit var pickerRetryDelay: NumberPicker
    private lateinit var textRetryDelayValue: TextView
    private lateinit var radioAfterGroup: RadioGroup
    private lateinit var afterActionsGroup: View
    private lateinit var listAfterActions: LinearLayout
    private lateinit var textAfterEmpty: TextView
    private lateinit var afterJumpGroup: View
    private lateinit var textAfterJumpStatus: TextView
    private lateinit var btnSave: Button

    // ---- Overlay pick state ----
    // Which on-screen pick is in flight for the overlay:
    // "area" (the section-1 region), "search" (image search region),
    // "ocr" (OCR region), "template", "color".
    private var pendingRegionKind: String? = null
    // Which sub-action list an overlay tap belongs to:
    // "success", "retry" or "after".
    private var pendingSubList: String? = null
    private var overlayRequestId: String? = null
    // A pick the user asked for but the overlay permission blocked:
    // the request is kept (not dropped) so that returning from system
    // settings with the permission granted resumes it automatically
    // instead of stranding the user on a dead pick button.
    private var pickAwaitingPermission: Boolean = false

    override fun onResume() {
        super.onResume()
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
            if (kind != null) {
                launchPickRegion(kind)
            } else if (subList != null) {
                launchSubPointPick(subList)
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
            return
        }
        when (obj.optString("kind")) {
            "region" -> {
                val kind = pendingRegionKind
                pendingRegionKind = null
                if (kind == null) return
                val left = obj.optInt("left", -1)
                val top = obj.optInt("top", -1)
                val right = obj.optInt("right", -1)
                val bottom = obj.optInt("bottom", -1)
                when (kind) {
                    "template" -> {
                        val path = obj.optString("templatePath", "")
                        if (path.isNotBlank()) {
                            templatePath = path
                            refreshTemplateViews()
                        }
                    }
                    "color" -> {
                        if (left >= 0 && top >= 0 && right > left && bottom > top) {
                            setRegion(left, top, right, bottom)
                            colorValue = obj.optInt("colorValue", 0)
                            colorPicked = true
                            refreshColorViews()
                            refreshRegionStatuses()
                        }
                    }
                    else -> {
                        // "area", "search", "ocr": a picked region, searched
                        // in REGION mode from now on.
                        if (left >= 0 && top >= 0 && right > left && bottom > top) {
                            setRegion(left, top, right, bottom)
                            areaMode = AreaMode.REGION
                            refreshAreaViews()
                            refreshRegionStatuses()
                        }
                    }
                }
                refreshSaveEnabled()
            }
            "point" -> {
                val listKey = pendingSubList
                pendingSubList = null
                if (listKey == null) return
                val point = Point(obj.optInt("x"), obj.optInt("y"))
                val step = Step(
                    id = WorkflowStore.newId(),
                    type = StepType.TAP,
                    point = point,
                    waitAfterMs = WAIT_AFTER_MS
                )
                addSubAction(listKey, step)
            }
        }
    }

    // ---------- Lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
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
        }

        loadInputs()
        bindViews()
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
        // A color step that already has a picked region was picked
        // on screen before; treat its area as picked.
        colorPicked = stepType == StepType.COLOR_RECOGNITION && hasRegion
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
    }

    private fun bindViews() {
        btnJudgment = findViewById(R.id.btn_judgment)
        judgmentLabel = findViewById(R.id.rec_judgment_label)
        btnArea = findViewById(R.id.btn_area)
        btnPickRegion = findViewById(R.id.btn_pick_region)
        textRegionStatus = findViewById(R.id.text_region_status)
        editButtonText = findViewById(R.id.edit_button_text)
        editButtonId = findViewById(R.id.edit_button_id)
        editButtonDesc = findViewById(R.id.edit_button_desc)
        imgTemplatePreview = findViewById(R.id.img_template_preview)
        textTemplateStatus = findViewById(R.id.text_template_status)
        textImageRegionStatus = findViewById(R.id.text_image_region_status)
        viewColorSwatch = findViewById(R.id.view_color_swatch)
        textColorStatus = findViewById(R.id.text_color_status)
        pickerTolerance = findViewById(R.id.picker_tolerance)
        textToleranceValue = findViewById(R.id.text_tolerance_value)
        textOcrRegionStatus = findViewById(R.id.text_ocr_region_status)
        editOcrMatch = findViewById(R.id.edit_ocr_match)
        editOcrVariable = findViewById(R.id.edit_ocr_variable)
        radioSuccessGroup = findViewById(R.id.radio_success_group)
        successClickGroup = findViewById(R.id.success_click_group)
        pickerClickCount = findViewById(R.id.picker_click_count)
        textClickCountValue = findViewById(R.id.text_click_count_value)
        successActionsGroup = findViewById(R.id.success_actions_group)
        switchClickFirst = findViewById(R.id.switch_click_first)
        listSuccessActions = findViewById(R.id.list_success_actions)
        textSuccessEmpty = findViewById(R.id.text_success_empty)
        successJumpGroup = findViewById(R.id.success_jump_group)
        textSuccessJumpStatus = findViewById(R.id.text_success_jump_status)
        listRetryActions = findViewById(R.id.list_retry_actions)
        textRetryEmpty = findViewById(R.id.text_retry_empty)
        pickerRetryCount = findViewById(R.id.picker_retry_count)
        textRetryCountValue = findViewById(R.id.text_retry_count_value)
        pickerRetryDelay = findViewById(R.id.picker_retry_delay)
        textRetryDelayValue = findViewById(R.id.text_retry_delay_value)
        radioAfterGroup = findViewById(R.id.radio_after_group)
        afterActionsGroup = findViewById(R.id.after_actions_group)
        listAfterActions = findViewById(R.id.list_after_actions)
        textAfterEmpty = findViewById(R.id.text_after_empty)
        afterJumpGroup = findViewById(R.id.after_jump_group)
        textAfterJumpStatus = findViewById(R.id.text_after_jump_status)
        btnSave = findViewById(R.id.btn_rec_save)
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

        pickerClickCount.minValue = 1
        pickerClickCount.maxValue = 10
        pickerClickCount.value = originalStep.clickCount.coerceIn(1, 10)
        pickerTolerance.minValue = 0
        pickerTolerance.maxValue = 100
        pickerTolerance.value = originalStep.colorTolerance.coerceIn(0, 100)
        pickerRetryCount.minValue = 0
        pickerRetryCount.maxValue = 10
        pickerRetryCount.value = originalStep.retryCount.coerceIn(0, 10)
        pickerRetryDelay.minValue = 0
        pickerRetryDelay.maxValue = MAX_PICKER_100MS
        pickerRetryDelay.value =
            (originalStep.retryDelayMs / 100).toInt().coerceIn(0, MAX_PICKER_100MS)

        pickerClickCount.setOnValueChangedListener { _, _, newVal ->
            textClickCountValue.text = newVal.toString()
        }
        pickerTolerance.setOnValueChangedListener { _, _, newVal ->
            textToleranceValue.text = newVal.toString()
        }
        pickerRetryCount.setOnValueChangedListener { _, _, newVal ->
            textRetryCountValue.text = newVal.toString()
        }
        pickerRetryDelay.setOnValueChangedListener { _, _, newVal ->
            textRetryDelayValue.text = (newVal * 100).toString() + " ms"
        }

        switchClickFirst.isChecked = originalStep.clickFirst

        when (successMode) {
            SuccessMode.CLICK -> radioSuccessGroup.check(R.id.radio_success_click)
            SuccessMode.CUSTOM -> radioSuccessGroup.check(R.id.radio_success_custom)
            SuccessMode.JUMP -> radioSuccessGroup.check(R.id.radio_success_jump)
        }
        when (afterRetryMode) {
            AfterRetryMode.IGNORE -> radioAfterGroup.check(R.id.radio_after_ignore)
            AfterRetryMode.CUSTOM -> radioAfterGroup.check(R.id.radio_after_custom)
            AfterRetryMode.JUMP -> radioAfterGroup.check(R.id.radio_after_jump)
        }
        radioSuccessGroup.setOnCheckedChangeListener { _, checkedId ->
            successMode = when (checkedId) {
                R.id.radio_success_custom -> SuccessMode.CUSTOM
                R.id.radio_success_jump -> SuccessMode.JUMP
                else -> SuccessMode.CLICK
            }
            refreshSuccessVisibility()
            refreshSaveEnabled()
        }
        radioAfterGroup.setOnCheckedChangeListener { _, checkedId ->
            afterRetryMode = when (checkedId) {
                R.id.radio_after_custom -> AfterRetryMode.CUSTOM
                R.id.radio_after_jump -> AfterRetryMode.JUMP
                else -> AfterRetryMode.IGNORE
            }
            refreshAfterVisibility()
            refreshSaveEnabled()
        }
    }

    private fun wireButtons() {
        btnJudgment.setOnClickListener { showJudgmentDialog() }
        btnArea.setOnClickListener { showAreaDialog() }
        btnPickRegion.setOnClickListener { launchPickRegion("area") }
        findViewById<Button>(R.id.btn_capture_template).setOnClickListener {
            launchPickRegion("template")
        }
        findViewById<Button>(R.id.btn_pick_search_region).setOnClickListener {
            launchPickRegion("search")
        }
        findViewById<Button>(R.id.btn_pick_color).setOnClickListener {
            launchPickRegion("color")
        }
        findViewById<Button>(R.id.btn_pick_ocr_region).setOnClickListener {
            launchPickRegion("ocr")
        }
        findViewById<Button>(R.id.btn_add_success_action).setOnClickListener {
            showAddSubActionDialog("success")
        }
        findViewById<Button>(R.id.btn_add_retry_action).setOnClickListener {
            showAddSubActionDialog("retry")
        }
        findViewById<Button>(R.id.btn_add_after_action).setOnClickListener {
            showAddSubActionDialog("after")
        }
        findViewById<Button>(R.id.btn_success_jump_target).setOnClickListener {
            showJumpDialog(isAfterRetry = false)
        }
        findViewById<Button>(R.id.btn_after_jump_target).setOnClickListener {
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
        refreshRegionStatuses()
        refreshTemplateViews()
        refreshColorViews()
        refreshSuccessVisibility()
        refreshAfterVisibility()
        refreshJumpStatuses()
        renderSubLists()
        refreshSaveEnabled()
    }

    private fun refreshJudgmentButton() {
        btnJudgment.text = when (matchMode) {
            MatchMode.EXACT -> getString(R.string.rec_judgment_exact)
            MatchMode.PARTIAL -> getString(R.string.rec_judgment_partial)
        }
    }

    private fun refreshAreaViews() {
        btnArea.text = when (areaMode) {
            AreaMode.FULLSCREEN -> getString(R.string.rec_area_fullscreen)
            AreaMode.REGION -> getString(R.string.rec_area_region)
        }
        // The on-screen pick button is always visible: tapping it while
        // the area is Fullscreen switches to Region first (see
        // launchPickRegion). Hiding it left new Button steps with no
        // pick control at all, which read as "no screen access".
        btnPickRegion.visibility = View.VISIBLE
    }

    /** Region state is shown, never as editable data: picked / not picked. */
    private fun refreshRegionStatuses() {
        val text = if (hasRegion) {
            getString(R.string.rec_region_picked)
        } else {
            getString(R.string.rec_region_not_picked)
        }
        textRegionStatus.text = text
        textImageRegionStatus.text = text
        textOcrRegionStatus.text = text
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
        textToleranceValue.text = pickerTolerance.value.toString()
        textClickCountValue.text = pickerClickCount.value.toString()
        textRetryCountValue.text = pickerRetryCount.value.toString()
        textRetryDelayValue.text = (pickerRetryDelay.value * 100).toString() + " ms"
    }

    private fun refreshSuccessVisibility() {
        successClickGroup.visibility =
            if (successMode == SuccessMode.CLICK) View.VISIBLE else View.GONE
        // One shared list: Custom actions, and "actions before jumping".
        successActionsGroup.visibility =
            if (successMode == SuccessMode.CUSTOM || successMode == SuccessMode.JUMP) {
                View.VISIBLE
            } else {
                View.GONE
            }
        // The click-first switch only applies to Custom mode.
        switchClickFirst.visibility =
            if (successMode == SuccessMode.CUSTOM) View.VISIBLE else View.GONE
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
            getString(R.string.rec_jump_none)
        }
    }

    private fun refreshJumpStatuses() {
        textSuccessJumpStatus.text = if (!hasJumpTargets) {
            getString(R.string.rec_jump_no_steps)
        } else if (jumpTarget >= 0) {
            jumpLabel(jumpTarget)
        } else {
            getString(R.string.rec_jump_none)
        }
        textAfterJumpStatus.text = if (!hasJumpTargets) {
            getString(R.string.rec_jump_no_steps)
        } else if (afterRetryJumpTarget >= 0) {
            jumpLabel(afterRetryJumpTarget)
        } else {
            getString(R.string.rec_jump_none)
        }
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
    private fun launchSubPointPick(listKey: String) {
        pendingSubList = listKey
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

    // ---------- Dialogs ----------

    private fun showJudgmentDialog() {
        val labels = arrayOf(
            getString(R.string.rec_judgment_exact),
            getString(R.string.rec_judgment_partial)
        )
        var selected = if (matchMode == MatchMode.EXACT) 0 else 1
        AlertDialog.Builder(this)
            .setTitle(R.string.rec_judgment_dialog_title)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.ok) { _, _ ->
                matchMode = if (selected == 0) MatchMode.EXACT else MatchMode.PARTIAL
                refreshJudgmentButton()
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
                refreshSaveEnabled()
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
        AlertDialog.Builder(this)
            .setTitle(R.string.rec_jump_target_dialog)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.ok) { _, _ ->
                if (selected < 0) return@setPositiveButton
                val target = jumpChoices[selected].first
                if (isAfterRetry) {
                    afterRetryJumpTarget = target
                } else {
                    jumpTarget = target
                }
                refreshJumpStatuses()
                refreshSaveEnabled()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
            row.findViewById<Button>(R.id.sub_action_delete).setOnClickListener {
                if (index in list.indices) {
                    list.removeAt(index)
                    renderSubLists()
                }
            }
            container.addView(row)
        }
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

    private fun showAddSubActionDialog(listKey: String) {
        val labels = arrayOf(
            getString(R.string.rec_action_tap),
            getString(R.string.rec_action_type_text),
            getString(R.string.rec_action_wait)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.rec_add_action_title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> launchSubPointPick(listKey)
                    1 -> showTypeTextSubDialog(listKey)
                    else -> showWaitSubDialog(listKey)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Type text content is text, not a location, so an EditText is fine.
     * Also exposes wait-after and typing-mistakes (the runner already
     * applies mistakeLevel for TYPE_TEXT); defaults keep old behaviour:
     * wait 2000 ms, mistakes Off. */
    private fun showTypeTextSubDialog(listKey: String) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        layout.addView(input)
        val waitLabel = TextView(this).apply {
            text = getString(R.string.type_wait_after_title)
        }
        layout.addView(waitLabel)
        val waitPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = MAX_PICKER_100MS
            value = (WAIT_AFTER_MS / 100).toInt().coerceIn(0, MAX_PICKER_100MS)
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
                    isChecked = level == MistakeLevel.OFF
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
                addSubAction(
                    listKey,
                    Step(
                        id = WorkflowStore.newId(),
                        type = StepType.TYPE_TEXT,
                        text = input.text.toString(),
                        waitAfterMs = waitPicker.value * 100L,
                        mistakeLevel = level
                    )
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showWaitSubDialog(listKey: String) {
        val picker = NumberPicker(this).apply {
            minValue = 0
            maxValue = MAX_PICKER_100MS
            value = 10
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rec_wait_title)
            .setView(picker)
            .setPositiveButton(R.string.ok) { _, _ ->
                addSubAction(
                    listKey,
                    Step(
                        id = WorkflowStore.newId(),
                        type = StepType.WAIT,
                        durationMs = picker.value * 100L,
                        waitAfterMs = WAIT_AFTER_MS
                    )
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
                if (!colorPicked || !hasRegion) {
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
            colorTolerance = pickerTolerance.value,
            ocrVariable = ocrVariable.ifBlank { "OCR_TEXT" },
            successMode = successMode,
            clickCount = pickerClickCount.value,
            customSteps = successActions.toList(),
            clickFirst = switchClickFirst.isChecked,
            jumpTarget = if (successMode == SuccessMode.JUMP) jumpTarget else -1,
            retryCount = pickerRetryCount.value,
            retryDelayMs = pickerRetryDelay.value * 100L,
            retrySteps = retryActions.toList(),
            afterRetryMode = afterRetryMode,
            afterRetrySteps = afterActions.toList(),
            afterRetryJumpTarget =
                if (afterRetryMode == AfterRetryMode.JUMP) afterRetryJumpTarget else -1
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
    }
}
