package com.nimu.omeeeye.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.CodeLibraryStore

/**
 * Code Library (feature 3): manages saved code snippets by name.
 *
 * This screen only manages the library: add, edit, and delete snippets.
 * Snippets are typed by the TYPE_CODE step in a workflow; wiring that
 * step in the task editor is the coordinator's integration job, not
 * this screen's — nothing here pretends the step is wired.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var snippetList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: SnippetAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        snippetList = findViewById(R.id.snippet_list)
        emptyText = findViewById(R.id.empty_text)
        adapter = SnippetAdapter(
            onEdit = { snippet -> showSnippetDialog(snippet) },
            onDelete = { snippet -> showDeleteDialog(snippet) }
        )
        snippetList.layoutManager = LinearLayoutManager(this)
        snippetList.adapter = adapter

        findViewById<Button>(R.id.btn_add_snippet).setOnClickListener {
            showSnippetDialog(null)
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val snippets = CodeLibraryStore.loadAll(this)
        adapter.submitList(snippets)
        emptyText.visibility = if (snippets.isEmpty()) View.VISIBLE else View.GONE
        snippetList.visibility = if (snippets.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Add (existing == null) or edit dialog. The positive button is
     * intercepted so a blank name or blank code shows a message and
     * keeps the dialog open instead of saving an empty snippet.
     */
    private fun showSnippetDialog(existing: CodeLibraryStore.Snippet?) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val nameInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.library_name_hint)
            setText(existing?.name ?: "")
            setPadding(pad, pad, pad, pad)
        }
        val codeInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            hint = getString(R.string.library_code_hint)
            setText(existing?.code ?: "")
            minLines = 5
            setPadding(pad, pad, pad, pad)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameInput)
            addView(codeInput)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(
                if (existing == null) R.string.library_add_title
                else R.string.library_edit_title
            )
            .setView(container)
            .setPositiveButton(R.string.library_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val code = codeInput.text.toString()
                if (name.isEmpty()) {
                    Toast.makeText(
                        this, R.string.library_name_required,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                if (code.isBlank()) {
                    Toast.makeText(
                        this, R.string.library_code_required,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                CodeLibraryStore.save(
                    this,
                    CodeLibraryStore.Snippet(
                        id = existing?.id ?: CodeLibraryStore.newId(),
                        name = name,
                        code = code
                    )
                )
                Toast.makeText(
                    this, R.string.library_snippet_saved,
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
                reload()
            }
        }
        dialog.show()
    }

    private fun showDeleteDialog(snippet: CodeLibraryStore.Snippet) {
        AlertDialog.Builder(this)
            .setTitle(R.string.library_delete_title)
            .setMessage(getString(R.string.library_delete_message, snippet.name))
            .setPositiveButton(R.string.library_delete) { _, _ ->
                CodeLibraryStore.delete(this, snippet.id)
                Toast.makeText(
                    this, R.string.library_snippet_deleted,
                    Toast.LENGTH_SHORT
                ).show()
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class SnippetAdapter(
        private val onEdit: (CodeLibraryStore.Snippet) -> Unit,
        private val onDelete: (CodeLibraryStore.Snippet) -> Unit
    ) : RecyclerView.Adapter<SnippetAdapter.ViewHolder>() {

        private var items: List<CodeLibraryStore.Snippet> = emptyList()

        fun submitList(snippets: List<CodeLibraryStore.Snippet>) {
            items = snippets
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val snippetName: TextView = view.findViewById(R.id.snippet_name)
            val snippetPreview: TextView = view.findViewById(R.id.snippet_preview)
            val deleteButton: ImageButton =
                view.findViewById(R.id.btn_delete_snippet)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_snippet_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val snippet = items[position]
            holder.snippetName.text = snippet.name
            // Preview: first line of the code only, never the whole snippet.
            holder.snippetPreview.text =
                snippet.code.lineSequence().firstOrNull() ?: ""
            holder.itemView.setOnClickListener { onEdit(snippet) }
            holder.itemView.setOnLongClickListener {
                onDelete(snippet)
                true
            }
            holder.deleteButton.setOnClickListener { onDelete(snippet) }
        }

        override fun getItemCount(): Int = items.size
    }
}
