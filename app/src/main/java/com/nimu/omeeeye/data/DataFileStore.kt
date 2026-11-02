package com.nimu.omeeeye.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Library data files (mobile DataSourceStore): TXT / CSV / Excel files
 * uploaded in the Code Library, selectable as a per-step data source
 * (TYPE_TEXT, PASTE) by their id — never by a file path typed by the
 * user. Files are copied into internal storage at upload time, exactly
 * like workflow-level data files, so the original document can move or
 * disappear without breaking the reference.
 *
 * Deleting a file removes its entry and its internal copy. Steps or
 * workflows still referencing the id are NOT silently rewritten: at
 * run time the runner fails that step with a clear "Data file not
 * found" message (see WorkflowRunner.resolveStepData).
 */
object DataFileStore {

    private const val FILE_NAME = "library_data_files.json"

    /** type is "TXT", "CSV" or "EXCEL"; fileName is internal storage only. */
    data class DataFile(
        val id: String,
        val name: String,
        val type: String,
        val fileName: String
    )

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun loadAll(context: Context): List<DataFile> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DataFile(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", "Unnamed"),
                    type = o.optString("type", ""),
                    fileName = o.optString("fileName", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(context: Context, id: String): DataFile? =
        loadAll(context).firstOrNull { it.id == id }

    fun save(context: Context, dataFile: DataFile) {
        val all = loadAll(context).toMutableList()
        val index = all.indexOfFirst { it.id == dataFile.id }
        if (index >= 0) all[index] = dataFile else all.add(dataFile)
        persist(context, all)
    }

    /** Removes the entry and deletes the internal copy, if it exists. */
    fun delete(context: Context, id: String) {
        val entry = loadAll(context).firstOrNull { it.id == id }
        persist(context, loadAll(context).filter { it.id != id })
        if (entry != null && entry.fileName.isNotBlank()) {
            try {
                File(context.filesDir, entry.fileName).delete()
            } catch (e: Exception) {
                // Best effort: the entry is gone, which is what matters.
            }
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun persist(context: Context, files: List<DataFile>) {
        val arr = JSONArray()
        files.forEach { d ->
            arr.put(
                JSONObject().apply {
                    put("id", d.id)
                    put("name", d.name)
                    put("type", d.type)
                    put("fileName", d.fileName)
                }
            )
        }
        file(context).writeText(arr.toString())
    }
}
