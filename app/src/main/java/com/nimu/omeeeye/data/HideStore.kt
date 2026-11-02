package com.nimu.omeeeye.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Hide Mode (feature 2) settings. Local JSON file only, no server.
 *
 * - [hidden]: last state written by HideManager. The launcher-component
 *   state is the authoritative visibility signal (see HideManager);
 *   this flag is kept so the UI and the trigger engine can read the
 *   intent without querying the PackageManager every time.
 * - [dialerCode]: code dialed in the phone app to unhide (for example
 *   a *# code). Dialed text is not a location.
 * - [smsKeyword]: keyword in an incoming SMS body that unhides.
 * - [volumeEnabled] / [shakeEnabled]: volume Up,Up,Down combo and shake
 *   as unhide triggers. Volume unhide needs the accessibility service
 *   enabled, because only it sees volume key events.
 * - [autoUnhideMinutes]: minutes after midnight to auto-unhide, or
 *   -1 = off.
 */
object HideStore {

    private const val FILE_NAME = "hide.json"

    data class HideSettings(
        val hidden: Boolean = false,
        val dialerCode: String = "",
        val smsKeyword: String = "",
        val volumeEnabled: Boolean = false,
        val shakeEnabled: Boolean = false,
        val autoUnhideMinutes: Int = -1
    )

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun get(context: Context): HideSettings {
        val f = file(context)
        if (!f.exists()) return HideSettings()
        return try {
            val obj = JSONObject(f.readText())
            HideSettings(
                hidden = obj.optBoolean("hidden", false),
                dialerCode = obj.optString("dialerCode", ""),
                smsKeyword = obj.optString("smsKeyword", ""),
                volumeEnabled = obj.optBoolean("volumeEnabled", false),
                shakeEnabled = obj.optBoolean("shakeEnabled", false),
                autoUnhideMinutes = obj.optInt("autoUnhideMinutes", -1)
            )
        } catch (e: Exception) {
            HideSettings()
        }
    }

    fun save(context: Context, settings: HideSettings) {
        try {
            val obj = JSONObject().apply {
                put("hidden", settings.hidden)
                put("dialerCode", settings.dialerCode)
                put("smsKeyword", settings.smsKeyword)
                put("volumeEnabled", settings.volumeEnabled)
                put("shakeEnabled", settings.shakeEnabled)
                put("autoUnhideMinutes", settings.autoUnhideMinutes)
            }
            file(context).writeText(obj.toString())
        } catch (e: Exception) {
            // Storage failure: the in-memory state is unchanged and the
            // UI reports the save only when this call returns, so a
            // failure here must not crash the screen.
        }
    }
}
