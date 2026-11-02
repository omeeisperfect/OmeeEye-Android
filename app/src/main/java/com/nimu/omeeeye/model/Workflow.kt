package com.nimu.omeeeye.model

/**
 * A screen point picked ON SCREEN. Points are never typed by the user:
 * they only ever come from the OverlayPickService overlay (tap or
 * recorded touches). See the standing pick-only rule in README.md.
 */
data class Point(val x: Int, val y: Int)

/** A rectangular screen area, picked ON SCREEN via the overlay. */
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Int, y: Int): Boolean =
        x in left..right && y in top..bottom

    fun center(): Point = Point((left + right) / 2, (top + bottom) / 2)
    fun isValid(): Boolean = right > left && bottom > top
}

enum class StepType {
    TAP,
    SWIPE,
    TYPE_TEXT,
    WAIT,
    NAVIGATION,
    LAUNCH_APP,
    SUB_TASK,
    // Code Library
    TYPE_CODE,
    // Data-driven Excel row
    TYPE_EXCEL_ROW,
    // Recognition (Button / Image / Color / OCR). All four share the same
    // success / failure shape (see the fields below): success mode
    // (click / custom / jump), retry count + delay, and after-retry mode
    // (ignore / custom / jump). Jump targets are 0-based indexes into the
    // SAME workflow's step list, always chosen from a picker in the UI —
    // never typed.
    BUTTON_RECOGNITION,
    IMAGE_RECOGNITION,
    COLOR_RECOGNITION,
    OCR_READ,
    // Advanced actions
    SCREENSHOT,
    PASTE,
    VIBRATION,
    SOUND,
    SCREEN_OFF,
    AREA_CLICK,
    WAIT_RANDOM,
    STOP_TASK,
    INTENT_URI,
    NETWORK_GET
}

enum class NavKey {
    BACK,
    HOME,
    RECENTS
}

/** Typing-mistakes level, mirroring the desktop rates. */
enum class MistakeLevel {
    OFF,
    LIGHT,
    NORMAL,
    HEAVY
}

/** How a button's text must match: whole text, or part of it. */
enum class MatchMode {
    EXACT,
    PARTIAL
}

/** Where a recognition step looks: the whole screen, or a picked region. */
enum class AreaMode {
    FULLSCREEN,
    REGION
}

/**
 * What happens when a recognition step SUCCEEDS.
 * CLICK: tap the found target [clickCount] times.
 * CUSTOM: optionally tap once first ([clickFirst]), then run [customSteps].
 * JUMP: run [customSteps] as "actions before jumping", then jump to
 * [jumpTarget] and continue execution from that step ("Jump & Continue").
 */
enum class SuccessMode {
    CLICK,
    CUSTOM,
    JUMP
}

/**
 * What happens when a recognition step still fails AFTER all retries.
 * IGNORE: continue with the next step as if nothing failed.
 * CUSTOM: run [afterRetrySteps], then continue with the next step.
 * JUMP: run [afterRetrySteps] as "actions before jumping", then jump to
 * [afterRetryJumpTarget] and continue from that step.
 */
enum class AfterRetryMode {
    IGNORE,
    CUSTOM,
    JUMP
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
    val waitAfterMs: Long = 2000,
    // ---- Code Library ----
    // TYPE_CODE: id of a snippet in CodeLibraryStore.
    val snippetId: String = "",
    // ---- Typing mistakes (TYPE_TEXT human-like, TYPE_CODE, TYPE_EXCEL_ROW) ----
    val mistakeLevel: MistakeLevel = MistakeLevel.OFF,
    // ---- Recognition: identification ----
    // BUTTON_RECOGNITION: button text. Manual TEXT entry is allowed here
    // (text is not a location); the no-typed-coordinates rule is untouched.
    val matchText: String = "",
    val matchMode: MatchMode = MatchMode.EXACT,
    // Optional extra identifiers for a button: accessibility view id and
    // content description. Empty means "not used". Matched as substrings.
    val buttonId: String = "",
    val buttonDesc: String = "",
    // IMAGE_RECOGNITION: internal file name of the captured template.
    val templatePath: String = "",
    // Recognition area: FULLSCREEN, or a region picked on screen.
    val areaMode: AreaMode = AreaMode.FULLSCREEN,
    // Region bounds in absolute screen pixels, picked on screen only.
    // -1 means "no region". Used by recognition steps (REGION mode) and
    // by AREA_CLICK (tap a random point inside the region).
    val regionLeft: Int = -1,
    val regionTop: Int = -1,
    val regionRight: Int = -1,
    val regionBottom: Int = -1,
    // COLOR_RECOGNITION: picked color (ARGB int) + tolerance.
    val colorValue: Int = 0,
    val colorTolerance: Int = 20,
    // OCR_READ: variable name the read text is stored under, usable as
    // %NAME% by later steps. Default OCR_TEXT.
    val ocrVariable: String = "OCR_TEXT",
    // ---- Recognition: success shape ----
    val successMode: SuccessMode = SuccessMode.CLICK,
    val clickCount: Int = 1,
    // Custom sub-actions (success CUSTOM mode, or pre-jump actions in
    // success JUMP mode). Executed inline, one level deep, with the same
    // pause/stop rules as normal steps. Jumps inside sub-actions are
    // ignored (a sub-action list never jumps).
    val customSteps: List<Step> = emptyList(),
    val clickFirst: Boolean = false,
    // 0-based index into the SAME workflow's steps, or -1 = none.
    val jumpTarget: Int = -1,
    // ---- Recognition: failure shape (retry, then after-retry) ----
    // Number of retries after the first failed attempt (default 1) and
    // the delay between retries (default 3000 ms).
    val retryCount: Int = 1,
    val retryDelayMs: Long = 3000,
    // Actions run BEFORE each retry attempt.
    val retrySteps: List<Step> = emptyList(),
    val afterRetryMode: AfterRetryMode = AfterRetryMode.IGNORE,
    // Custom actions for after-retry CUSTOM mode, or pre-jump actions in
    // after-retry JUMP mode.
    val afterRetrySteps: List<Step> = emptyList(),
    val afterRetryJumpTarget: Int = -1,
    // ---- Advanced actions ----
    // WAIT_RANDOM bounds (ms).
    val waitMinMs: Long = 0,
    val waitMaxMs: Long = 1000,
    // INTENT_URI target (a URI string, e.g. https://… or tel:…).
    val intentUri: String = "",
    // NETWORK_GET target URL (GET only).
    val networkUrl: String = ""
) {
    /** The picked region, or null when none was picked. */
    fun region(): Rect? =
        if (regionLeft >= 0 && regionTop >= 0 && regionRight > regionLeft &&
            regionBottom > regionTop
        ) {
            Rect(regionLeft, regionTop, regionRight, regionBottom)
        } else {
            null
        }
}

data class Workflow(
    val id: String,
    val name: String,
    val cycleIntervalMs: Long = 500,
    val cycles: Int = 1,
    val steps: List<Step> = emptyList(),
    // Soft delete: true means the workflow lives in Trash, not on Home
    val deleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    // ---- Data-driven repeat ----
    // "" = no data source; otherwise "TXT", "CSV" or "EXCEL".
    val dataType: String = "",
    // Internal-storage file name of the copied data file (see
    // DataSourceLoader). Empty when dataType is empty.
    val dataFileName: String = "",
    // 1-based row to start from (default 1 = first row).
    val startRow: Int = 1,
    // Rows to run when a data source is attached; 0 = all remaining rows
    // from startRow to the end (no wrap-back). When > 0, rows wrap with
    // modulo past the end of the file.
    val dataRepeat: Int = 0
)
