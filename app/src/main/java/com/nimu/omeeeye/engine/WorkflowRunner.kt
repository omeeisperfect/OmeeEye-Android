package com.nimu.omeeeye.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.RunHistoryStore
import com.nimu.omeeeye.data.WorkflowStore
import com.nimu.omeeeye.model.Step
import com.nimu.omeeeye.model.StepType
import com.nimu.omeeeye.model.Workflow

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
 * - History is recorded on every terminal outcome. For a full run,
 *   totalSteps counts every step execution (steps x cycles); for
 *   [runSingle] it is the workflow's step count with 1 (or 0) done.
 * - A SUB_TASK step runs the referenced workflow inline. Its own steps are
 *   reported through onStepChanged as they run, but count as one completed
 *   step of the parent. A cycle (same workflow id twice in one chain) or a
 *   nesting depth past 5 fails the run.
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
    }

    private const val MAX_SUB_TASK_DEPTH = 5
    private const val TICK_INTERVAL_MS = 500L
    private const val SLEEP_CHUNK_MS = 100L

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
     * but no cycles and no waitAfterMs afterwards. Ignored while another
     * run is active.
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
        Thread({ tickerLoop() }, "omeeeye-ticker").start()
        return true
    }

    // ------------------------------------------------------------------
    // Full run
    // ------------------------------------------------------------------

    private fun runWorkflow(workflow: Workflow) {
        val cycles = workflow.cycles.coerceAtLeast(1)
        val totalSteps = workflow.steps.size * cycles
        if (AutomationService.instance == null) {
            finish(workflow, Status.FAILED, noServiceMessage(), 0, totalSteps)
            return
        }
        var stepsDone = 0
        var failure: String? = null
        var stopped = false
        outer@ for (cycle in 0 until cycles) {
            if (cycle > 0) {
                if (!sleepPausable(workflow.cycleIntervalMs)) {
                    stopped = true
                    break
                }
            }
            for ((index, step) in workflow.steps.withIndex()) {
                if (!waitIfPaused()) {
                    stopped = true
                    break@outer
                }
                currentStep = index
                postStepChanged(index, workflow.steps.size, step)
                postTick()
                when (val result = executeStep(step, index, listOf(workflow.id), 0)) {
                    is StepResult.Success -> {
                        stepsDone++
                        if (!sleepPausable(step.waitAfterMs)) {
                            stopped = true
                            break@outer
                        }
                    }
                    is StepResult.Stopped -> {
                        stopped = true
                        break@outer
                    }
                    is StepResult.Failed -> {
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
        currentStep = stepIndex
        postStepChanged(stepIndex, totalSteps, step)
        postTick()
        when (val result = executeStep(step, stepIndex, listOf(workflow.id), 0)) {
            is StepResult.Success ->
                finish(workflow, Status.FINISHED, null, 1, totalSteps)
            is StepResult.Stopped ->
                finish(workflow, Status.STOPPED, null, 0, totalSteps)
            is StepResult.Failed ->
                finish(workflow, Status.FAILED, result.message, 0, totalSteps)
        }
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
                if (service.typeText(step.text)) StepResult.Success
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
        for ((subIndex, subStep) in sub.steps.withIndex()) {
            if (!waitIfPaused()) return StepResult.Stopped
            currentStep = subIndex
            postStepChanged(subIndex, sub.steps.size, subStep)
            postTick()
            when (val result = executeStep(subStep, subIndex, chain + subId, depth + 1)) {
                is StepResult.Success -> {
                    if (!sleepPausable(subStep.waitAfterMs)) return StepResult.Stopped
                }
                is StepResult.Stopped -> return StepResult.Stopped
                is StepResult.Failed -> return result // Inner message already formatted.
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
            RunHistoryStore.record(ctx, workflow.name, status.name, stepsDone, totalSteps)
        }
        mainHandler.post {
            snapshotListeners().forEach { it.onFinished(status, message) }
        }
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

    /** "Step N failed: <reason>" using the existing string resource. */
    private fun failMessage(index: Int, reason: String): String =
        appContext?.getString(R.string.run_failed_step, index + 1, reason)
            ?: "Step ${index + 1} failed: $reason"
}
