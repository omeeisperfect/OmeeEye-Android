package com.nimu.omeeeye.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.WorkflowStore
import com.nimu.omeeeye.model.Workflow

/** Soft-deleted workflows. Restore returns one to Home; Delete forever removes it. */
class TrashActivity : AppCompatActivity() {

    private lateinit var trashList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: TrashAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trash)

        trashList = findViewById(R.id.trash_list)
        emptyText = findViewById(R.id.empty_text)
        adapter = TrashAdapter(
            onRestore = { workflow ->
                WorkflowStore.restore(this, workflow.id)
                Toast.makeText(this, R.string.workflow_restored, Toast.LENGTH_SHORT).show()
                reload()
            },
            onDeleteForever = { workflow ->
                WorkflowStore.deletePermanently(this, workflow.id)
                Toast.makeText(
                    this,
                    R.string.workflow_deleted_forever,
                    Toast.LENGTH_SHORT
                ).show()
                reload()
            }
        )
        trashList.layoutManager = LinearLayoutManager(this)
        trashList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val items = WorkflowStore.loadTrash(this)
        adapter.submitList(items)
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        trashList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private class TrashAdapter(
        private val onRestore: (Workflow) -> Unit,
        private val onDeleteForever: (Workflow) -> Unit
    ) : RecyclerView.Adapter<TrashAdapter.ViewHolder>() {

        private var items: List<Workflow> = emptyList()

        fun submitList(workflows: List<Workflow>) {
            items = workflows
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val trashName: TextView = view.findViewById(R.id.trash_name)
            val btnRestore: Button = view.findViewById(R.id.btn_restore)
            val btnDeleteForever: Button = view.findViewById(R.id.btn_delete_forever)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_trash_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val workflow = items[position]
            holder.trashName.text = workflow.name
            holder.btnRestore.setOnClickListener { onRestore(workflow) }
            holder.btnDeleteForever.setOnClickListener { onDeleteForever(workflow) }
        }

        override fun getItemCount(): Int = items.size
    }
}
