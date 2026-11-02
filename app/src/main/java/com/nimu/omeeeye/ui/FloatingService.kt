package com.nimu.omeeeye.ui

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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.WorkflowStore
import com.nimu.omeeeye.engine.AutomationService
import com.nimu.omeeeye.engine.WorkflowRunner
import com.nimu.omeeeye.model.NavKey
import com.nimu.omeeeye.model.Step

/**
 * Hosts the two floating overlays. Requires "Display over other apps".
 *
 * Idle bar (bound to one workflow): configure, play, target picker, close, expand.
 * Running panel: stop, pause, timer, step count, Back/Home/Recents, more.
 * Stopping a run ends it fully — there is no resume after stop (pause is
 * separate and only holds the run between steps).
 */
class FloatingService : Service() {

    companion object {
        const val ACTION_IDLE = "idle"
        const val ACTION_RUNNING = "running"
        const val EXTRA_ACTION = "action"
        const val EXTRA_WORKFLOW_ID = "workflow_id"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "omeeeye_running"

        fun startIdle(context: Context, workflowId: String) {
            start(context, ACTION_IDLE, workflowId)
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

    private val runnerListener = object : WorkflowRunner.Listener {
        override fun onStepChanged(index: Int, total: Int, step: Step) {
            stepText?.text = getString(R.string.step_format, index + 1, total)
        }

        override fun onTick(elapsedMs: Long) {
            timerText?.text = formatTime(elapsedMs)
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_IDLE -> {
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
        removeRunningView()
        if (idleView != null) return
        if (!Settings.canDrawOverlays(this)) return
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
        windowManager.addView(view, overlayParams(y = 300))
        idleView = view
    }

    private fun showRunning() {
        removeIdleView()
        if (runningView != null) return
        if (!Settings.canDrawOverlays(this)) return
        val view = LayoutInflater.from(this).inflate(R.layout.floating_running, null)
        timerText = view.findViewById(R.id.run_text_timer)
        stepText = view.findViewById(R.id.run_text_step)
        pauseButton = view.findViewById(R.id.run_btn_pause)
        view.findViewById<View>(R.id.run_btn_stop).setOnClickListener {
            // Terminal: the run ends fully, no resume after stop.
            WorkflowRunner.stop()
        }
        pauseButton?.setOnClickListener {
            if (WorkflowRunner.isPaused()) {
                WorkflowRunner.resume()
                pauseButton?.setImageResource(android.R.drawable.ic_media_pause)
            } else {
                WorkflowRunner.pause()
                pauseButton?.setImageResource(android.R.drawable.ic_media_play)
            }
        }
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
        windowManager.addView(view, overlayParams(y = 300))
        runningView = view
    }

    private fun openEditor(id: String, pickTarget: Boolean) {
        val editor = Intent(this, TaskEditorActivity::class.java).apply {
            putExtra("workflow_id", id)
            putExtra("pick_target", pickTarget)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(editor)
    }

    private fun overlayParams(y: Int): WindowManager.LayoutParams {
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
            x = 40
            this.y = y
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

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
}
