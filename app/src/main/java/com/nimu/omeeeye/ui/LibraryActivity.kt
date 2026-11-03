package com.nimu.omeeeye.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.CodeLibraryStore
import com.nimu.omeeeye.data.DataFileStore
import com.nimu.omeeeye.data.DataSourceLoader
import com.nimu.omeeeye.data.WorkflowStore
import com.nimu.omeeeye.model.Step

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
    private lateinit var dataFileList: RecyclerView
    private lateinit var dataEmptyText: TextView
    private lateinit var dataAdapter: DataFileAdapter

    // Data file upload: the chosen file is copied into internal storage
    // (never referenced by its original path, which the app may lose).
    // Only a file pick — never a typed path — per the pick-only rule.
    private val uploadLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                var ext = ""
                var displayName: String? = null
                try {
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex =
                                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                        }
                    }
                } catch (e: Exception) {
                    // Fall through to the last-path-segment name.
                }
                val rawName = displayName ?: (uri.lastPathSegment ?: "")
                ext = rawName.substringAfterLast('.', "").lowercase()
                if (ext !in listOf("txt", "csv", "xlsx", "xls")) {
                    ext = when (contentResolver.getType(uri)) {
                        "text/plain" -> "txt"
                        "text/csv", "text/comma-separated-values" -> "csv"
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
                        else -> ""
                    }
                }
                if (ext == "xls") {
                    Toast.makeText(this, R.string.data_xls_unsupported, Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                if (ext !in listOf("txt", "csv", "xlsx")) {
                    Toast.makeText(this, R.string.data_read_failed, Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
                val id = DataFileStore.newId()
                val fileName = "libdata_" + id + "." + ext
                val outFile = java.io.File(filesDir, fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    Toast.makeText(this, R.string.data_read_failed, Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
                // Snapshot first: displayName is a var captured by the
                // cursor closure above, so it cannot be smart-cast here.
                val display = displayName
                val name = (display?.substringBeforeLast('.', display) ?: "")
                    .ifBlank { "Data file" }
                DataFileStore.save(
                    this,
                    DataFileStore.DataFile(
                        id = id,
                        name = name,
                        type = if (ext == "xlsx") "EXCEL" else ext.uppercase(),
                        fileName = fileName
                    )
                )
                Toast.makeText(this, R.string.library_data_uploaded, Toast.LENGTH_SHORT).show()
                reload()
            } catch (e: Exception) {
                Toast.makeText(this, R.string.data_read_failed, Toast.LENGTH_SHORT).show()
            }
        }

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

        dataFileList = findViewById(R.id.data_file_list)
        dataEmptyText = findViewById(R.id.data_empty_text)
        dataAdapter = DataFileAdapter(
            onDelete = { file -> showDataDeleteDialog(file) },
            onOpen = { file -> showDatasetView(file) }
        )
        dataFileList.layoutManager = LinearLayoutManager(this)
        dataFileList.adapter = dataAdapter

        findViewById<Button>(R.id.btn_upload_data).setOnClickListener {
            uploadLauncher.launch("*/*")
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
        val dataFiles = DataFileStore.loadAll(this)
        dataAdapter.submitList(dataFiles)
        dataEmptyText.visibility = if (dataFiles.isEmpty()) View.VISIBLE else View.GONE
        dataFileList.visibility = if (dataFiles.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Counts steps (including nested sub-action lists) using a data file,
     *  plus workflows using it as their workflow-level data source
     *  (dataLibraryId), so the delete warning covers both uses. */
    private fun countDataFileReferences(fileId: String): Int {
        fun scan(steps: List<Step>): Int {
            var count = 0
            for (step in steps) {
                if (step.dataSourceId == fileId) count++
                count += scan(step.customSteps)
                count += scan(step.retrySteps)
                count += scan(step.afterRetrySteps)
            }
            return count
        }
        return WorkflowStore.loadAll(this).sumOf { workflow ->
            scan(workflow.steps) + if (workflow.dataLibraryId == fileId) 1 else 0
        }
    }

    /** Display form of a stored type: the stored "EXCEL" reads "Excel". */
    private fun displayType(type: String): String =
        if (type == "EXCEL") getString(R.string.library_dataset_type_excel)
        else type

    /**
     * Dataset view: tapping a Library data file opens a dialog preview
     * parsed with exactly the runner's rules (DataSourceLoader.loadFile):
     * TXT = one value per line under TEXT, CSV = header row + quoted
     * fields, Excel = first sheet, values only. At most the first 10
     * rows are shown, in a table that scrolls both ways. An unreadable
     * file shows its loader error, never an empty fake table; a
     * readable file with no data rows says so. Closing the dialog
     * (Close button or back) returns to the list, which stays
     * underneath untouched.
     */
    private fun showDatasetView(file: DataFileStore.DataFile) {
        val view = layoutInflater.inflate(R.layout.dialog_dataset_view, null)
        val nameView: TextView = view.findViewById(R.id.dataset_file_name)
        val metaView: TextView = view.findViewById(R.id.dataset_meta)
        val noteView: TextView = view.findViewById(R.id.dataset_note)
        val errorView: TextView = view.findViewById(R.id.dataset_error)
        val emptyView: TextView = view.findViewById(R.id.dataset_empty)
        val table: TableLayout = view.findViewById(R.id.dataset_table)
        nameView.text = file.name
        val typeLabel = displayType(file.type)
        val loaded = DataSourceLoader.loadFile(this, file.type, file.fileName)
        val error = loaded.error
        when {
            error != null -> {
                metaView.text = typeLabel
                errorView.text =
                    getString(R.string.library_dataset_error, error)
                errorView.visibility = View.VISIBLE
                table.visibility = View.GONE
            }
            loaded.rows.isEmpty() -> {
                metaView.text =
                    getString(R.string.library_dataset_meta, typeLabel, 0)
                emptyView.visibility = View.VISIBLE
                table.visibility = View.GONE
            }
            else -> {
                val columns = loaded.rows[0].keys.filter { it != "ROW" }
                metaView.text = getString(
                    R.string.library_dataset_meta,
                    typeLabel, loaded.rows.size
                )
                if (loaded.rows.size > 10) noteView.visibility = View.VISIBLE
                val headerRow = TableRow(this)
                columns.forEach { headerRow.addView(datasetCell(it, true)) }
                table.addView(headerRow)
                loaded.rows.take(10).forEach { row ->
                    val tableRow = TableRow(this)
                    columns.forEach { column ->
                        tableRow.addView(datasetCell(row[column] ?: "", false))
                    }
                    table.addView(tableRow)
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.library_dataset_title)
            .setView(view)
            .setPositiveButton(R.string.library_dataset_close, null)
            .show()
    }

    /**
     * One dataset table cell. Colour comes from the @color/... resource
     * (night variant applies automatically) — never a hardcoded hex.
     */
    private fun datasetCell(text: String, bold: Boolean): TextView {
        val padH = (12 * resources.displayMetrics.density).toInt()
        val padV = (6 * resources.displayMetrics.density).toInt()
        return TextView(this).apply {
            this.text = text
            setPadding(padH, padV, padH, padV)
            setTextColor(resources.getColor(R.color.text_primary, theme))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun showDataDeleteDialog(file: DataFileStore.DataFile) {
        val inUse = countDataFileReferences(file.id)
        val message = getString(R.string.library_data_delete_message, file.name) +
            if (inUse > 0) {
                "\n\n" + getString(R.string.library_data_in_use, inUse)
            } else {
                ""
            }
        AlertDialog.Builder(this)
            .setTitle(R.string.library_data_delete_title)
            .setMessage(message)
            .setPositiveButton(R.string.library_delete) { _, _ ->
                DataFileStore.delete(this, file.id)
                Toast.makeText(
                    this, R.string.library_data_deleted,
                    Toast.LENGTH_SHORT
                ).show()
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    /**
     * Data file rows. Reuses the snippet row layout (name + preview +
     * delete button): tapping a row opens the dataset view
     * ([showDatasetView]) — a data file has no edit dialog — and
     * deletion goes through [showDataDeleteDialog].
     */
    private inner class DataFileAdapter(
        private val onDelete: (DataFileStore.DataFile) -> Unit,
        private val onOpen: (DataFileStore.DataFile) -> Unit
    ) : RecyclerView.Adapter<DataFileViewHolder>() {

        private var items: List<DataFileStore.DataFile> = emptyList()

        fun submitList(files: List<DataFileStore.DataFile>) {
            items = files
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DataFileViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_snippet_row, parent, false)
            return DataFileViewHolder(view)
        }

        override fun onBindViewHolder(holder: DataFileViewHolder, position: Int) {
            val file = items[position]
            holder.fileName.text = file.name
            holder.filePreview.text = getString(
                R.string.library_dataset_row_preview, displayType(file.type)
            )
            holder.itemView.setOnClickListener { onOpen(file) }
            holder.itemView.setOnLongClickListener {
                onDelete(file)
                true
            }
            holder.deleteButton.setOnClickListener { onDelete(file) }
        }

        override fun getItemCount(): Int = items.size
    }

    // ViewHolder lives at activity level, not inside the inner adapter:
    // Kotlin does not allow a class declared inside an inner class
    // (that was the CI error at DataFileAdapter.ViewHolder).
    private class DataFileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fileName: TextView = view.findViewById(R.id.snippet_name)
        val filePreview: TextView = view.findViewById(R.id.snippet_preview)
        val deleteButton: ImageButton =
            view.findViewById(R.id.btn_delete_snippet)
    }
}
