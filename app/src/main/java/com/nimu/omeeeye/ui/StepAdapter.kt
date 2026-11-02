package com.nimu.omeeeye.ui

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nimu.omeeeye.R
import com.nimu.omeeeye.model.NavKey
import com.nimu.omeeeye.model.Step
import com.nimu.omeeeye.model.StepType
import java.util.Collections

/**
 * One row per step in the task editor. The adapter owns no workflow data of
 * its own: it renders the [steps] list instance handed in by
 * TaskEditorActivity, so moves/copies/deletes made through the listener are
 * immediately visible to the activity (same list object).
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
        val stepWait: TextView = view.findViewById(R.id.step_wait)
        val btnCopy: ImageButton = view.findViewById(R.id.btn_copy)
        val btnDrag: ImageButton = view.findViewById(R.id.btn_drag)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
        val btnStepPlay: ImageButton = view.findViewById(R.id.btn_step_play)
        val btnMenu: ImageButton = view.findViewById(R.id.btn_step_menu)
    }

    /** Called by the activity's ItemTouchHelper when a drag moves a row. */
    fun move(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        if (fromPosition !in steps.indices || toPosition !in steps.indices) return
        Collections.swap(steps, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
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
        holder.stepWait.text = context.getString(R.string.wait_format, step.waitAfterMs)

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) listener.onStepClicked(pos)
        }
        holder.stepWait.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) listener.onWaitClicked(pos)
        }
        holder.btnCopy.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) listener.onCopyStep(pos)
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
        val base = when (step.type) {
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
        // Per-step cycles: a repeated step shows "xN" beside its title;
        // cycles == 1 (the default, and every old step) shows no suffix.
        return if (step.stepCycles > 1) {
            base + " " + context.getString(R.string.step_cycles_format, step.stepCycles)
        } else {
            base
        }
    }
}
