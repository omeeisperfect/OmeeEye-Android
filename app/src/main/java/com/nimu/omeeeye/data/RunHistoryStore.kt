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
        val totalSteps: Int,
        // Step-by-step detail (feature 11). Empty for records written
        // before detail existed, or for the old record() path.
        val stepLogs: List<StepLog> = emptyList(),
        val error: String = ""
    )

    /** One executed step inside a run record. [detail] is an extra
     * human-readable result line (today: the OCR text a step read, or
     * why it found nothing). Old records load it as blank. */
    data class StepLog(
        val stepIndex: Int,
        val label: String,
        val durationMs: Long,
        val status: String,
        val error: String = "",
        val detail: String = ""
    )

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun record(
        context: Context,
        workflowName: String,
        status: String,
        stepsDone: Int,
        totalSteps: Int
    ) {
        recordDetail(context, workflowName, status, stepsDone, totalSteps, emptyList(), "")
    }

    /** Full record with step-by-step detail (feature 11). */
    fun recordDetail(
        context: Context,
        workflowName: String,
        status: String,
        stepsDone: Int,
        totalSteps: Int,
        stepLogs: List<StepLog>,
        error: String
    ) {
        try {
            val records = loadAll(context).toMutableList()
            records.add(
                0,
                RunRecord(
                    workflowName,
                    System.currentTimeMillis(),
                    status,
                    stepsDone,
                    totalSteps,
                    stepLogs.take(500),
                    error
                )
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
                        put("error", r.error)
                        val steps = JSONArray()
                        r.stepLogs.forEach { s ->
                            steps.put(
                                JSONObject().apply {
                                    put("stepIndex", s.stepIndex)
                                    put("label", s.label)
                                    put("durationMs", s.durationMs)
                                    put("status", s.status)
                                    put("error", s.error)
                                    put("detail", s.detail)
                                }
                            )
                        }
                        put("steps", steps)
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
                val logs = mutableListOf<StepLog>()
                val stepsArr = o.optJSONArray("steps")
                if (stepsArr != null) {
                    for (j in 0 until stepsArr.length()) {
                        try {
                            val s = stepsArr.getJSONObject(j)
                            logs.add(
                                StepLog(
                                    stepIndex = s.optInt("stepIndex", 0),
                                    label = s.optString("label", ""),
                                    durationMs = s.optLong("durationMs", 0),
                                    status = s.optString("status", ""),
                                    error = s.optString("error", ""),
                                    detail = s.optString("detail", "")
                                )
                            )
                        } catch (e: Exception) {
                            // Skip one corrupt step log.
                        }
                    }
                }
                RunRecord(
                    workflowName = o.optString("workflowName", ""),
                    timeMs = o.optLong("timeMs", 0),
                    status = o.optString("status", ""),
                    stepsDone = o.optInt("stepsDone", 0),
                    totalSteps = o.optInt("totalSteps", 0),
                    stepLogs = logs,
                    error = o.optString("error", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
