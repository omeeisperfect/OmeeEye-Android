package com.nimu.omeeeye.ui

import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.nimu.omeeeye.R
import com.nimu.omeeeye.model.Workflow

/**
 * Card list for Home. All actions are delegated to the listener, which
 * MainActivity wires to WorkflowStore / TaskEditorActivity / FloatingService.
 */
class WorkflowAdapter(private val listener: Listener) :
    RecyclerView.Adapter<WorkflowAdapter.ViewHolder>() {

    interface Listener {
        fun onEdit(workflow: Workflow)
        fun onDuplicate(workflow: Workflow)
        fun onDelete(workflow: Workflow)
        fun onFloatingBar(workflow: Workflow)
        fun onPlay(workflow: Workflow)
        // Home card menu (reference order + Floating bar): gesture
        // path, Edit details, Timed, copy, Share, shortcut, Floating
        // bar, delete. Edit details reuses the existing Edit path
        // (onEdit opens the editor, where the name is edited).
        fun onEditDetails(workflow: Workflow)
        fun onShare(workflow: Workflow)
        fun onShortcut(workflow: Workflow)
        fun onTimed(workflow: Workflow)
        fun onGesturePath(workflow: Workflow)
    }

    companion object {
        private const val MENU_EDIT = 1
        private const val MENU_DUPLICATE = 2
        private const val MENU_DELETE = 3
        private const val MENU_FLOATING_BAR = 4
        private const val MENU_EDIT_DETAILS = 5
        private const val MENU_SHARE = 6
        private const val MENU_SHORTCUT = 7
        private const val MENU_TIMED = 8
        private const val MENU_GESTURE_PATH = 9
    }

    private var items: List<Workflow> = emptyList()

    fun submitList(workflows: List<Workflow>) {
        items = workflows
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val workflowName: TextView = view.findViewById(R.id.workflow_name)
        val workflowInterval: TextView = view.findViewById(R.id.workflow_interval)
        val workflowCycles: TextView = view.findViewById(R.id.workflow_cycles)
        val btnCardMenu: ImageButton = view.findViewById(R.id.btn_card_menu)
        val btnPlay: ImageButton = view.findViewById(R.id.btn_play)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_workflow, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val workflow = items[position]
        holder.workflowName.text = workflow.name
        holder.workflowInterval.text = holder.itemView.context.getString(
            R.string.cycle_interval_format, workflow.cycleIntervalMs
        )
        holder.workflowCycles.text = holder.itemView.context.getString(
            R.string.cycles_format, workflow.cycles
        )

        holder.btnPlay.setOnClickListener { listener.onPlay(workflow) }

        holder.btnCardMenu.setOnClickListener { view ->
            val popup = PopupMenu(view.context, view)
            // Card menu, reference order and labels, plus Floating bar
            // as the 8th item (the user asked for floating mode to be
            // reachable; CLOSE alone is not enough). Every item wires
            // to an existing MainActivity handler; no handler was
            // invented. "Edit" is not listed (it duplicates Edit
            // details, which opens the same editor).
            popup.menu.add(0, MENU_GESTURE_PATH, 0, R.string.card_menu_gesture_path)
            popup.menu.add(0, MENU_EDIT_DETAILS, 1, R.string.card_menu_edit_details)
            popup.menu.add(0, MENU_TIMED, 2, R.string.card_menu_timed)
            popup.menu.add(0, MENU_DUPLICATE, 3, R.string.card_menu_copy_task)
            popup.menu.add(0, MENU_SHARE, 4, R.string.card_menu_share_task)
            popup.menu.add(0, MENU_SHORTCUT, 5, R.string.card_menu_shortcut_item)
            popup.menu.add(0, MENU_FLOATING_BAR, 6, R.string.card_menu_floating_bar)
            val deleteTitle = SpannableString(
                view.context.getString(R.string.card_menu_delete_item)
            ).apply {
                setSpan(
                    ForegroundColorSpan(
                        ContextCompat.getColor(view.context, R.color.danger)
                    ),
                    0, length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            popup.menu.add(0, MENU_DELETE, 7, deleteTitle)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_GESTURE_PATH -> {
                        listener.onGesturePath(workflow)
                        true
                    }
                    MENU_EDIT_DETAILS -> {
                        listener.onEditDetails(workflow)
                        true
                    }
                    MENU_TIMED -> {
                        listener.onTimed(workflow)
                        true
                    }
                    MENU_DUPLICATE -> {
                        listener.onDuplicate(workflow)
                        true
                    }
                    MENU_SHARE -> {
                        listener.onShare(workflow)
                        true
                    }
                    MENU_SHORTCUT -> {
                        listener.onShortcut(workflow)
                        true
                    }
                    MENU_FLOATING_BAR -> {
                        listener.onFloatingBar(workflow)
                        true
                    }
                    MENU_DELETE -> {
                        listener.onDelete(workflow)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    override fun getItemCount(): Int = items.size
}
