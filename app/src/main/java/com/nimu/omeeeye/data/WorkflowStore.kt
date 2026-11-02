package com.nimu.omeeeye.data

import android.content.Context
import com.nimu.omeeeye.model.AfterRetryMode
import com.nimu.omeeeye.model.AreaMode
import com.nimu.omeeeye.model.MatchMode
import com.nimu.omeeeye.model.MistakeLevel
import com.nimu.omeeeye.model.NavKey
import com.nimu.omeeeye.model.Point
import com.nimu.omeeeye.model.Step
import com.nimu.omeeeye.model.StepType
import com.nimu.omeeeye.model.SuccessMode
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

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        name: String,
        default: T
    ): T {
        return try {
            if (name.isBlank()) default else enumValueOf<T>(name)
        } catch (e: Exception) {
            default
        }
    }

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
        put("dataType", w.dataType)
        put("dataFileName", w.dataFileName)
        put("startRow", w.startRow)
        put("dataRepeat", w.dataRepeat)
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
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
            dataType = obj.optString("dataType", ""),
            dataFileName = obj.optString("dataFileName", ""),
            startRow = obj.optInt("startRow", 1),
            dataRepeat = obj.optInt("dataRepeat", 0)
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
        put("snippetId", s.snippetId)
        put("mistakeLevel", s.mistakeLevel.name)
        put("matchText", s.matchText)
        put("matchMode", s.matchMode.name)
        put("buttonId", s.buttonId)
        put("buttonDesc", s.buttonDesc)
        put("templatePath", s.templatePath)
        put("areaMode", s.areaMode.name)
        put("regionLeft", s.regionLeft)
        put("regionTop", s.regionTop)
        put("regionRight", s.regionRight)
        put("regionBottom", s.regionBottom)
        put("colorValue", s.colorValue)
        put("colorTolerance", s.colorTolerance)
        put("ocrVariable", s.ocrVariable)
        put("successMode", s.successMode.name)
        put("clickCount", s.clickCount)
        val custom = JSONArray()
        s.customSteps.forEach { custom.put(stepToJson(it)) }
        put("customSteps", custom)
        put("clickFirst", s.clickFirst)
        put("jumpTarget", s.jumpTarget)
        put("retryCount", s.retryCount)
        put("retryDelayMs", s.retryDelayMs)
        val retry = JSONArray()
        s.retrySteps.forEach { retry.put(stepToJson(it)) }
        put("retrySteps", retry)
        put("afterRetryMode", s.afterRetryMode.name)
        val after = JSONArray()
        s.afterRetrySteps.forEach { after.put(stepToJson(it)) }
        put("afterRetrySteps", after)
        put("afterRetryJumpTarget", s.afterRetryJumpTarget)
        put("waitMinMs", s.waitMinMs)
        put("waitMaxMs", s.waitMaxMs)
        put("intentUri", s.intentUri)
        put("networkUrl", s.networkUrl)
        put("dataSourceId", s.dataSourceId)
        put("dataColumn", s.dataColumn)
    }

    private fun stepsFromArray(arr: JSONArray?): List<Step> {
        val out = mutableListOf<Step>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            try {
                out.add(stepFromJson(arr.getJSONObject(i)))
            } catch (e: Exception) {
                // Skip one corrupt sub-action instead of losing the step.
            }
        }
        return out
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
            waitAfterMs = obj.optLong("waitAfterMs", 2000),
            snippetId = obj.optString("snippetId", ""),
            mistakeLevel = enumValueOrDefault(
                obj.optString("mistakeLevel", ""),
                MistakeLevel.OFF
            ),
            matchText = obj.optString("matchText", ""),
            matchMode = enumValueOrDefault(obj.optString("matchMode", ""), MatchMode.EXACT),
            buttonId = obj.optString("buttonId", ""),
            buttonDesc = obj.optString("buttonDesc", ""),
            templatePath = obj.optString("templatePath", ""),
            areaMode = enumValueOrDefault(obj.optString("areaMode", ""), AreaMode.FULLSCREEN),
            regionLeft = obj.optInt("regionLeft", -1),
            regionTop = obj.optInt("regionTop", -1),
            regionRight = obj.optInt("regionRight", -1),
            regionBottom = obj.optInt("regionBottom", -1),
            colorValue = obj.optInt("colorValue", 0),
            colorTolerance = obj.optInt("colorTolerance", 20),
            ocrVariable = obj.optString("ocrVariable", "OCR_TEXT"),
            successMode = enumValueOrDefault(
                obj.optString("successMode", ""),
                SuccessMode.CLICK
            ),
            clickCount = obj.optInt("clickCount", 1),
            customSteps = stepsFromArray(obj.optJSONArray("customSteps")),
            clickFirst = obj.optBoolean("clickFirst", false),
            jumpTarget = obj.optInt("jumpTarget", -1),
            retryCount = obj.optInt("retryCount", 1),
            retryDelayMs = obj.optLong("retryDelayMs", 3000),
            retrySteps = stepsFromArray(obj.optJSONArray("retrySteps")),
            afterRetryMode = enumValueOrDefault(
                obj.optString("afterRetryMode", ""),
                AfterRetryMode.IGNORE
            ),
            afterRetrySteps = stepsFromArray(obj.optJSONArray("afterRetrySteps")),
            afterRetryJumpTarget = obj.optInt("afterRetryJumpTarget", -1),
            waitMinMs = obj.optLong("waitMinMs", 0),
            waitMaxMs = obj.optLong("waitMaxMs", 1000),
            intentUri = obj.optString("intentUri", ""),
            networkUrl = obj.optString("networkUrl", ""),
            dataSourceId = obj.optString("dataSourceId", ""),
            dataColumn = obj.optString("dataColumn", "")
        )
    }
}
