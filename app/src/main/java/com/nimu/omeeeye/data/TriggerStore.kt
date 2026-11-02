package com.nimu.omeeeye.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Triggers (feature 1): a trigger maps one event to one workflow.
 * Local JSON file only, no server. Uses org.json (built into Android),
 * same pattern as WorkflowStore / CodeLibraryStore.
 *
 * Kinds (fixed set, approved):
 * - TIME: at a time of day ([timeMinutes] minutes after midnight).
 * - SMS: when an SMS arrives whose body contains [smsKeyword].
 * - NOTIFICATION: when the app with package [notifPackage] posts a
 *   notification.
 * - SHAKE: when the phone is shaken ([shakeSensitivity] 1 = Gentle,
 *   2 = Normal, 3 = Firm).
 * - BATTERY: when the battery level falls to [batteryPct] percent or
 *   below (fires once per downward crossing, see TriggerEngine).
 *
 * No trigger condition is a location: there are no coordinate fields
 * here, and none may be added (pick-only rule).
 */
object TriggerStore {

    private const val FILE_NAME = "triggers.json"

    enum class TriggerKind {
        TIME,
        SMS,
        NOTIFICATION,
        SHAKE,
        BATTERY
    }

    data class TriggerConfig(
        val id: String,
        val kind: TriggerKind,
        val workflowId: String,
        val enabled: Boolean,
        // TIME only; -1 = unset.
        val timeMinutes: Int = -1,
        // SMS only; matched case-insensitively against the message body.
        val smsKeyword: String = "",
        // NOTIFICATION only; package name of the watched app.
        val notifPackage: String = "",
        // SHAKE only; 1 = Gentle, 2 = Normal, 3 = Firm.
        val shakeSensitivity: Int = 2,
        // BATTERY only; percent threshold, 5..100.
        val batteryPct: Int = 20
    )

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun loadAll(context: Context): List<TriggerConfig> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                try {
                    triggerFromJson(arr.getJSONObject(i))
                } catch (e: Exception) {
                    // Skip one corrupt trigger instead of losing all.
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun loadEnabled(context: Context): List<TriggerConfig> =
        loadAll(context).filter { it.enabled }

    fun get(context: Context, id: String): TriggerConfig? =
        loadAll(context).firstOrNull { it.id == id }

    fun save(context: Context, trigger: TriggerConfig) {
        val all = loadAll(context).toMutableList()
        val index = all.indexOfFirst { it.id == trigger.id }
        if (index >= 0) all[index] = trigger else all.add(trigger)
        persist(context, all)
    }

    fun delete(context: Context, id: String) {
        persist(context, loadAll(context).filter { it.id != id })
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun persist(context: Context, triggers: List<TriggerConfig>) {
        val arr = JSONArray()
        triggers.forEach { t ->
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("kind", t.kind.name)
                    put("workflowId", t.workflowId)
                    put("enabled", t.enabled)
                    put("timeMinutes", t.timeMinutes)
                    put("smsKeyword", t.smsKeyword)
                    put("notifPackage", t.notifPackage)
                    put("shakeSensitivity", t.shakeSensitivity)
                    put("batteryPct", t.batteryPct)
                }
            )
        }
        file(context).writeText(arr.toString())
    }

    private fun triggerFromJson(obj: JSONObject): TriggerConfig {
        val kind = try {
            TriggerKind.valueOf(obj.optString("kind", "TIME"))
        } catch (e: Exception) {
            TriggerKind.TIME
        }
        return TriggerConfig(
            id = obj.optString("id", newId()),
            kind = kind,
            workflowId = obj.optString("workflowId", ""),
            enabled = obj.optBoolean("enabled", true),
            timeMinutes = obj.optInt("timeMinutes", -1),
            smsKeyword = obj.optString("smsKeyword", ""),
            notifPackage = obj.optString("notifPackage", ""),
            shakeSensitivity = obj.optInt("shakeSensitivity", 2)
                .coerceIn(1, 3),
            batteryPct = obj.optInt("batteryPct", 20).coerceIn(5, 100)
        )
    }
}
