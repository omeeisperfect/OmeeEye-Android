package com.nimu.omeeeye.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Code Library (feature 3): snippets saved by name, typed by the
 * TYPE_CODE step. Local JSON file only, no server.
 *
 * The Library screen (ui/LibraryActivity) owns the UI; this object owns
 * storage so the runner and the UI can never disagree on the format.
 */
object CodeLibraryStore {

    private const val FILE_NAME = "code_library.json"

    data class Snippet(val id: String, val name: String, val code: String)

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun loadAll(context: Context): List<Snippet> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Snippet(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", "Unnamed"),
                    code = o.optString("code", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(context: Context, id: String): Snippet? =
        loadAll(context).firstOrNull { it.id == id }

    fun save(context: Context, snippet: Snippet) {
        val all = loadAll(context).toMutableList()
        val index = all.indexOfFirst { it.id == snippet.id }
        if (index >= 0) all[index] = snippet else all.add(snippet)
        persist(context, all)
    }

    fun delete(context: Context, id: String) {
        persist(context, loadAll(context).filter { it.id != id })
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun persist(context: Context, snippets: List<Snippet>) {
        val arr = JSONArray()
        snippets.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("code", s.code)
                }
            )
        }
        file(context).writeText(arr.toString())
    }
}
