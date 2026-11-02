package com.nimu.omeeeye.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Minimal local run history, shown from Home's 3-dot menu -> History.
 * Appended by WorkflowRunner when a run finishes, stops, or fails.
 * Local file only, no server.
 */
object RunHistoryStore {

    private const val FILE_NAME = "run_history.json"
    private const val MAX_ENTRIES = 200

    data class RunRecord(
        val workflowName: String,
        val timeMs: Long,
        val status: String,
        val stepsDone: Int,
        val totalSteps: Int
    )

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun record(
        context: Context,
        workflowName: String,
        status: String,
        stepsDone: Int,
        totalSteps: Int
    ) {
        try {
            val records = loadAll(context).toMutableList()
            records.add(
                0,
                RunRecord(workflowName, System.currentTimeMillis(), status, stepsDone, totalSteps)
            )
            val arr = JSONArray()
            records.take(MAX_ENTRIES).forEach { r ->
                arr.put(
                    JSONObject().apply {
                        put("workflowName", r.workflowName)
                        put("timeMs", r.timeMs)
                        put("status", r.status)
                        put("stepsDone", r.stepsDone)
                        put("totalSteps", r.totalSteps)
                    }
                )
            }
            file(context).writeText(arr.toString())
        } catch (e: Exception) {
            // History must never break a run.
        }
    }

    fun loadAll(context: Context): List<RunRecord> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RunRecord(
                    workflowName = o.optString("workflowName", ""),
                    timeMs = o.optLong("timeMs", 0),
                    status = o.optString("status", ""),
                    stepsDone = o.optInt("stepsDone", 0),
                    totalSteps = o.optInt("totalSteps", 0)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
