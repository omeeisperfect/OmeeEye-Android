package com.nimu.omeeeye.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.CodeLibraryStore
import com.nimu.omeeeye.data.DataFileStore
import com.nimu.omeeeye.data.DataSourceLoader
import com.nimu.omeeeye.data.RunHistoryStore
import com.nimu.omeeeye.data.WorkflowStore
import com.nimu.omeeeye.model.AfterRetryMode
import com.nimu.omeeeye.model.MistakeLevel
import com.nimu.omeeeye.model.Point
import com.nimu.omeeeye.model.Step
import com.nimu.omeeeye.model.StepType
import com.nimu.omeeeye.model.SuccessMode
import com.nimu.omeeeye.model.Workflow
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * Runs a [Workflow] on a worker thread, delegating every physical action to
 * [AutomationService]. No coroutines (plain Threads + Handler, per the base
 * contract) and no new dependencies.
 *
 * Conventions the UI relies on:
 * - Step indexes in [Listener.onStepChanged] and [currentStepIndex] are
 *   0-based list indexes (the floating bar shows index + 1).
 * - Pause only holds the run between steps and during sleeps; a gesture
 *   already in flight finishes first. Stop ends the run fully — there is
 *   no resume after stop.
 * - History is recorded on every terminal outcome, now with step-by-step
 *   detail (feature 11): each executed step's label, duration, status
 *   and error reason.
 * - A SUB_TASK step runs the referenced workflow inline. Its own steps are
 *   reported through onStepChanged as they run, but count as one completed
 *   step of the parent. A cycle (same workflow id twice in one chain) or a
 *   nesting depth past 5 fails the run.
 * - Recognition steps (Button / Image / Color / OCR) share one shape:
 *   on success, CLICK taps the found target [Step.clickCount] times,
 *   CUSTOM optionally taps once first ([Step.clickFirst]) then runs
 *   [Step.customSteps], and JUMP runs [Step.customSteps] as "actions
 *   before jumping" then jumps to [Step.jumpTarget] and continues from
 *   that step ("Jump & Continue"). On failure the step retries
 *   [Step.retryCount] times with [Step.retryDelayMs] between attempts,
 *   running [Step.retrySteps] before each retry; after the retries are
 *   exhausted, IGNORE continues, CUSTOM runs [Step.afterRetrySteps],
 *   and JUMP runs [Step.afterRetrySteps] then jumps to
 *   [Step.afterRetryJumpTarget]. Jump targets are validated against the
 *   workflow's own step list; an invalid target fails the run instead
 *   of crashing it, and a jump-loop guard caps total executions.
 * - Data-driven runs (feature 4): when a workflow has a data source,
 *   one iteration runs per selected row. Columns are available as
 *   %COLUMN% variables, plus %ROW%. Start row is 1-based; with no
 *   explicit repeat, rows run from startRow to the end with no
 *   wrap-back; with an explicit repeat, rows wrap with modulo.
 * - OCR variables persist for the whole run; data variables change per
 *   row. %NAME% substitution applies to typed text, code, button text,
 *   intent URIs and network URLs.
 * - Per-step cycles (missing-core layer): a step's [Step.stepCycles]
 *   (clamped 1..99) repeats that step in sequence inside a full run;
 *   1 is today's behaviour. A Jump during a repeat skips the remaining
 *   repeats and applies; Stopped ends the run. Single-step tests always
 *   run once, regardless of stepCycles. History keeps one entry per
 *   step (combined duration).
 * - Recognition nesting (missing-core layer): custom / retry /
 *   afterRetry sub-action lists may contain nested recognition steps.
 *   They arrive with an incremented depth via [executeEmbedded]; a
 *   recognition step at depth >= MAX_RECOGNITION_DEPTH (top level + 2
 *   nested levels) fails with "Recognition nesting too deep".
 * - Break loop (reference-workflow clone): a recognition step with
 *   [Step.breakLoopOnSuccess] ends the whole run as FINISHED once its
 *   success handling has run; [Step.breakLoopOnFailure] does the same
 *   once the retries are exhausted and the after-retry handling has
 *   run. Both skip all remaining steps, cycles and data iterations.
 *   A break inside a sub-task or a sub-action list propagates to the
 *   outer run (unlike jumps, which are list-local, a break has no
 *   list-local meaning). When the matching mode is JUMP, the break
 *   wins: pre-jump actions still run, the jump itself is skipped.
 * - Loop action (reference-workflow clone): a LOOP_ACTION step runs
 *   one existing step of the same workflow [Step.loopCount] times
 *   (clamped 1..99). The target resolves by step ID through the
 *   per-run [loopResolver] (top-level list, or the sub-workflow's
 *   list inside a sub-task); a missing target, a self-target, or
 *   nesting past MAX_LOOP_DEPTH fails the run cleanly. The target's
 *   own waitAfterMs is not applied between repeats; Jump and
 *   BreakLoop results from the target escape the loop.
 */
object WorkflowRunner {

    enum class Status { FINISHED, STOPPED, FAILED }

    interface Listener {
        fun onStepChanged(index: Int, total: Int, step: Step)
        fun onTick(elapsedMs: Long)
        fun onFinished(status: Status, message: String?)
    }

    private sealed class StepResult {
        object Success : StepResult()
        object Stopped : StepResult()
        class Failed(val message: String) : StepResult()
        class Jump(val target: Int) : StepResult()
        /**
         * Break loop: end the whole run as FINISHED (see the class
         * KDoc). Only recognition steps produce it, via the break-loop
         * flags; it propagates through sub-tasks and sub-action lists.
         */
        object BreakLoop : StepResult()
    }

    /** Detection outcome for one recognition attempt. */
    private sealed class DetectOutcome {
        /** Found; [point] is the tap target, or null when there is none. */
        class Found(val point: Point?) : DetectOutcome()
        object NotFound : DetectOutcome()
        class HardFail(val message: String) : DetectOutcome()
    }

    private const val MAX_SUB_TASK_DEPTH = 5
    /**
     * Recognition nesting budget, as a depth limit: a top-level
     * recognition step runs at depth 0 and may nest recognition steps
     * 2 levels deep (depths 1 and 2). A recognition step reached at
     * depth >= this value fails with "Recognition nesting too deep"
     * instead of recursing forever. The depth counter is shared with
     * sub-tasks, so a recognition step inside a sub-task starts with a
     * smaller remaining budget.
     */
    private const val MAX_RECOGNITION_DEPTH = 3
    /** Loop-action nesting budget: a loop repeating a loop repeats at
     * most this deep; deeper fails with "Loop nesting too deep". */
    private const val MAX_LOOP_DEPTH = 5
    private const val TICK_INTERVAL_MS = 500L
    private const val SLEEP_CHUNK_MS = 100L
    private const val MAX_STEP_EXECUTIONS = 10000
    private const val CLICK_GAP_MS = 200L

    private var appContext: Context? = null
    private val listeners = mutableSetOf<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Guards paused / stopRequested / wait-notify. */
    private val lock = Object()

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var stopRequested = false
    @Volatile private var pauseStartMs = 0L
    @Volatile private var pausedTotalMs = 0L
    @Volatile private var runStartMs = 0L
    @Volatile private var lastTickPostMs = 0L
    @Volatile private var currentWorkflowField: Workflow? = null
    @Volatile private var currentStep = -1

    // Per-run state, reset in beginRun.
    private val dataVariables = mutableMapOf<String, String>()
    private val runVariables = mutableMapOf<String, String>()
    private var currentDataRowValues: List<String> = emptyList()
    private var currentDataRowColumns: List<String> = emptyList()
    // 0-based position in the current run's iteration sequence (workflow
    // data rows, or plain cycles). Per-step Library data sources read
    // the row at this ordinal (modulo the file's row count); single-step
    // tests never advance it, so they always read the first row.
    private var currentIterationOrdinal: Int = 0
    private val stepLogs = mutableListOf<RunHistoryStore.StepLog>()
    // OCR result line for the step currently executing (fix round,
    // item 6): what the last OCR attempt read, or why it found
    // nothing. Reset before every step so a stale value can never
    // leak into another step's history line.
    private var lastOcrDetail: String = ""
    // Loop-action target lookup for the current run (reference clone).
    // Set by runWorkflow / runSingleStep to the top-level step list
    // and temporarily swapped by executeSubTask to the sub-workflow's
    // list, so a loop step always resolves against the list it runs
    // in. Only the LOOP_ACTION branch reads it; every other step type
    // is unaffected by its presence. Single-threaded runner: the run
    // thread is the only writer, so no synchronisation is needed.
    private var loopResolver: ((String) -> Step?)? = null
    // Current loop-action nesting depth (see MAX_LOOP_DEPTH).
    private var loopDepth: Int = 0

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun addListener(l: Listener) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun removeListener(l: Listener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    /** Starts a full run. Ignored while another run is active. */
    fun start(workflow: Workflow) {
        if (!beginRun(workflow)) return
        Thread({ runWorkflow(workflow) }, "omeeeye-runner").start()
    }

    /**
     * Runs exactly one step of [workflow]: same step logic as a full run,
     * but no cycles and no waitAfterMs afterwards. Per-step
     * [Step.stepCycles] is likewise ignored: a single-step test always
     * runs once, regardless of stepCycles. A jump result from a
     * recognition step counts as finished here (a single step cannot
     * continue anywhere). Ignored while another run is active.
     */
    fun runSingle(workflow: Workflow, stepIndex: Int) {
        if (!beginRun(workflow)) return
        Thread({ runSingleStep(workflow, stepIndex) }, "omeeeye-runner").start()
    }

    fun pause() {
        synchronized(lock) {
            if (running && !paused) {
                paused = true
                pauseStartMs = SystemClock.elapsedRealtime()
            }
        }
    }

    fun resume() {
        synchronized(lock) {
            if (paused) {
                pausedTotalMs += SystemClock.elapsedRealtime() - pauseStartMs
                paused = false
                lock.notifyAll()
            }
        }
    }

    /** Terminal: the run ends fully, resume after stop is not possible. */
    fun stop() {
        synchronized(lock) {
            if (!running) return
            stopRequested = true
            paused = false
            lock.notifyAll()
        }
    }

    fun isRunning(): Boolean = running

    fun isPaused(): Boolean = paused

    fun currentWorkflow(): Workflow? = currentWorkflowField

    fun currentStepIndex(): Int = currentStep

    // ------------------------------------------------------------------
    // Run setup
    // ------------------------------------------------------------------

    /** Shared start-of-run bookkeeping. Returns false if a run is active. */
    private fun beginRun(workflow: Workflow): Boolean {
        synchronized(lock) {
            if (running) return false
            running = true
            stopRequested = false
            paused = false
            pausedTotalMs = 0L
            currentWorkflowField = workflow
            currentStep = -1
            runStartMs = SystemClock.elapsedRealtime()
            lastTickPostMs = runStartMs
        }
        dataVariables.clear()
        runVariables.clear()
        currentDataRowValues = emptyList()
        currentDataRowColumns = emptyList()
        currentIterationOrdinal = 0
        stepLogs.clear()
        loopResolver = null
        loopDepth = 0
        Thread({ tickerLoop() }, "omeeeye-ticker").start()
        return true
    }

    // ------------------------------------------------------------------
    // Full run
    // ------------------------------------------------------------------

    private fun runWorkflow(workflow: Workflow) {
        if (AutomationService.instance == null) {
            finish(workflow, Status.FAILED, noServiceMessage(), 0, workflow.steps.size)
            return
        }
        // Loop-action targets resolve against this workflow's list.
        loopResolver = { id -> workflow.steps.firstOrNull { it.id == id } }
        // Resolve data iterations first: null entries mean "no data row"
        // (plain cycles); a map entry means "run once for this row".
        val iterations: List<Map<String, String>?>
        val totalSteps: Int
        if (workflow.dataType.isNotBlank() || workflow.dataLibraryId.isNotBlank()) {
            val ctx = appContext
            if (ctx == null) {
                finish(workflow, Status.FAILED, noServiceMessage(), 0, workflow.steps.size)
                return
            }
            // Library bridge: a Library source resolves via its stored
            // entry (type + internal file name), then flows through the
            // existing startRow/dataRepeat logic below unchanged. A
            // deleted Library entry fails cleanly, like a missing file.
            val loaded = if (workflow.dataLibraryId.isNotBlank()) {
                val libraryFile = DataFileStore.get(ctx, workflow.dataLibraryId)
                if (libraryFile == null) {
                    finish(
                        workflow, Status.FAILED,
                        dataErrorMessage("Data file not found"), 0, workflow.steps.size
                    )
                    return
                }
                DataSourceLoader.loadFile(ctx, libraryFile.type, libraryFile.fileName)
            } else {
                DataSourceLoader.load(ctx, workflow)
            }
            if (loaded.error != null) {
                finish(
                    workflow, Status.FAILED,
                    dataErrorMessage(loaded.error), 0, workflow.steps.size
                )
                return
            }
            val rows = loaded.rows
            if (rows.isEmpty()) {
                finish(
                    workflow, Status.FAILED,
                    dataErrorMessage("Data file is empty"), 0, workflow.steps.size
                )
                return
            }
            val startIndex = workflow.startRow - 1
            if (startIndex < 0 || startIndex >= rows.size) {
                finish(
                    workflow, Status.FAILED,
                    dataErrorMessage("Start row is beyond the data file"),
                    0, workflow.steps.size
                )
                return
            }
            iterations = if (workflow.dataRepeat > 0) {
                (0 until workflow.dataRepeat).map { offset ->
                    rows[(startIndex + offset) % rows.size]
                }
            } else {
                rows.subList(startIndex, rows.size)
            }
            totalSteps = workflow.steps.size * iterations.size
        } else {
            val cycles = workflow.cycles.coerceAtLeast(1)
            iterations = List(cycles) { null }
            totalSteps = workflow.steps.size * cycles
        }

        var stepsDone = 0
        var failure: String? = null
        var stopped = false
        var executions = 0
        outer@ for ((iterationIndex, row) in iterations.withIndex()) {
            if (iterationIndex > 0) {
                if (!sleepPausable(workflow.cycleIntervalMs)) {
                    stopped = true
                    break
                }
            }
            currentIterationOrdinal = iterationIndex
            applyDataRow(row)
            var index = 0
            while (index < workflow.steps.size) {
                if (!waitIfPaused()) {
                    stopped = true
                    break@outer
                }
                val step = workflow.steps[index]
                currentStep = index
                postStepChanged(index, workflow.steps.size, step)
                postTick()
                val stepStart = SystemClock.elapsedRealtime()
                lastOcrDetail = ""
                // Per-step cycles: the step runs stepCycles times in
                // sequence (clamped 1..99; 1 = today's behaviour).
                // Pause/stop are honoured between repeats via
                // waitIfPaused (already checked once above, re-checked
                // here before every repeat); a Jump during a repeat
                // skips the remaining repeats and applies below;
                // Stopped/Failed end the repeats and propagate below.
                val repeatCount = step.stepCycles.coerceIn(1, 99)
                var result: StepResult = StepResult.Success
                var repeatIndex = 0
                while (repeatIndex < repeatCount) {
                    if (!waitIfPaused()) {
                        result = StepResult.Stopped
                        break
                    }
                    result = executeStep(step, index, listOf(workflow.id), 0)
                    executions++
                    if (executions > MAX_STEP_EXECUTIONS) {
                        failure = jumpLoopMessage()
                        logStep(
                            index, step,
                            SystemClock.elapsedRealtime() - stepStart,
                            "FAILED", failure
                        )
                        break@outer
                    }
                    repeatIndex++
                    if (result !is StepResult.Success) break
                }
                val duration = SystemClock.elapsedRealtime() - stepStart
                when (result) {
                    is StepResult.Success -> {
                        stepsDone++
                        logStep(index, step, duration, "OK", "")
                        if (!sleepPausable(step.waitAfterMs)) {
                            stopped = true
                            break@outer
                        }
                        index++
                    }
                    is StepResult.Jump -> {
                        if (result.target !in workflow.steps.indices) {
                            logStep(index, step, duration, "FAILED", invalidJumpMessage())
                            failure = invalidJumpMessage()
                            break@outer
                        }
                        stepsDone++
                        logStep(index, step, duration, "OK", "")
                        if (!sleepPausable(step.waitAfterMs)) {
                            stopped = true
                            break@outer
                        }
                        index = result.target
                    }
                    is StepResult.BreakLoop -> {
                        // Break loop: the run ends FINISHED here. No
                        // failure is set, so the finish below reports
                        // FINISHED; waitAfter still applies once, like
                        // for Jump, unless the run is stopped mid-wait.
                        stepsDone++
                        logStep(index, step, duration, "OK", "")
                        if (!sleepPausable(step.waitAfterMs)) {
                            stopped = true
                        }
                        break@outer
                    }
                    is StepResult.Stopped -> {
                        logStep(index, step, duration, "STOPPED", "")
                        stopped = true
                        break@outer
                    }
                    is StepResult.Failed -> {
                        logStep(index, step, duration, "FAILED", result.message)
                        failure = result.message
                        break@outer
                    }
                }
            }
        }
        when {
            stopped -> finish(workflow, Status.STOPPED, null, stepsDone, totalSteps)
            failure != null -> finish(workflow, Status.FAILED, failure, stepsDone, totalSteps)
            else -> finish(workflow, Status.FINISHED, null, stepsDone, totalSteps)
        }
    }

    private fun applyDataRow(row: Map<String, String>?) {
        dataVariables.clear()
        currentDataRowValues = emptyList()
        currentDataRowColumns = emptyList()
        if (row == null) return
        // Keep column order stable: data rows are LinkedHashMaps.
        val columns = row.keys.filter { it != "ROW" }
        currentDataRowColumns = columns
        currentDataRowValues = columns.map { row[it] ?: "" }
        dataVariables.putAll(row)
    }

    // ------------------------------------------------------------------
    // Single step
    // ------------------------------------------------------------------

    private fun runSingleStep(workflow: Workflow, stepIndex: Int) {
        val totalSteps = workflow.steps.size
        if (AutomationService.instance == null) {
            finish(workflow, Status.FAILED, noServiceMessage(), 0, totalSteps)
            return
        }
        val step = workflow.steps.getOrNull(stepIndex)
        if (step == null) {
            finish(workflow, Status.FAILED, "Invalid step index", 0, totalSteps)
            return
        }
        if (!waitIfPaused()) {
            finish(workflow, Status.STOPPED, null, 0, totalSteps)
            return
        }
        // A loop step tested on its own resolves against this list.
        loopResolver = { id -> workflow.steps.firstOrNull { it.id == id } }
        currentStep = stepIndex
        postStepChanged(stepIndex, totalSteps, step)
        postTick()
        val stepStart = SystemClock.elapsedRealtime()
        lastOcrDetail = ""
        when (val result = executeStep(step, stepIndex, listOf(workflow.id), 0)) {
            is StepResult.Success, is StepResult.Jump, is StepResult.BreakLoop -> {
                logStep(stepIndex, step, SystemClock.elapsedRealtime() - stepStart, "OK", "")
                finish(workflow, Status.FINISHED, null, 1, totalSteps)
            }
            is StepResult.Stopped -> {
                logStep(stepIndex, step, SystemClock.elapsedRealtime() - stepStart, "STOPPED", "")
                finish(workflow, Status.STOPPED, null, 0, totalSteps)
            }
            is StepResult.Failed -> {
                logStep(stepIndex, step, SystemClock.elapsedRealtime() - stepStart, "FAILED", result.message)
                finish(workflow, Status.FAILED, result.message, 0, totalSteps)
            }
        }
    }

    // ------------------------------------------------------------------
    // Variables
    // ------------------------------------------------------------------

    /** Replaces %NAME% placeholders from data-row and run variables. */
    fun substitute(text: String): String {
        var out = text
        for ((key, value) in dataVariables) {
            out = out.replace("%$key%", value)
            out = out.replace("%${key.uppercase()}%", value)
        }
        for ((key, value) in runVariables) {
            out = out.replace("%$key%", value)
            out = out.replace("%${key.uppercase()}%", value)
        }
        return out
    }

    // ------------------------------------------------------------------
    // Per-step data sources (desktop _data_source_id)
    // ------------------------------------------------------------------

    private sealed class StepData {
        /** Step has no own source: fall back to the workflow's source. */
        object NotAttached : StepData()
        /** One row of the step's own Library file. */
        class Row(val values: Map<String, String>) : StepData()
        /** The step's own source cannot be used; [message] is user-facing. */
        class Error(val message: String) : StepData()
    }

    /**
     * Resolves a step's own Library data source (precedence: the step's
     * own source first, else the workflow's source — the caller handles
     * the fallback by using [NotAttached]). The row is the current
     * iteration ordinal modulo the file's row count, so a step file
     * shorter than the workflow's iteration count cycles instead of
     * failing; a single-step test always reads the first row.
     */
    private fun resolveStepData(step: Step): StepData {
        if (step.dataSourceId.isBlank()) return StepData.NotAttached
        val ctx = appContext ?: return StepData.Error("Data file not found")
        val file = DataFileStore.get(ctx, step.dataSourceId)
            ?: return StepData.Error(
                stringOr(R.string.run_data_missing, "Data file not found")
            )
        val loaded = DataSourceLoader.loadFile(ctx, file.type, file.fileName)
        if (loaded.error != null) return StepData.Error(loaded.error)
        if (loaded.rows.isEmpty()) {
            return StepData.Error(
                stringOr(R.string.run_data_empty, "Data file is empty")
            )
        }
        val row = loaded.rows[currentIterationOrdinal % loaded.rows.size]
        return StepData.Row(row)
    }

    /**
     * The value of [column] in [row], or the first data column when
     * [column] is blank. Returns null when the named column does not
     * exist (the caller then fails the step cleanly). "ROW" is never a
     * data column.
     */
    private fun stepColumnValue(
        row: Map<String, String>,
        column: String
    ): String? {
        if (column.isNotBlank()) return row[column]
        return row.entries.firstOrNull { it.key != "ROW" }?.value
    }

    /**
     * Like [substitute], but a step's own row wins: placeholders resolve
     * from [stepRow] first, then the workflow's data row, then run
     * (OCR) variables. Steps without an own source keep using
     * [substitute] unchanged.
     */
    private fun substituteWithRow(text: String, stepRow: Map<String, String>): String {
        var out = text
        for ((key, value) in stepRow) {
            out = out.replace("%$key%", value)
            out = out.replace("%${key.uppercase()}%", value)
        }
        return substitute(out)
    }

    // ------------------------------------------------------------------
    // Step execution (shared by full runs, single steps, sub-tasks)
    // ------------------------------------------------------------------

    private fun executeStep(
        step: Step,
        index: Int,
        chain: List<String>,
        depth: Int
    ): StepResult {
        val service = AutomationService.instance
            ?: return StepResult.Failed(
                failMessage(index, "Accessibility service is not available")
            )
        return when (step.type) {
            StepType.TAP -> {
                val point = step.point
                    ?: return StepResult.Failed(failMessage(index, "Missing tap point"))
                if (service.tap(point)) StepResult.Success
                else StepResult.Failed(failMessage(index, "Tap failed"))
            }
            StepType.SWIPE -> {
                val start = step.start
                val end = step.end
                if (start == null || end == null) {
                    return StepResult.Failed(failMessage(index, "Missing swipe points"))
                }
                if (service.swipe(start, end, step.durationMs)) StepResult.Success
                else StepResult.Failed(failMessage(index, "Swipe failed"))
            }
            StepType.TYPE_TEXT -> {
                // Per-step data source (desktop _data_source_id): the
                // step's own Library file wins over the workflow's file.
                // Blank text + own source types the picked column's
                // value; otherwise placeholders resolve from the own
                // row first, then the workflow row. No own source =
                // exactly the old behaviour below.
                val stepData = resolveStepData(step)
                if (stepData is StepData.Error) {
                    return StepResult.Failed(failMessage(index, stepData.message))
                }
                val text = when (stepData) {
                    is StepData.Row -> {
                        if (step.text.isBlank()) {
                            stepColumnValue(stepData.values, step.dataColumn)
                                ?: return StepResult.Failed(
                                    failMessage(
                                        index,
                                        stringOr(
                                            R.string.run_data_column_missing,
                                            "Column not found in data file"
                                        )
                                    )
                                )
                        } else {
                            substituteWithRow(step.text, stepData.values)
                        }
                    }
                    else -> substitute(step.text)
                }
                val ok = if (step.mistakeLevel != MistakeLevel.OFF) {
                    service.typeHuman(text, step.mistakeLevel)
                } else {
                    service.typeText(text)
                }
                if (ok) StepResult.Success
                else StepResult.Failed(failMessage(index, "Type failed"))
            }
            StepType.WAIT -> {
                if (sleepPausable(step.durationMs)) StepResult.Success
                else StepResult.Stopped
            }
            StepType.NAVIGATION -> {
                val key = step.navKey
                    ?: return StepResult.Failed(failMessage(index, "Missing navigation key"))
                if (service.pressKey(key)) StepResult.Success
                else StepResult.Failed(failMessage(index, "Key press failed"))
            }
            StepType.LAUNCH_APP -> {
                if (step.packageName.isBlank()) {
                    return StepResult.Failed(failMessage(index, "Missing app package"))
                }
                if (service.launchApp(step.packageName)) StepResult.Success
                else StepResult.Failed(failMessage(index, "App launch failed"))
            }
            StepType.SUB_TASK -> executeSubTask(step, index, chain, depth)
            StepType.TYPE_CODE -> {
                val ctx = appContext
                    ?: return StepResult.Failed(failMessage(index, "Snippet not found"))
                val snippet = CodeLibraryStore.get(ctx, step.snippetId)
                    ?: return StepResult.Failed(
                        failMessage(index, stringOr(R.string.run_no_snippet, "Code snippet not found"))
                    )
                // Code typing is always human-like; the step's level
                // defaults to NORMAL in the editor.
                val level = if (step.mistakeLevel == MistakeLevel.OFF) {
                    MistakeLevel.NORMAL
                } else {
                    step.mistakeLevel
                }
                if (service.typeHuman(substitute(snippet.code), level)) {
                    StepResult.Success
                } else {
                    StepResult.Failed(failMessage(index, "Type failed"))
                }
            }
            StepType.TYPE_EXCEL_ROW -> {
                if (currentDataRowValues.isEmpty()) {
                    return StepResult.Failed(
                        failMessage(
                            index,
                            stringOr(R.string.run_no_data_row, "No data row attached to this step")
                        )
                    )
                }
                if (service.typeExcelRow(currentDataRowValues, step.mistakeLevel)) {
                    StepResult.Success
                } else {
                    StepResult.Failed(failMessage(index, "Excel row typing failed"))
                }
            }
            StepType.BUTTON_RECOGNITION,
            StepType.IMAGE_RECOGNITION,
            StepType.COLOR_RECOGNITION,
            StepType.OCR_READ -> executeRecognition(step, index, chain, depth)
            StepType.SCREENSHOT -> {
                val path = service.takeScreenshotSave()
                if (path != null) {
                    // Also open the screenshot preview (created by a
                    // later builder). The save itself already succeeded,
                    // so a missing preview activity — or any launch
                    // failure — is swallowed and the step still succeeds.
                    try {
                        val ctx = appContext
                        if (ctx != null) {
                            val preview = Intent().apply {
                                setClassName(
                                    ctx.packageName,
                                    "com.nimu.omeeeye.ui.ScreenshotPreviewActivity"
                                )
                                putExtra("path", path)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(preview)
                        }
                    } catch (e: Exception) {
                        // Preview is best-effort only; never fail the step.
                    }
                    StepResult.Success
                } else {
                    StepResult.Failed(screenshotFailMessage(index, service))
                }
            }
            StepType.PASTE -> {
                // Library source wins when set (existing behaviour).
                // Otherwise Typed text (step.text) is placed on the
                // clipboard first and then pasted with the exact same
                // mechanism; blank text + blank source is the old
                // clipboard-only behaviour, byte for byte.
                if (step.dataSourceId.isBlank() && step.text.isNotBlank()) {
                    service.setClipboardText(step.text)
                    if (service.pasteClipboard()) StepResult.Success
                    else StepResult.Failed(failMessage(index, "Paste failed"))
                } else if (step.dataSourceId.isBlank()) {
                    if (service.pasteClipboard()) StepResult.Success
                    else StepResult.Failed(failMessage(index, "Paste failed"))
                } else {
                    val stepData = resolveStepData(step)
                    if (stepData is StepData.Error) {
                        StepResult.Failed(failMessage(index, stepData.message))
                    } else if (stepData is StepData.Row) {
                        val value = stepColumnValue(stepData.values, step.dataColumn)
                        if (value == null) {
                            StepResult.Failed(
                                failMessage(
                                    index,
                                    stringOr(
                                        R.string.run_data_column_missing,
                                        "Column not found in data file"
                                    )
                                )
                            )
                        } else {
                            service.setClipboardText(value)
                            if (service.pasteClipboard()) StepResult.Success
                            else StepResult.Failed(failMessage(index, "Paste failed"))
                        }
                    } else {
                        StepResult.Failed(failMessage(index, "Paste failed"))
                    }
                }
            }
            StepType.VIBRATION -> {
                service.vibrate(300)
                StepResult.Success
            }
            StepType.SOUND -> {
                service.beep()
                StepResult.Success
            }
            StepType.SCREEN_OFF -> {
                if (service.lockScreen()) StepResult.Success
                else StepResult.Failed(failMessage(index, "Screen lock failed"))
            }
            StepType.AREA_CLICK -> {
                val region = step.region()
                    ?: return StepResult.Failed(failMessage(index, "No area picked"))
                if (step.areaClickMode == "ALL") {
                    // Grid mode: tap a grid across the region, 150 px
                    // apart, at most 50 taps. The first failed tap fails
                    // the step cleanly. Pause/stop are honoured between
                    // steps (per the runner convention), not mid-grid.
                    var taps = 0
                    var gridFailed = false
                    grid@ for (y in region.top..region.bottom step 150) {
                        for (x in region.left..region.right step 150) {
                            if (taps >= 50) break@grid
                            if (!service.tap(Point(x, y))) {
                                gridFailed = true
                                break@grid
                            }
                            taps++
                        }
                    }
                    if (gridFailed) {
                        StepResult.Failed(failMessage(index, "Area click failed"))
                    } else {
                        StepResult.Success
                    }
                } else {
                    // RANDOM (default, and any other value): exactly
                    // today's single-random-tap behaviour.
                    val point = Point(
                        Random.nextInt(region.left, region.right + 1),
                        Random.nextInt(region.top, region.bottom + 1)
                    )
                    if (service.tap(point)) StepResult.Success
                    else StepResult.Failed(failMessage(index, "Area click failed"))
                }
            }
            StepType.WAIT_RANDOM -> {
                val min = step.waitMinMs.coerceAtLeast(0)
                val max = step.waitMaxMs.coerceAtLeast(min)
                val delay = if (max > min) Random.nextLong(min, max + 1) else min
                if (sleepPausable(delay)) StepResult.Success else StepResult.Stopped
            }
            StepType.STOP_TASK -> StepResult.Stopped
            StepType.INTENT_URI -> {
                val uri = substitute(step.intentUri)
                if (uri.isBlank()) {
                    return StepResult.Failed(failMessage(index, "Missing intent URI"))
                }
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext?.startActivity(intent)
                    StepResult.Success
                } catch (e: Exception) {
                    StepResult.Failed(failMessage(index, "Could not open intent"))
                }
            }
            StepType.NETWORK_GET -> executeNetworkGet(step, index)
            StepType.SLIDE_LIST -> {
                if (service.scrollList(step.slideDirection, step.slideCount)) {
                    StepResult.Success
                } else {
                    StepResult.Failed(
                        failMessage(
                            index,
                            stringOr(
                                R.string.run_slide_no_list,
                                "No scrollable list found"
                            )
                        )
                    )
                }
            }
            StepType.QS_TILE -> {
                if (step.qsTileLabel.isBlank()) {
                    StepResult.Failed(
                        failMessage(
                            index,
                            stringOr(
                                R.string.run_qs_tile_not_found,
                                "Quick setting tile not found"
                            )
                        )
                    )
                } else if (service.openQuickSettingsAndTapTile(step.qsTileLabel)) {
                    StepResult.Success
                } else {
                    StepResult.Failed(
                        failMessage(
                            index,
                            stringOr(
                                R.string.run_qs_tile_not_found,
                                "Quick setting tile not found"
                            )
                        )
                    )
                }
            }
            StepType.POWER_DIALOG -> {
                if (service.showPowerDialog()) {
                    StepResult.Success
                } else {
                    StepResult.Failed(
                        failMessage(
                            index,
                            stringOr(
                                R.string.run_power_dialog_unavailable,
                                "Power dialog is not available on this device"
                            )
                        )
                    )
                }
            }
            StepType.LOOP_ACTION -> executeLoop(step, index, chain, depth)
        }
    }

    /**
     * Loop action (reference-workflow clone): run the step identified
     * by [Step.loopTargetId] [Step.loopCount] times in sequence.
     * The target comes from the per-run [loopResolver] (the list the
     * loop step runs in); a blank id, a missing target, a self-target
     * or nesting past MAX_LOOP_DEPTH fails the step cleanly instead
     * of looping forever or crashing. The target's own waitAfterMs is
     * deliberately NOT slept between repeats (the loop step's own
     * waitAfterMs is slept once by the caller, exactly like per-step
     * cycles); Stopped / Failed / Jump / BreakLoop from the target
     * end the loop at once and propagate to the caller.
     */
    private fun executeLoop(
        step: Step,
        index: Int,
        chain: List<String>,
        depth: Int
    ): StepResult {
        if (step.loopTargetId.isBlank()) {
            return StepResult.Failed(
                failMessage(
                    index,
                    stringOr(R.string.run_loop_no_target, "Loop target not found")
                )
            )
        }
        if (step.loopTargetId == step.id) {
            return StepResult.Failed(
                failMessage(
                    index,
                    stringOr(
                        R.string.run_loop_self,
                        "Loop action cannot repeat itself"
                    )
                )
            )
        }
        val target = loopResolver?.invoke(step.loopTargetId)
            ?: return StepResult.Failed(
                failMessage(
                    index,
                    stringOr(R.string.run_loop_no_target, "Loop target not found")
                )
            )
        if (target.id == step.id) {
            return StepResult.Failed(
                failMessage(
                    index,
                    stringOr(
                        R.string.run_loop_self,
                        "Loop action cannot repeat itself"
                    )
                )
            )
        }
        if (loopDepth >= MAX_LOOP_DEPTH) {
            return StepResult.Failed(
                failMessage(
                    index,
                    stringOr(R.string.run_loop_too_deep, "Loop nesting too deep")
                )
            )
        }
        val count = step.loopCount.coerceIn(1, 99)
        loopDepth++
        try {
            repeat(count) {
                if (!waitIfPaused()) return StepResult.Stopped
                when (val result = executeStep(target, index, chain, depth)) {
                    is StepResult.Success -> {
                        // Next repeat; nothing else to do.
                    }
                    is StepResult.Stopped,
                    is StepResult.Failed,
                    is StepResult.Jump,
                    is StepResult.BreakLoop -> return result
                }
            }
        } finally {
            loopDepth--
        }
        return StepResult.Success
    }

    private fun executeNetworkGet(step: Step, index: Int): StepResult {
        val urlText = substitute(step.networkUrl)
        if (urlText.isBlank()) {
            return StepResult.Failed(failMessage(index, "Missing URL"))
        }
        return try {
            val connection = URL(urlText).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            try {
                val code = connection.responseCode
                if (code in 200..299) {
                    StepResult.Success
                } else {
                    StepResult.Failed(
                        failMessage(
                            index,
                            stringOr(R.string.run_network_failed, "Network request failed") +
                                " ($code)"
                        )
                    )
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            StepResult.Failed(
                failMessage(
                    index,
                    stringOr(R.string.run_network_failed, "Network request failed")
                )
            )
        }
    }

    private fun executeSubTask(
        step: Step,
        index: Int,
        chain: List<String>,
        depth: Int
    ): StepResult {
        val subId = step.subWorkflowId
        if (subId.isBlank()) {
            return StepResult.Failed(failMessage(index, "Missing sub-task workflow"))
        }
        if (chain.contains(subId)) {
            return StepResult.Failed(failMessage(index, "Sub-task cycle detected"))
        }
        if (depth >= MAX_SUB_TASK_DEPTH) {
            return StepResult.Failed(failMessage(index, "Sub-task depth limit reached"))
        }
        val ctx = appContext
            ?: return StepResult.Failed(failMessage(index, "Sub-task workflow not found"))
        val sub = WorkflowStore.get(ctx, subId)
            ?: return StepResult.Failed(failMessage(index, "Sub-task workflow not found"))
        // Inline: the sub-workflow's own steps run here, with its own
        // waitAfterMs pauses, but no cycle repetition of the sub-workflow.
        // Loop-action targets inside resolve against THIS list (the
        // finally restores the caller's resolver on every exit path).
        val previousResolver = loopResolver
        loopResolver = { id -> sub.steps.firstOrNull { it.id == id } }
        try {
            for ((subIndex, subStep) in sub.steps.withIndex()) {
                if (!waitIfPaused()) return StepResult.Stopped
                currentStep = subIndex
                postStepChanged(subIndex, sub.steps.size, subStep)
                postTick()
                when (val result = executeStep(subStep, subIndex, chain + subId, depth + 1)) {
                    is StepResult.Success -> {
                        if (!sleepPausable(subStep.waitAfterMs)) return StepResult.Stopped
                    }
                    is StepResult.Jump -> {
                        // Jumps inside a sub-task never redirect the parent:
                        // the jumped-to step is outside this list's scope.
                        // Treat as completed and continue with the next step.
                        if (!sleepPausable(subStep.waitAfterMs)) return StepResult.Stopped
                    }
                    // Break loop is NOT list-local (unlike jumps), so it
                    // propagates to the outer run instead of completing.
                    is StepResult.BreakLoop -> return result
                    is StepResult.Stopped -> return StepResult.Stopped
                    is StepResult.Failed -> return result // Inner message already formatted.
                }
            }
        } finally {
            loopResolver = previousResolver
        }
        return StepResult.Success
    }

    // ------------------------------------------------------------------
    // Recognition: shared success / retry / after-retry shape
    // ------------------------------------------------------------------

    private fun executeRecognition(
        step: Step,
        index: Int,
        chain: List<String>,
        depth: Int
    ): StepResult {
        val service = AutomationService.instance
            ?: return StepResult.Failed(
                failMessage(index, "Accessibility service is not available")
            )
        // Nesting guard: recognition steps nested inside recognition
        // sub-actions (custom / retry / afterRetry) arrive here with an
        // incremented depth via executeEmbedded. Deeper than the top
        // level + 2 nested levels fails cleanly instead of recursing.
        if (depth >= MAX_RECOGNITION_DEPTH) {
            return StepResult.Failed(
                failMessage(
                    index,
                    stringOr(
                        R.string.run_recognition_too_deep,
                        "Recognition nesting too deep"
                    )
                )
            )
        }
        val region = if (step.areaMode == com.nimu.omeeeye.model.AreaMode.REGION) {
            step.region()
        } else {
            null
        }
        // Image templates are loaded once, not on every retry.
        val templateBitmap = if (step.type == StepType.IMAGE_RECOGNITION) {
            if (step.templatePath.isBlank()) {
                return StepResult.Failed(failMessage(index, "No image template"))
            }
            service.loadTemplateBitmap(step.templatePath)
                ?: return StepResult.Failed(failMessage(index, "Image template not found"))
        } else {
            null
        }

        val detect: () -> DetectOutcome = {
            when (step.type) {
                StepType.BUTTON_RECOGNITION -> {
                    val bounds = service.findNodeByText(
                        substitute(step.matchText),
                        step.matchMode,
                        step.buttonId,
                        step.buttonDesc,
                        region
                    )
                    if (bounds != null) {
                        DetectOutcome.Found(Point(bounds.centerX(), bounds.centerY()))
                    } else {
                        DetectOutcome.NotFound
                    }
                }
                StepType.IMAGE_RECOGNITION -> {
                    val shot = service.takeScreenshotBitmap()
                    if (shot == null) {
                        DetectOutcome.HardFail(
                            screenshotFailMessage(index, service)
                        )
                    } else {
                        val point = service.findImageInBitmap(shot, templateBitmap!!, region)
                        shot.recycle()
                        if (point != null) DetectOutcome.Found(point)
                        else DetectOutcome.NotFound
                    }
                }
                StepType.COLOR_RECOGNITION -> {
                    if (step.anchorX >= 0 && step.anchorY >= 0) {
                        // Anchor mode: only the live pixel at the
                        // picked anchor point is checked (pickColorAt
                        // screenshots internally), against colorValue
                        // within colorTolerance, per channel. No
                        // region search runs in this mode.
                        val live = service.pickColorAt(
                            Point(step.anchorX, step.anchorY)
                        )
                        if (live == null) {
                            DetectOutcome.HardFail(
                                screenshotFailMessage(index, service)
                            )
                        } else {
                            val tr = (step.colorValue shr 16) and 0xFF
                            val tg = (step.colorValue shr 8) and 0xFF
                            val tb = step.colorValue and 0xFF
                            val r = (live shr 16) and 0xFF
                            val g = (live shr 8) and 0xFF
                            val b = live and 0xFF
                            val tol = step.colorTolerance
                            if (kotlin.math.abs(r - tr) <= tol &&
                                kotlin.math.abs(g - tg) <= tol &&
                                kotlin.math.abs(b - tb) <= tol
                            ) {
                                DetectOutcome.Found(Point(step.anchorX, step.anchorY))
                            } else {
                                DetectOutcome.NotFound
                            }
                        }
                    } else {
                        // No anchor: the old region-search behaviour,
                        // unchanged.
                        val shot = service.takeScreenshotBitmap()
                        if (shot == null) {
                            DetectOutcome.HardFail(
                                screenshotFailMessage(index, service)
                            )
                        } else {
                            val point = service.findColorInBitmap(
                                shot, step.colorValue, step.colorTolerance, region
                            )
                            shot.recycle()
                            if (point != null) DetectOutcome.Found(point)
                            else DetectOutcome.NotFound
                        }
                    }
                }
                StepType.OCR_READ -> {
                    val shot = service.takeScreenshotBitmap()
                    if (shot == null) {
                        DetectOutcome.HardFail(
                            screenshotFailMessage(index, service)
                        )
                    } else {
                        val result = OcrEngine.read(shot, region?.let {
                            android.graphics.Rect(it.left, it.top, it.right, it.bottom)
                        })
                        shot.recycle()
                        val text = result.text
                        val wanted = substitute(step.matchText).trim()
                        val found = if (wanted.isEmpty()) {
                            text.isNotEmpty()
                        } else if (step.matchMode == com.nimu.omeeeye.model.MatchMode.EXACT) {
                            text.equals(wanted, ignoreCase = true)
                        } else {
                            text.contains(wanted, ignoreCase = true)
                        }
                        // History line (fix round, item 6): never store
                        // OCR silently. Found shows the read text;
                        // not-found says so explicitly, with the wanted
                        // text when one was set. The crop path itself
                        // was checked: the screenshot bitmap is full
                        // resolution and the region is absolute screen
                        // pixels, so no scaling/offset error hides here.
                        // (stringOr cannot pass format args, so these
                        // lines format with getString directly.)
                        lastOcrDetail = try {
                            when {
                                text.isNotEmpty() && found -> appContext?.getString(
                                    R.string.ocr_history_found, text.take(100)
                                ) ?: "OCR text: “${text.take(100)}”"
                                wanted.isNotEmpty() -> appContext?.getString(
                                    R.string.ocr_history_no_text_wanted, wanted
                                ) ?: "OCR: no text found in region (looking for “$wanted”)"
                                else -> appContext?.getString(
                                    R.string.ocr_history_no_text
                                ) ?: "OCR: no text found in region"
                            }
                        } catch (e: Exception) {
                            if (text.isNotEmpty() && found) {
                                "OCR text: “${text.take(100)}”"
                            } else {
                                "OCR: no text found in region"
                            }
                        }
                        if (found) {
                            runVariables[step.ocrVariable.ifBlank { "OCR_TEXT" }] = text
                            val point = result.bounds?.let {
                                Point(it.centerX(), it.centerY())
                            } ?: region?.center()
                            DetectOutcome.Found(point)
                        } else {
                            DetectOutcome.NotFound
                        }
                    }
                }
                else -> DetectOutcome.NotFound
            }
        }

        var attempt = 0
        while (true) {
            when (val outcome = detect()) {
                is DetectOutcome.HardFail -> return StepResult.Failed(outcome.message)
                is DetectOutcome.Found -> {
                    return when (step.successMode) {
                        SuccessMode.CLICK -> {
                            val point = outcome.point
                            if (point != null) {
                                repeat(step.clickCount.coerceAtLeast(1)) {
                                    if (!service.tap(point)) {
                                        return StepResult.Failed(
                                            failMessage(index, "Tap failed")
                                        )
                                    }
                                    if (!sleepPausable(CLICK_GAP_MS)) {
                                        return StepResult.Stopped
                                    }
                                }
                            }
                            // Break loop ends the whole run as FINISHED
                            // once the success handling has run.
                            if (step.breakLoopOnSuccess) StepResult.BreakLoop
                            else StepResult.Success
                        }
                        SuccessMode.CUSTOM -> {
                            if (step.clickFirst && outcome.point != null) {
                                if (!service.tap(outcome.point)) {
                                    return StepResult.Failed(
                                        failMessage(index, "Tap failed")
                                    )
                                }
                            }
                            when (val result = executeEmbedded(step.customSteps, index, chain, depth)) {
                                is StepResult.Success -> {
                                    if (step.breakLoopOnSuccess) StepResult.BreakLoop
                                    else result
                                }
                                else -> result
                            }
                        }
                        SuccessMode.JUMP -> {
                            // Click-first applies in Jump mode too: the
                            // editor shows the checkbox here, so it must
                            // do something at run time (tap, then jump).
                            if (step.clickFirst && outcome.point != null) {
                                if (!service.tap(outcome.point)) {
                                    return StepResult.Failed(
                                        failMessage(index, "Tap failed")
                                    )
                                }
                            }
                            when (val pre = executeEmbedded(step.customSteps, index, chain, depth)) {
                                is StepResult.Success -> {
                                    // Break wins over jump: the pre-jump
                                    // actions ran, but the run ends
                                    // instead of jumping (see Step).
                                    if (step.breakLoopOnSuccess) {
                                        StepResult.BreakLoop
                                    } else if (step.jumpTarget < 0) {
                                        StepResult.Failed(
                                            failMessage(
                                                index,
                                                stringOr(
                                                    R.string.run_invalid_jump,
                                                    "Invalid jump target"
                                                )
                                            )
                                        )
                                    } else {
                                        StepResult.Jump(step.jumpTarget)
                                    }
                                }
                                else -> pre
                            }
                        }
                    }
                }
                is DetectOutcome.NotFound -> {
                    if (attempt < step.retryCount.coerceAtLeast(0)) {
                        when (val pre = executeEmbedded(step.retrySteps, index, chain, depth)) {
                            is StepResult.Success -> {
                                // Continue to the next attempt below.
                            }
                            else -> return pre
                        }
                        if (!sleepPausable(step.retryDelayMs.coerceAtLeast(0))) {
                            return StepResult.Stopped
                        }
                        attempt++
                    } else {
                        return when (step.afterRetryMode) {
                            AfterRetryMode.IGNORE -> {
                                if (step.breakLoopOnFailure) StepResult.BreakLoop
                                else StepResult.Success
                            }
                            AfterRetryMode.CUSTOM -> {
                                when (
                                    val result = executeEmbedded(
                                        step.afterRetrySteps, index, chain, depth
                                    )
                                ) {
                                    is StepResult.Success -> {
                                        if (step.breakLoopOnFailure) StepResult.BreakLoop
                                        else result
                                    }
                                    else -> result
                                }
                            }
                            AfterRetryMode.JUMP -> {
                                when (
                                    val pre = executeEmbedded(
                                        step.afterRetrySteps, index, chain, depth
                                    )
                                ) {
                                    is StepResult.Success -> {
                                        // Break wins over jump here too.
                                        if (step.breakLoopOnFailure) {
                                            StepResult.BreakLoop
                                        } else if (step.afterRetryJumpTarget < 0) {
                                            StepResult.Failed(
                                                failMessage(
                                                    index,
                                                    stringOr(
                                                        R.string.run_invalid_jump,
                                                        "Invalid jump target"
                                                    )
                                                )
                                            )
                                        } else {
                                            StepResult.Jump(step.afterRetryJumpTarget)
                                        }
                                    }
                                    else -> pre
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Runs embedded sub-actions inline. A Jump inside a sub-action list
     * is ignored (sub-actions never redirect the parent run); Stopped
     * and Failed propagate. Recognition steps inside the list run with
     * depth + 1, so nesting past the top level + 2 nested levels fails
     * inside executeRecognition with "Recognition nesting too deep".
     */
    private fun executeEmbedded(
        steps: List<Step>,
        parentIndex: Int,
        chain: List<String>,
        depth: Int
    ): StepResult {
        if (depth >= MAX_SUB_TASK_DEPTH) {
            return StepResult.Failed(
                failMessage(parentIndex, "Sub-action depth limit reached")
            )
        }
        for ((subIndex, subStep) in steps.withIndex()) {
            if (!waitIfPaused()) return StepResult.Stopped
            when (val result = executeStep(subStep, subIndex, chain, depth + 1)) {
                is StepResult.Success, is StepResult.Jump -> {
                    if (!sleepPausable(subStep.waitAfterMs)) return StepResult.Stopped
                }
                // Break loop propagates out of sub-action lists too:
                // a jump target is list-local, but ending the run's
                // loop is not.
                is StepResult.BreakLoop -> return result
                is StepResult.Stopped -> return StepResult.Stopped
                is StepResult.Failed -> return result
            }
        }
        return StepResult.Success
    }

    // ------------------------------------------------------------------
    // Pause / sleep helpers
    // ------------------------------------------------------------------

    /**
     * Waits while paused. Returns false if the run was stopped while
     * waiting. Called between steps; [sleepPausable] covers sleeps.
     */
    private fun waitIfPaused(): Boolean {
        synchronized(lock) {
            while (paused && !stopRequested) {
                try {
                    lock.wait()
                } catch (e: InterruptedException) {
                    // Keep waiting unless stopped; stop() notifies.
                }
            }
            return !stopRequested
        }
    }

    /**
     * Sleeps [ms], suspending while paused (pause time does not count
     * towards the sleep). Returns false if the run was stopped mid-sleep.
     */
    private fun sleepPausable(ms: Long): Boolean {
        var remaining = ms
        while (remaining > 0) {
            synchronized(lock) {
                while (paused && !stopRequested) {
                    try {
                        lock.wait()
                    } catch (e: InterruptedException) {
                        // Keep waiting unless stopped.
                    }
                }
                if (stopRequested) return false
            }
            val chunk = minOf(remaining, SLEEP_CHUNK_MS)
            try {
                Thread.sleep(chunk)
            } catch (e: InterruptedException) {
                if (stopRequested) return false
            }
            remaining -= chunk
        }
        return !stopRequested
    }

    // ------------------------------------------------------------------
    // Ticks, finish, messages
    // ------------------------------------------------------------------

    /** Elapsed run time in ms, excluding time spent paused. */
    private fun elapsedMs(): Long {
        val now = SystemClock.elapsedRealtime()
        var pausedMs = pausedTotalMs
        if (paused) pausedMs += now - pauseStartMs
        return (now - runStartMs - pausedMs).coerceAtLeast(0L)
    }

    private fun tickerLoop() {
        while (running) {
            try {
                Thread.sleep(SLEEP_CHUNK_MS)
            } catch (e: InterruptedException) {
                // Loop condition re-checked below.
            }
            if (!running || paused) continue
            val now = SystemClock.elapsedRealtime()
            if (now - lastTickPostMs >= TICK_INTERVAL_MS) {
                lastTickPostMs = now
                postTick()
            }
        }
    }

    private fun finish(
        workflow: Workflow,
        status: Status,
        message: String?,
        stepsDone: Int,
        totalSteps: Int
    ) {
        synchronized(lock) {
            running = false
            paused = false
            currentWorkflowField = null
            currentStep = -1
            lock.notifyAll()
        }
        appContext?.let { ctx ->
            RunHistoryStore.recordDetail(
                ctx, workflow.name, status.name, stepsDone, totalSteps,
                stepLogs.toList(), message ?: ""
            )
        }
        mainHandler.post {
            snapshotListeners().forEach { it.onFinished(status, message) }
        }
    }

    private fun logStep(
        index: Int,
        step: Step,
        durationMs: Long,
        status: String,
        error: String
    ) {
        val detail = when (step.type) {
            StepType.TYPE_TEXT -> step.text.take(30)
            StepType.BUTTON_RECOGNITION, StepType.OCR_READ -> step.matchText.take(30)
            StepType.TYPE_CODE -> step.snippetId.take(20)
            StepType.SLIDE_LIST -> step.slideDirection
            StepType.QS_TILE -> step.qsTileLabel.take(30)
            // POWER_DIALOG needs no detail: its type name is the label.
            else -> ""
        }
        val label = if (detail.isBlank()) step.type.name else "${step.type.name}: $detail"
        stepLogs.add(
            RunHistoryStore.StepLog(
                index, label, durationMs, status, error,
                detail = if (step.type == StepType.OCR_READ) lastOcrDetail else ""
            )
        )
    }

    private fun postStepChanged(index: Int, total: Int, step: Step) {
        mainHandler.post {
            snapshotListeners().forEach { it.onStepChanged(index, total, step) }
        }
    }

    private fun postTick() {
        val elapsed = elapsedMs()
        mainHandler.post {
            snapshotListeners().forEach { it.onTick(elapsed) }
        }
    }

    private fun snapshotListeners(): List<Listener> =
        synchronized(listeners) { listeners.toList() }

    private fun noServiceMessage(): String? =
        appContext?.getString(R.string.run_failed_no_service)

    private fun dataErrorMessage(detail: String): String =
        stringOr(R.string.run_data_failed, "Data file could not be loaded") +
            ": " + detail

    private fun invalidJumpMessage(): String =
        stringOr(R.string.run_invalid_jump, "Invalid jump target")

    private fun jumpLoopMessage(): String =
        stringOr(R.string.run_jump_loop, "Jump loop detected")

    /**
     * Screenshot failure message with the real API reason when known
     * (e.g. "Screenshot failed: Screenshot API error code 2"). A null
     * bitmap is still a failure — the detail only makes it diagnosable.
     */
    private fun screenshotFailMessage(index: Int, service: AutomationService?): String {
        val base = stringOr(R.string.run_screenshot_failed, "Screenshot failed")
        val detail = service?.lastScreenshotError
        val reason = if (detail != null) "$base: $detail" else base
        return failMessage(index, reason)
    }

    private fun stringOr(resId: Int, fallback: String): String =
        try {
            appContext?.getString(resId) ?: fallback
        } catch (e: Exception) {
            fallback
        }

    /** "Step N failed: <reason>" using the existing string resource. */
    private fun failMessage(index: Int, reason: String): String =
        appContext?.getString(R.string.run_failed_step, index + 1, reason)
            ?: "Step ${index + 1} failed: $reason"
}
