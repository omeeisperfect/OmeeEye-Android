package com.nimu.omeeeye.ui

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.WorkflowStore
import com.nimu.omeeeye.engine.AutomationService
import com.nimu.omeeeye.engine.WorkflowRunner
import com.nimu.omeeeye.model.NavKey
import com.nimu.omeeeye.model.Step
import com.nimu.omeeeye.model.StepType

/**
 * Hosts the two floating overlays. Requires "Display over other apps".
 *
 * Idle bar (bound to one workflow), in reference order: configure
 * (wrench), play, target picker, close, expand. Running panel, in
 * reference order for the controls this app actually offers: red
 * stop, pause, timer (+ step count), Back/Home/Recents, more
 * (3-dot). The reference's undo / target / wait-indicator / OCR /
 * image-recognition / palette slots are NOT drawn: no runner or
 * recognition API behind them exists in this codebase, and drawing
 * them would be fake functionality (see report).
 *
 * The bar never appears over the task editor: opening the editor
 * from the bar removes the bar view, and opening the editor from
 * anywhere else hides it via hideIdle() in TaskEditorActivity.onCreate
 * (the service stays alive, and the bar returns via showIdle when a
 * run ends, via Home CLOSE, or via the workflow card's Floating bar
 * action when it is listed again). Runs only: the running panel shows
 * while a workflow executes; stopping a run ends it fully — there
 * is no resume after stop (pause is separate and only holds the run
 * between steps).
 */
class FloatingService : Service() {

    companion object {
        const val ACTION_IDLE = "idle"
        const val ACTION_RUNNING = "running"
        const val EXTRA_ACTION = "action"
        const val EXTRA_WORKFLOW_ID = "workflow_id"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "omeeeye_running"

        // The live service, if any. Set in onCreate, cleared in
        // onDestroy, so hideIdle() below never starts a service just
        // to hide nothing (starting one would leave a stray foreground
        // notification behind with no bar).
        private var instance: FloatingService? = null

        fun startIdle(context: Context, workflowId: String) {
            start(context, ACTION_IDLE, workflowId)
        }

        /**
         * The only entry point an Activity should use for the idle bar.
         * The old Context overload left two steps to the caller —
         * check the overlay permission, then move the task to the back
         * — and a caller that forgot either one produced exactly the
         * reported symptom (app backgrounds, no bar, or a silent
         * nothing). This overload does both itself: missing permission
         * shows the guide dialog and returns false, so the caller does
         * nothing else; on success the bar is started and the task is
         * moved to the back so the bar sits over the real screen.
         */
        fun startIdle(activity: Activity, workflowId: String): Boolean {
            if (!Settings.canDrawOverlays(activity)) {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.floating_mode_guide_title)
                    .setMessage(R.string.floating_mode_guide_text)
                    .setPositiveButton(R.string.floating_mode_guide_open_settings) { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${activity.packageName}")
                        )
                        activity.startActivity(intent)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return false
            }
            start(activity, ACTION_IDLE, workflowId)
            activity.moveTaskToBack(true)
            return true
        }

        /**
         * Hides the idle bar only. Called on the main thread
         * (TaskEditorActivity.onCreate). Never starts the
         * service, never touches the running panel: a run must always
         * stay stoppable, so only the idle view is dropped.
         */
        fun hideIdle() {
            instance?.removeIdleView()
        }

        fun startRunning(context: Context, workflowId: String) {
            start(context, ACTION_RUNNING, workflowId)
        }

        fun stopAll(context: Context) {
            context.stopService(Intent(context, FloatingService::class.java))
        }

        private fun start(context: Context, action: String, workflowId: String) {
            val intent = Intent(context, FloatingService::class.java).apply {
                putExtra(EXTRA_ACTION, action)
                putExtra(EXTRA_WORKFLOW_ID, workflowId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private var idleView: View? = null
    private var runningView: View? = null
    private var workflowId: String? = null
    private var timerText: TextView? = null
    private var stepText: TextView? = null
    private var pauseButton: ImageButton? = null
    private var waitText: TextView? = null
    private var navRow: View? = null
    private var toolsRow: View? = null
    private var expandButton: ImageButton? = null
    private var runningExpanded = true
    // Real wait countdown, runner-owned, no new runner field: when a
    // WAIT step starts, its duration comes from the step itself and
    // the start is anchored on the runner's elapsed clock (the same
    // clock onTick reports, so paused time never counts down). The
    // countdown refreshes on each runner tick (~500 ms); it is never
    // a static label. WAIT_RANDOM has no known duration (the runner
    // picks it at execution time), so it shows no countdown rather
    // than a guessed one. waitAfterMs gaps between steps are not
    // Wait steps and likewise show nothing.
    private var waitDurationMs: Long = 0
    private var waitStartElapsedMs: Long = 0
    private var lastElapsedMs: Long = 0

    private val runnerListener = object : WorkflowRunner.Listener {
        override fun onStepChanged(index: Int, total: Int, step: Step) {
            stepText?.text = getString(R.string.step_format, index + 1, total)
            if (step.type == StepType.WAIT && step.durationMs > 0) {
                waitDurationMs = step.durationMs
                waitStartElapsedMs = lastElapsedMs
            } else {
                waitDurationMs = 0
            }
            updateWaitText()
        }

        override fun onTick(elapsedMs: Long) {
            lastElapsedMs = elapsedMs
            timerText?.text = formatTime(elapsedMs)
            updateWaitText()
        }

        override fun onFinished(status: WorkflowRunner.Status, message: String?) {
            if (status == WorkflowRunner.Status.FAILED && message != null) {
                Toast.makeText(this@FloatingService, message, Toast.LENGTH_LONG).show()
            }
            // Any end of run (finished, stopped, failed) returns to the idle bar.
            workflowId?.let { showIdle(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        WorkflowRunner.init(applicationContext)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_IDLE -> {
                // A run must never lose its stop control: an idle
                // request during a run must NOT drop the running panel
                // (the old showIdle removed it first). Keep the panel;
                // if it is somehow gone, restore it so the run stays
                // stoppable. The requested id is deliberately NOT
                // stored here, or the post-run bar would bind to the
                // wrong workflow.
                if (WorkflowRunner.isRunning()) {
                    if (runningView == null) showRunning()
                    return START_NOT_STICKY
                }
                workflowId = intent.getStringExtra(EXTRA_WORKFLOW_ID)
                workflowId?.let { showIdle(it) }
            }
            ACTION_RUNNING -> {
                val id = intent.getStringExtra(EXTRA_WORKFLOW_ID)
                    ?: return START_NOT_STICKY
                workflowId = id
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, R.string.overlay_off, Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }
                val workflow = WorkflowStore.get(this, id)
                if (workflow == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startRun(workflow.id)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        WorkflowRunner.removeListener(runnerListener)
        removeViews()
        if (instance == this) instance = null
        super.onDestroy()
    }

    private fun startRun(id: String) {
        val workflow = WorkflowStore.get(this, id) ?: return
        showRunning()
        WorkflowRunner.removeListener(runnerListener)
        WorkflowRunner.addListener(runnerListener)
        WorkflowRunner.start(workflow)
    }

    private fun showIdle(id: String) {
        // REAL remaining failure, fixed here: the old order removed
        // the existing views FIRST and only then checked the overlay
        // permission — so a re-invocation with the permission missing
        // (or revoked since the bar was shown) destroyed the bar it
        // already had and showed nothing, with only a toast to show
        // for it. Checks now run before anything is removed, so a
        // failed invocation leaves the previous bar untouched.
        if (!Settings.canDrawOverlays(this)) {
            // Never fail silently: say exactly why no bar appears.
            Toast.makeText(this, R.string.floating_bar_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        if (WorkflowStore.get(this, id) == null) {
            // A bar bound to a deleted workflow would be worse than
            // none: its Play step silently does nothing and its
            // Configure step would open a brand-new workflow.
            Toast.makeText(this, R.string.floating_mode_workflow_gone, Toast.LENGTH_LONG).show()
            return
        }
        removeRunningView()
        // A bar left over from another workflow used to block a new one:
        // the old bar stayed bound to the old workflow's actions. Drop
        // it and build fresh for this workflow instead.
        removeIdleView()
        val view = LayoutInflater.from(this).inflate(R.layout.floating_idle, null)
        view.findViewById<View>(R.id.btn_configure).setOnClickListener {
            openEditor(id, pickTarget = false)
        }
        view.findViewById<View>(R.id.btn_play).setOnClickListener { startRun(id) }
        view.findViewById<View>(R.id.btn_target).setOnClickListener {
            // Opens the editor straight into point picking; the picked point
            // is appended as a Tap step (pick-only, never typed).
            openEditor(id, pickTarget = true)
        }
        view.findViewById<View>(R.id.btn_close).setOnClickListener {
            removeViews()
            stopSelf()
        }
        view.findViewById<View>(R.id.btn_expand).setOnClickListener {
            val main = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(main)
        }
        // Floating panel chooser (item 7): only the chosen functions
        // show. Close and Expand are ALWAYS visible even when
        // unchecked, so the panel can never be configured into a
        // state with no way back: Close dismisses it, Expand opens
        // Home (and from there Settings).
        view.findViewById<View>(R.id.btn_configure).visibility =
            fnVisibility(FloatingPanelPrefs.FN_CONFIGURE)
        view.findViewById<View>(R.id.btn_play).visibility =
            fnVisibility(FloatingPanelPrefs.FN_PLAY)
        view.findViewById<View>(R.id.btn_target).visibility =
            fnVisibility(FloatingPanelPrefs.FN_TARGET)
        val params = overlayParams()
        val dragView = makeDraggable(view, params)
        try {
            windowManager.addView(dragView, params)
        } catch (e: Exception) {
            // addView used to throw out of onStartCommand uncaught
            // (permission revoked between check and add, or a bad
            // window token): the service died and no bar appeared
            // with no message at all. Fail with a toast instead.
            Toast.makeText(this, R.string.floating_mode_bar_failed, Toast.LENGTH_LONG).show()
            return
        }
        idleView = dragView
    }

    private fun showRunning() {
        removeIdleView()
        if (runningView != null) return
        if (!Settings.canDrawOverlays(this)) return
        val view = LayoutInflater.from(this).inflate(R.layout.floating_running, null)
        timerText = view.findViewById(R.id.run_text_timer)
        stepText = view.findViewById(R.id.run_text_step)
        pauseButton = view.findViewById(R.id.run_btn_pause)
        waitText = view.findViewById(R.id.run_text_wait)
        navRow = view.findViewById(R.id.run_row_nav)
        toolsRow = view.findViewById(R.id.run_row_tools)
        expandButton = view.findViewById(R.id.run_btn_expand)
        runningExpanded = true
        waitDurationMs = 0
        view.findViewById<View>(R.id.run_btn_stop).setOnClickListener {
            // Terminal: the run ends fully, no resume after stop.
            WorkflowRunner.stop()
        }
        pauseButton?.setOnClickListener {
            if (WorkflowRunner.isPaused()) {
                WorkflowRunner.resume()
                pauseButton?.setImageResource(R.drawable.ic_float_pause)
            } else {
                WorkflowRunner.pause()
                pauseButton?.setImageResource(R.drawable.ic_float_play)
            }
        }
        // Visual-only reference icons: no runner or recognition API
        // behind them exists yet, so each tap says so honestly
        // instead of faking an action or staying silently dead.
        fun soonToast(messageRes: Int): View.OnClickListener =
            View.OnClickListener {
                Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
            }
        view.findViewById<View>(R.id.run_btn_undo).setOnClickListener(
            soonToast(R.string.float_undo_soon)
        )
        view.findViewById<View>(R.id.run_btn_target).setOnClickListener(
            soonToast(R.string.float_target_soon)
        )
        view.findViewById<View>(R.id.run_btn_rocket).setOnClickListener(
            soonToast(R.string.float_rocket_soon)
        )
        view.findViewById<View>(R.id.run_btn_ocr).setOnClickListener(
            soonToast(R.string.float_ocr_soon)
        )
        view.findViewById<View>(R.id.run_btn_image).setOnClickListener(
            soonToast(R.string.float_image_soon)
        )
        view.findViewById<View>(R.id.run_btn_palette).setOnClickListener(
            soonToast(R.string.float_palette_soon)
        )
        expandButton?.setOnClickListener { toggleRunningExpanded() }
        view.findViewById<View>(R.id.run_btn_back).setOnClickListener {
            AutomationService.instance?.pressKey(NavKey.BACK)
        }
        view.findViewById<View>(R.id.run_btn_home).setOnClickListener {
            AutomationService.instance?.pressKey(NavKey.HOME)
        }
        view.findViewById<View>(R.id.run_btn_recents).setOnClickListener {
            AutomationService.instance?.pressKey(NavKey.RECENTS)
        }
        view.findViewById<View>(R.id.run_btn_more).setOnClickListener {
            val main = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(main)
        }
        // Floating panel chooser (item 7): only the chosen functions
        // show. Stop and More are ALWAYS visible even when unchecked:
        // a running workflow must always be stoppable, and More is
        // the way back to Home (and from there Settings).
        pauseButton?.visibility = fnVisibility(FloatingPanelPrefs.FN_PAUSE)
        view.findViewById<View>(R.id.run_btn_back).visibility =
            fnVisibility(FloatingPanelPrefs.FN_BACK)
        view.findViewById<View>(R.id.run_btn_home).visibility =
            fnVisibility(FloatingPanelPrefs.FN_HOME)
        view.findViewById<View>(R.id.run_btn_recents).visibility =
            fnVisibility(FloatingPanelPrefs.FN_RECENTS)
        val params = overlayParams()
        val dragView = makeDraggable(view, params)
        windowManager.addView(dragView, params)
        runningView = dragView
    }

    /**
     * Draggable floating panels: the inflated panel is wrapped in a
     * PanelDragLayout whose drag moves this panel's window params.
     * Button taps still click (the wrapper only intercepts past the
     * 10dp drag threshold), and the position is clamped to the screen
     * and persisted on drag end, so the next panel appears where the
     * user left the last one. Wrapping changes no ids, no click
     * listeners, and no show/hide order.
     */
    private fun makeDraggable(
        panel: View,
        params: WindowManager.LayoutParams
    ): View {
        val drag = PanelDragLayout(this)
        drag.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        drag.dragOrigin = { params.x to params.y }
        drag.onDragPosition = { newX, newY ->
            val metrics = resources.displayMetrics
            val maxX = (metrics.widthPixels - drag.measuredWidth).coerceAtLeast(0)
            val maxY = (metrics.heightPixels - drag.measuredHeight).coerceAtLeast(0)
            params.x = newX.coerceIn(0, maxX)
            params.y = newY.coerceIn(0, maxY)
            try {
                windowManager.updateViewLayout(drag, params)
            } catch (e: Exception) {
                // Panel already removed (e.g. run ended mid-drag).
            }
        }
        drag.onDragEnd = {
            FloatingPanelPrefs.savePosition(this, params.x, params.y)
        }
        return drag
    }

    /** Expand chevron: collapses rows 2-3 to a one-row bar, or brings
     * them back. Row 1 (stop / pause / timer) always stays visible,
     * so a collapsed panel can still stop the run. */
    private fun toggleRunningExpanded() {
        runningExpanded = !runningExpanded
        val visibility = if (runningExpanded) View.VISIBLE else View.GONE
        navRow?.visibility = visibility
        toolsRow?.visibility = visibility
        expandButton?.setImageResource(
            if (runningExpanded) R.drawable.ic_float_chevron_up
            else R.drawable.ic_float_chevron_down
        )
        expandButton?.contentDescription = getString(
            if (runningExpanded) R.string.float_desc_collapse
            else R.string.float_desc_expand
        )
    }

    /** Real "Ns" wait countdown, or hidden when no Wait step runs. */
    private fun updateWaitText() {
        val text = waitText ?: return
        if (waitDurationMs <= 0) {
            text.visibility = View.GONE
            return
        }
        val remaining = (waitDurationMs - (lastElapsedMs - waitStartElapsedMs))
            .coerceAtLeast(0L)
        text.text = "${(remaining + 999L) / 1000L}s"
        text.visibility = View.VISIBLE
    }

    private fun openEditor(id: String, pickTarget: Boolean) {
        // Never over the editor: drop the bar view before the editor
        // opens. The service itself keeps running, so the bar comes
        // back via showIdle when a run ends; the user can also
        // re-show it from the workflow card's Floating bar action.
        // (The running panel is NOT dropped by More: it opens Home,
        // not the editor, and a run must always stay stoppable.)
        removeIdleView()
        val editor = Intent(this, TaskEditorActivity::class.java).apply {
            putExtra("workflow_id", id)
            putExtra("pick_target", pickTarget)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(editor)
    }

    /** Floating panel chooser (item 7): chosen -> VISIBLE, else GONE. */
    private fun fnVisibility(fn: String): Int =
        if (FloatingPanelPrefs.isEnabled(this, fn)) View.VISIBLE else View.GONE

    private fun overlayParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Floating inset in dp (was a raw 40 px): the wrap-content
            // panel never stretches edge-to-edge, and both side/top
            // margins keep it visibly floating.
            val margin = (16 * resources.displayMetrics.density).toInt()
            x = margin
            this.y = margin
            // Last dragged position wins over the default inset, so a
            // panel re-shown after a run (or after Home CLOSE) returns
            // where the user left it. Clamped loosely here (the panel
            // is not measured yet); drag moves clamp tightly.
            FloatingPanelPrefs.getPosition(this@FloatingService)?.let { (savedX, savedY) ->
                val metrics = resources.displayMetrics
                x = savedX.coerceIn(0, metrics.widthPixels)
                this.y = savedY.coerceIn(0, metrics.heightPixels)
            }
        }
    }

    private fun removeIdleView() {
        idleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // View was already removed.
            }
        }
        idleView = null
    }

    private fun removeRunningView() {
        runningView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // View was already removed.
            }
        }
        runningView = null
        timerText = null
        stepText = null
        pauseButton = null
        waitText = null
        navRow = null
        toolsRow = null
        expandButton = null
        runningExpanded = true
        waitDurationMs = 0
    }

    private fun removeViews() {
        removeIdleView()
        removeRunningView()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OmeeEye running",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.floating_running))
            .setContentText(getString(R.string.floating_tap_to_open))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    /** Reference-style elapsed timer, mm:ss (minutes keep counting
     * past 60, so a long run still reads correctly). */
    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}
