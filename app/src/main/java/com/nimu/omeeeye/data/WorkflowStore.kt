package com.nimu.omeeeye.data

import android.content.Context
import com.nimu.omeeeye.model.NavKey
import com.nimu.omeeeye.model.Point
import com.nimu.omeeeye.model.Step
import com.nimu.omeeeye.model.StepType
import com.nimu.omeeeye.model.Workflow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Local-only storage. Workflows live in one JSON file in internal storage.
 * No server, no account, no sync. Uses org.json (built into Android) so
 * the project needs no JSON dependency.
 *
 * Every other file in the app must use this object for workflow data and
 * must use [stepToJson] / [stepFromJson] for step serialisation, so the
 * on-disk format stays consistent.
 */
object WorkflowStore {

    private const val FILE_NAME = "workflows.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun loadAll(context: Context): List<Workflow> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { stepWorkflowFromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Workflows shown on Home (not in Trash). */
    fun loadActive(context: Context): List<Workflow> =
        loadAll(context).filter { !it.deleted }
            .sortedByDescending { it.updatedAt }

    /** Workflows in Trash (soft-deleted). */
    fun loadTrash(context: Context): List<Workflow> =
        loadAll(context).filter { it.deleted }
            .sortedByDescending { it.updatedAt }

    fun get(context: Context, id: String): Workflow? =
        loadAll(context).firstOrNull { it.id == id }

    fun save(context: Context, workflow: Workflow) {
        val all = loadAll(context).toMutableList()
        val index = all.indexOfFirst { it.id == workflow.id }
        val stamped = workflow.copy(updatedAt = System.currentTimeMillis())
        if (index >= 0) all[index] = stamped else all.add(stamped)
        persist(context, all)
    }

    /** Soft delete: moves the workflow to Trash. */
    fun delete(context: Context, id: String) {
        get(context, id)?.let { save(context, it.copy(deleted = true)) }
    }

    fun restore(context: Context, id: String) {
        get(context, id)?.let { save(context, it.copy(deleted = false)) }
    }

    fun deletePermanently(context: Context, id: String) {
        persist(context, loadAll(context).filter { it.id != id })
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun persist(context: Context, workflows: List<Workflow>) {
        val arr = JSONArray()
        workflows.forEach { arr.put(workflowToJson(it)) }
        file(context).writeText(arr.toString())
    }

    fun workflowToJson(w: Workflow): JSONObject = JSONObject().apply {
        put("id", w.id)
        put("name", w.name)
        put("cycleIntervalMs", w.cycleIntervalMs)
        put("cycles", w.cycles)
        put("deleted", w.deleted)
        put("updatedAt", w.updatedAt)
        val steps = JSONArray()
        w.steps.forEach { steps.put(stepToJson(it)) }
        put("steps", steps)
    }

    fun stepWorkflowFromJson(obj: JSONObject): Workflow {
        val steps = mutableListOf<Step>()
        val arr = obj.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until arr.length()) {
            try {
                steps.add(stepFromJson(arr.getJSONObject(i)))
            } catch (e: Exception) {
                // Skip one corrupt step instead of losing the whole workflow.
            }
        }
        return Workflow(
            id = obj.optString("id", newId()),
            name = obj.optString("name", "Unnamed"),
            cycleIntervalMs = obj.optLong("cycleIntervalMs", 500),
            cycles = obj.optInt("cycles", 1),
            steps = steps,
            deleted = obj.optBoolean("deleted", false),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
        )
    }

    fun stepToJson(s: Step): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("type", s.type.name)
        s.point?.let { put("x", it.x); put("y", it.y) }
        s.start?.let { put("startX", it.x); put("startY", it.y) }
        s.end?.let { put("endX", it.x); put("endY", it.y) }
        put("durationMs", s.durationMs)
        put("text", s.text)
        s.navKey?.let { put("navKey", it.name) }
        put("packageName", s.packageName)
        put("subWorkflowId", s.subWorkflowId)
        put("waitAfterMs", s.waitAfterMs)
    }

    fun stepFromJson(obj: JSONObject): Step {
        val type = try {
            StepType.valueOf(obj.optString("type", "TAP"))
        } catch (e: Exception) {
            StepType.TAP
        }
        val navKey = try {
            if (obj.has("navKey")) NavKey.valueOf(obj.getString("navKey")) else null
        } catch (e: Exception) {
            null
        }
        return Step(
            id = obj.optString("id", newId()),
            type = type,
            point = if (obj.has("x")) Point(obj.getInt("x"), obj.getInt("y")) else null,
            start = if (obj.has("startX")) Point(obj.getInt("startX"), obj.getInt("startY")) else null,
            end = if (obj.has("endX")) Point(obj.getInt("endX"), obj.getInt("endY")) else null,
            durationMs = obj.optLong("durationMs", 0),
            text = obj.optString("text", ""),
            navKey = navKey,
            packageName = obj.optString("packageName", ""),
            subWorkflowId = obj.optString("subWorkflowId", ""),
            waitAfterMs = obj.optLong("waitAfterMs", 2000)
        )
    }
}
