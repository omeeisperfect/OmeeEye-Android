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
    }

    companion object {
        private const val MENU_EDIT = 1
        private const val MENU_DUPLICATE = 2
        private const val MENU_DELETE = 3
        private const val MENU_FLOATING_BAR = 4
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
            popup.menu.add(0, MENU_EDIT, 0, R.string.card_menu_edit)
            popup.menu.add(0, MENU_DUPLICATE, 1, R.string.card_menu_duplicate)
            popup.menu.add(0, MENU_DELETE, 2, R.string.card_menu_delete)
            popup.menu.add(0, MENU_FLOATING_BAR, 3, R.string.card_menu_floating_bar)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_EDIT -> {
                        listener.onEdit(workflow)
                        true
                    }
                    MENU_DUPLICATE -> {
                        listener.onDuplicate(workflow)
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
