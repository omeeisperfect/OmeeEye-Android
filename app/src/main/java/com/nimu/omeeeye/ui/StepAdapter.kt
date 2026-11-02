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
        StepType.TAP -> android.R.drawable.ic_menu_mylocation
        StepType.SWIPE -> android.R.drawable.ic_menu_directions
        StepType.TYPE_TEXT -> android.R.drawable.ic_menu_edit
        StepType.WAIT -> android.R.drawable.ic_menu_recent_history
        StepType.NAVIGATION -> android.R.drawable.ic_menu_revert
        StepType.LAUNCH_APP -> android.R.drawable.ic_menu_send
        StepType.SUB_TASK -> android.R.drawable.ic_menu_agenda
    }

    private fun titleFor(context: android.content.Context, step: Step): String =
        when (step.type) {
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
        }
}
