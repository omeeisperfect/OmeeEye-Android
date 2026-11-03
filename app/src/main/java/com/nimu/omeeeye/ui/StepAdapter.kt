package com.nimu.omeeeye.ui

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.nimu.omeeeye.R
import com.nimu.omeeeye.model.AfterRetryMode
import com.nimu.omeeeye.model.NavKey
import com.nimu.omeeeye.model.Step
import com.nimu.omeeeye.model.StepType
import com.nimu.omeeeye.model.SuccessMode
import java.util.Collections

/**
 * One row per step in the task editor, reference structure. The adapter
 * owns no workflow data of its own: it renders the [steps] list instance
 * handed in by TaskEditorActivity, so moves/copies/deletes/swaps made
 * through the listener (or [swapWithNext]) are immediately visible to
 * the activity (same list object) and are persisted when it saves.
 *
 * First line: number, gesture icon, title, real duration (hidden when
 * the step has none — never a fake number), edit pencil, eye preview,
 * always-shown count badge, drag handle. Second row: copy, swap with
 * the next step, delete, single-step play, action menu. Recognition
 * rows add green success / red failure pills on the card; tapping a
 * pill opens the step editor, which holds that section's picker.
 * The Wait line sits BELOW the card; tapping it edits wait-after.
 *
 * Drag reorder is started ONLY from the drag handle (btn_drag): the
 * activity wires [Listener.onStartDrag] to its ItemTouchHelper, and long-press
 * drag is disabled there.
 */
class StepAdapter(
    private val steps: MutableList<Step>,
    private val listener: Listener
) : RecyclerView.Adapter<StepAdapter.ViewHolder>() {

    interface Listener {
        fun onStepClicked(position: Int)
        fun onCopyStep(position: Int)
        fun onDeleteStep(position: Int)
        fun onPlayStep(position: Int)
        fun onStepMenu(position: Int, anchor: View)
        fun onWaitClicked(position: Int)
        fun onStartDrag(holder: RecyclerView.ViewHolder)
        /** Display name of a sub-task workflow, or null if it no longer exists. */
        fun subTaskName(subWorkflowId: String): String?
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val stepNumber: TextView = view.findViewById(R.id.step_number)
        val stepIcon: ImageView = view.findViewById(R.id.step_icon)
        val stepTitle: TextView = view.findViewById(R.id.step_title)
        val stepDuration: TextView = view.findViewById(R.id.step_duration)
        val btnEdit: ImageButton = view.findViewById(R.id.btn_edit)
        val btnPreview: ImageButton = view.findViewById(R.id.btn_preview)
        val stepCycles: TextView = view.findViewById(R.id.step_cycles)
        val btnCopy: ImageButton = view.findViewById(R.id.btn_copy)
        val btnSwap: ImageButton = view.findViewById(R.id.btn_swap)
        val btnDrag: ImageButton = view.findViewById(R.id.btn_drag)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
        val btnStepPlay: ImageButton = view.findViewById(R.id.btn_step_play)
        val btnMenu: ImageButton = view.findViewById(R.id.btn_step_menu)
        val branchPills: View = view.findViewById(R.id.branch_pills)
        val pillSuccess: TextView = view.findViewById(R.id.pill_success)
        val pillFailure: TextView = view.findViewById(R.id.pill_failure)
        val waitRow: View = view.findViewById(R.id.wait_row)
        val stepWait: TextView = view.findViewById(R.id.step_wait)
        val btnWaitEdit: ImageButton = view.findViewById(R.id.btn_wait_edit)
    }

    /** Called by the activity's ItemTouchHelper when a drag moves a row. */
    fun move(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        if (fromPosition !in steps.indices || toPosition !in steps.indices) return
        Collections.swap(steps, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
    }

    /**
     * Swap button: exchanges this step with the NEXT step only (one
     * position down). The last step has no next step, so its swap
     * button does nothing. Same-list rule as drag: the activity saves
     * the new order when SAVE is tapped.
     */
    fun swapWithNext(position: Int) {
        if (position !in steps.indices || position + 1 !in steps.indices) return
        Collections.swap(steps, position, position + 1)
        notifyItemMoved(position, position + 1)
        // Numbers change on both rows after a swap; rebind both so the
        // "1." / "2." labels always match the new order.
        notifyItemChanged(position)
        notifyItemChanged(position + 1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_step, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val step = steps[position]
        val context = holder.itemView.context

        holder.stepNumber.text = "${position + 1}."
        holder.stepIcon.setImageResource(iconFor(step.type))
        holder.stepTitle.text = titleFor(context, step)
        // Real duration only: steps with no duration show nothing.
        if (step.durationMs > 0) {
            holder.stepDuration.visibility = View.VISIBLE
            holder.stepDuration.text =
                context.getString(R.string.step_duration_format, step.durationMs)
        } else {
            holder.stepDuration.visibility = View.GONE
        }
        // Count badge: always shown; the runner clamps 1..99, so a
        // stored value below 1 reads as the default 1 here too.
        holder.stepCycles.text = context.getString(
            R.string.step_cycles_format, step.stepCycles.coerceAtLeast(1)
        )
        holder.stepWait.text =
            context.getString(R.string.step_wait_after_format, step.waitAfterMs)

        if (isRecognition(step.type)) {
            holder.branchPills.visibility = View.VISIBLE
            holder.pillSuccess.text = successLabel(context, step) + " \u25BE"
            holder.pillFailure.text = failureLabel(context, step) + " \u25BE"
        } else {
            holder.branchPills.visibility = View.GONE
        }

        val openEditor = {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) listener.onStepClicked(pos)
        }
        holder.itemView.setOnClickListener { openEditor() }
        holder.btnEdit.setOnClickListener { openEditor() }
        // Branch pills open the step editor, which holds that
        // section's success / failure picker.
        holder.pillSuccess.setOnClickListener { openEditor() }
        holder.pillFailure.setOnClickListener { openEditor() }
        holder.btnPreview.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                showPreviewDialog(holder.itemView.context, steps[pos])
            }
        }
        val editWait = {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) listener.onWaitClicked(pos)
        }
        holder.waitRow.setOnClickListener { editWait() }
        holder.stepWait.setOnClickListener { editWait() }
        holder.btnWaitEdit.setOnClickListener { editWait() }
        holder.btnCopy.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) listener.onCopyStep(pos)
        }
        holder.btnSwap.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) swapWithNext(pos)
        }
        holder.btnDelete.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) listener.onDeleteStep(pos)
        }
        holder.btnStepPlay.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) listener.onPlayStep(pos)
        }
        holder.btnMenu.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) listener.onStepMenu(pos, holder.btnMenu)
        }
        // Drag starts only from this handle, never from a long-press on the row.
        holder.btnDrag.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                listener.onStartDrag(holder)
            }
            false
        }
    }

    override fun getItemCount(): Int = steps.size

    private fun isRecognition(type: StepType): Boolean = type == StepType.BUTTON_RECOGNITION ||
        type == StepType.IMAGE_RECOGNITION ||
        type == StepType.COLOR_RECOGNITION ||
        type == StepType.OCR_READ

    private fun successLabel(context: android.content.Context, step: Step): String =
        when (step.successMode) {
            SuccessMode.CLICK -> context.getString(R.string.branch_success_click)
            SuccessMode.CUSTOM -> context.getString(R.string.branch_success_custom)
            SuccessMode.JUMP -> context.getString(R.string.branch_success_jump)
        }

    private fun failureLabel(context: android.content.Context, step: Step): String =
        when (step.afterRetryMode) {
            AfterRetryMode.IGNORE -> context.getString(R.string.branch_failure_ignore)
            AfterRetryMode.CUSTOM -> context.getString(R.string.branch_failure_custom)
            AfterRetryMode.JUMP -> context.getString(R.string.branch_failure_jump)
        }

    /**
     * Read-only summary of one step: title as the dialog title, real
     * field values as lines. Fields the step does not have are omitted;
     * nothing is invented. The dialog has only OK — it never edits.
     */
    private fun showPreviewDialog(context: android.content.Context, step: Step) {
        val lines = mutableListOf<String>()
        if (step.durationMs > 0) {
            lines.add(context.getString(R.string.step_preview_duration, step.durationMs))
        }
        lines.add(context.getString(R.string.step_preview_wait, step.waitAfterMs))
        lines.add(
            context.getString(
                R.string.step_preview_cycles, step.stepCycles.coerceAtLeast(1)
            )
        )
        when (step.type) {
            StepType.TAP -> step.point?.let {
                lines.add(
                    context.getString(R.string.step_preview_point, it.x, it.y)
                )
            }
            StepType.SWIPE -> if (step.start != null && step.end != null) {
                lines.add(
                    context.getString(
                        R.string.step_preview_swipe,
                        step.start.x, step.start.y, step.end.x, step.end.y
                    )
                )
            }
            StepType.TYPE_TEXT -> if (step.text.isNotBlank()) {
                lines.add(
                    context.getString(
                        R.string.step_preview_text, step.text.take(80)
                    )
                )
            }
            StepType.NAVIGATION -> step.navKey?.let {
                val label = when (it) {
                    NavKey.BACK -> context.getString(R.string.nav_back)
                    NavKey.HOME -> context.getString(R.string.nav_home)
                    NavKey.RECENTS -> context.getString(R.string.nav_recents)
                }
                lines.add(context.getString(R.string.step_preview_key, label))
            }
            StepType.LAUNCH_APP -> if (step.packageName.isNotBlank()) {
                lines.add(
                    context.getString(R.string.step_preview_package, step.packageName)
                )
            }
            StepType.SUB_TASK -> {
                val name = listener.subTaskName(step.subWorkflowId)
                lines.add(
                    if (name.isNullOrBlank()) {
                        context.getString(R.string.step_sub_task)
                    } else {
                        "Sub-task: $name"
                    }
                )
            }
            StepType.WAIT_RANDOM -> lines.add(
                context.getString(
                    R.string.step_preview_wait_random, step.waitMinMs, step.waitMaxMs
                )
            )
            StepType.BUTTON_RECOGNITION, StepType.IMAGE_RECOGNITION,
            StepType.COLOR_RECOGNITION, StepType.OCR_READ -> {
                if (step.matchText.isNotBlank()) {
                    lines.add(
                        context.getString(R.string.step_preview_match, step.matchText)
                    )
                }
                if (step.type == StepType.OCR_READ) {
                    lines.add(
                        context.getString(
                            R.string.step_preview_variable, step.ocrVariable
                        )
                    )
                }
                lines.add(
                    context.getString(
                        R.string.step_preview_success, successLabel(context, step)
                    )
                )
                lines.add(
                    context.getString(
                        R.string.step_preview_failure, failureLabel(context, step)
                    )
                )
            }
            else -> Unit
        }
        AlertDialog.Builder(context)
            .setTitle(titleFor(context, step))
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun iconFor(type: StepType): Int = when (type) {
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
    }

    private fun titleFor(context: android.content.Context, step: Step): String {
        // The count badge now carries per-step cycles, so the title no
        // longer appends an "xN" suffix (it used to, before the badge).
        return when (step.type) {
            StepType.TAP -> context.getString(R.string.step_tap)
            StepType.SWIPE -> context.getString(R.string.step_swipe)
            StepType.TYPE_TEXT -> context.getString(R.string.step_type_text)
            StepType.WAIT -> context.getString(R.string.step_wait)
            StepType.NAVIGATION -> {
                val label = when (step.navKey) {
                    NavKey.BACK -> context.getString(R.string.nav_back)
                    NavKey.HOME -> context.getString(R.string.nav_home)
                    NavKey.RECENTS -> context.getString(R.string.nav_recents)
                    null -> context.getString(R.string.step_navigation)
                }
                if (step.navKey == null) label else "$label key"
            }
            StepType.LAUNCH_APP -> context.getString(R.string.step_launch_app)
            StepType.SUB_TASK -> {
                val name = listener.subTaskName(step.subWorkflowId)
                if (name.isNullOrBlank()) {
                    context.getString(R.string.step_sub_task)
                } else {
                    "Sub-task: $name"
                }
            }
            StepType.TYPE_CODE -> context.getString(R.string.step_type_code)
            StepType.TYPE_EXCEL_ROW -> context.getString(R.string.step_type_excel_row)
            StepType.BUTTON_RECOGNITION -> context.getString(R.string.step_button_recognition)
            StepType.IMAGE_RECOGNITION -> context.getString(R.string.step_image_recognition)
            StepType.COLOR_RECOGNITION -> context.getString(R.string.step_color_recognition)
            StepType.OCR_READ -> context.getString(R.string.step_ocr_read)
            StepType.SCREENSHOT -> context.getString(R.string.step_screenshot)
            StepType.PASTE -> context.getString(R.string.step_paste)
            StepType.VIBRATION -> context.getString(R.string.step_vibration)
            StepType.SOUND -> context.getString(R.string.step_sound)
            StepType.SCREEN_OFF -> context.getString(R.string.step_screen_off)
            StepType.AREA_CLICK -> context.getString(R.string.step_area_click)
            StepType.WAIT_RANDOM -> context.getString(R.string.step_wait_random)
            StepType.STOP_TASK -> context.getString(R.string.step_stop_task)
            StepType.INTENT_URI -> context.getString(R.string.step_intent_uri)
            StepType.NETWORK_GET -> context.getString(R.string.step_network_get)
            StepType.SLIDE_LIST -> context.getString(R.string.step_slide_list)
            StepType.QS_TILE -> context.getString(R.string.step_qs_tile)
            StepType.POWER_DIALOG -> context.getString(R.string.step_power_dialog)
        }
    }
}
