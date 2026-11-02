package com.nimu.omeeeye.model

/**
 * A screen point picked ON SCREEN. Points are never typed by the user:
 * they only ever come from PickPointActivity (tap) or RecordActivity
 * (recorded touches). See the standing pick-only rule in README.md.
 */
data class Point(val x: Int, val y: Int)

enum class StepType {
    TAP,
    SWIPE,
    TYPE_TEXT,
    WAIT,
    NAVIGATION,
    LAUNCH_APP,
    SUB_TASK
}

enum class NavKey {
    BACK,
    HOME,
    RECENTS
}

data class Step(
    val id: String,
    val type: StepType,
    // TAP
    val point: Point? = null,
    // SWIPE
    val start: Point? = null,
    val end: Point? = null,
    // SWIPE duration / WAIT duration (ms)
    val durationMs: Long = 0,
    // TYPE_TEXT content (text content is not a location, so typing it is fine)
    val text: String = "",
    // NAVIGATION
    val navKey: NavKey? = null,
    // LAUNCH_APP
    val packageName: String = "",
    // SUB_TASK: id of another workflow
    val subWorkflowId: String = "",
    // Pause after this step finishes, shown as "Wait: N ms" in the editor
    val waitAfterMs: Long = 2000
)

data class Workflow(
    val id: String,
    val name: String,
    val cycleIntervalMs: Long = 500,
    val cycles: Int = 1,
    val steps: List<Step> = emptyList(),
    // Soft delete: true means the workflow lives in Trash, not on Home
    val deleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)
