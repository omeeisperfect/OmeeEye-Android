package com.nimu.omeeeye.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
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
        // Home card menu additions (items 1, 2, 4, 5, 6). Edit details
        // reuses the existing Edit path (onEdit opens the editor, where
        // the name is edited); the rest are new MainActivity handlers.
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
            popup.menu.add(0, MENU_EDIT_DETAILS, 0, R.string.card_menu_edit_details)
            popup.menu.add(0, MENU_DUPLICATE, 1, R.string.card_menu_duplicate)
            popup.menu.add(0, MENU_SHARE, 2, R.string.card_menu_share)
            popup.menu.add(0, MENU_SHORTCUT, 3, R.string.card_menu_shortcut)
            popup.menu.add(0, MENU_TIMED, 4, R.string.card_menu_timed)
            popup.menu.add(0, MENU_GESTURE_PATH, 5, R.string.card_menu_gesture_path)
            // Existing items, kept in place: Edit and Floating bar
            // still work exactly as before.
            popup.menu.add(0, MENU_EDIT, 6, R.string.card_menu_edit)
            popup.menu.add(0, MENU_FLOATING_BAR, 7, R.string.card_menu_floating_bar)
            popup.menu.add(0, MENU_DELETE, 8, R.string.card_menu_delete)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_EDIT_DETAILS -> {
                        listener.onEditDetails(workflow)
                        true
                    }
                    MENU_EDIT -> {
                        listener.onEdit(workflow)
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
                    MENU_TIMED -> {
                        listener.onTimed(workflow)
                        true
                    }
                    MENU_GESTURE_PATH -> {
                        listener.onGesturePath(workflow)
                        true
                    }
                    MENU_DELETE -> {
                        listener.onDelete(workflow)
                        true
                    }
                    MENU_FLOATING_BAR -> {
                        listener.onFloatingBar(workflow)
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
