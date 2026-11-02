package com.nimu.omeeeye.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.nimu.omeeeye.R
import com.nimu.omeeeye.engine.AutomationService

/**
 * Result store for button capture (item 3). Same shape as
 * OverlayPickStore: the overlay service cannot return an Activity
 * result, so it stores the captured button here, brings the editor
 * back, and RecognitionEditActivity consumes it in onResume(). A null
 * result (cancel, no service, no nodes) means "change nothing".
 */
object ButtonCaptureStore {
    data class CapturedButton(
        val text: String,
        val viewId: String,
        val description: String
    )

    @Volatile
    private var captured: CapturedButton? = null

    @Synchronized
    fun put(text: String, viewId: String, description: String) {
        captured = CapturedButton(text, viewId, description)
    }

    /** Consumes (clears) the stored capture, or null when there is none. */
    @Synchronized
    fun take(): CapturedButton? {
        val result = captured
        captured = null
        return result
    }
}

/**
 * Button capture mode ("All buttons", item 3). Copies
 * OverlayPickService's foreground-service + TYPE_APPLICATION_OVERLAY
 * window + return-to-caller pattern: the caller moves its own task to
 * the back, so the real screen shows underneath this overlay.
 *
 * The service reads AutomationService.instance?.rootInActiveWindow and
 * collects clickable nodes with their text, view id, content
 * description and screen bounds, drawing a red outline over each.
 * Initially only nodes with non-empty text are shown; "Show more"
 * reveals nodes that have only an id / description. Tapping an
 * outlined node stores it in ButtonCaptureStore and returns to the
 * editor. Cancel returns without storing anything.
 *
 * Honest failures, never invented buttons: accessibility service off
 * or no clickable nodes -> toast and return with nothing stored.
 */
class ButtonCaptureService : Service() {

    companion object {
        const val EXTRA_RETURN_CLASS = "button_capture_return_class"

        private const val NOTIF_ID = 3
        private const val CHANNEL_ID = "omeeeye_button_capture"
        private const val RETRY_DELAY_MS = 600L

        /**
         * Starts a capture. Returns false (and shows the overlay
         * permission guide) when the overlay permission is missing;
         * the caller must then NOT treat a capture as in flight. On
         * true, the caller should move its own task to the back.
         */
        fun startCapture(
            activity: android.app.Activity,
            returnClass: String
        ): Boolean {
            if (!Settings.canDrawOverlays(activity)) {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.overlay_pick_title)
                    .setMessage(R.string.overlay_pick_permission)
                    .setPositiveButton(R.string.overlay_pick_open_settings) { _, _ ->
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
            val intent = Intent(activity, ButtonCaptureService::class.java).apply {
                putExtra(EXTRA_RETURN_CLASS, returnClass)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(intent)
            } else {
                activity.startService(intent)
            }
            activity.moveTaskToBack(true)
            return true
        }
    }

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var drawView: CaptureDrawView? = null
    private var showMoreButton: Button? = null
    private var returnClassName: String = ""
    private var showAll: Boolean = false
    private var retried: Boolean = false

    /** All collected nodes (text, id, description, bounds). */
    private var nodes: List<CapturedNode> = emptyList()

    private data class CapturedNode(
        val text: String,
        val viewId: String,
        val description: String,
        val bounds: Rect
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_off, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        returnClassName = intent?.getStringExtra(EXTRA_RETURN_CLASS) ?: ""
        removeViews()
        showAll = false
        retried = false
        collectAndShow()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeViews()
        super.onDestroy()
    }

    private fun collectAndShow() {
        val service = AutomationService.instance
        if (service == null) {
            Toast.makeText(
                this, R.string.button_capture_no_service, Toast.LENGTH_LONG
            ).show()
            finishWithoutCapture()
            return
        }
        val root = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            null
        }
        if (root == null) {
            // The foreground window may not be published yet right
            // after the task move: one delayed retry, then give up
            // honestly instead of showing an empty overlay.
            if (!retried) {
                retried = true
                mainHandler.postDelayed({ collectAndShow() }, RETRY_DELAY_MS)
            } else {
                Toast.makeText(
                    this, R.string.button_capture_no_service, Toast.LENGTH_LONG
                ).show()
                finishWithoutCapture()
            }
            return
        }
        try {
            val collected = mutableListOf<CapturedNode>()
            visitNode(root, collected)
            nodes = collected
        } finally {
            try {
                root.recycle()
            } catch (e: Exception) {
                // Ignore recycle failures.
            }
        }
        if (nodes.isEmpty()) {
            Toast.makeText(
                this, R.string.button_capture_none_found, Toast.LENGTH_LONG
            ).show()
            finishWithoutCapture()
            return
        }
        showOverlay()
    }

    /**
     * Depth-first collection. A node is kept only when it is clickable,
     * has a non-empty on-screen rect, and carries at least one of text,
     * view id or content description — a node with none of the three
     * cannot identify a button, so keeping it would only draw a
     * rectangle the editor could never use. Each visited node is
     * recycled exactly once by its parent loop (root by the caller).
     */
    private fun visitNode(
        node: AccessibilityNodeInfo,
        out: MutableList<CapturedNode>
    ) {
        try {
            if (node.isClickable) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val text = node.text?.toString() ?: ""
                val viewId = node.viewIdResourceName ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                if (bounds.width() > 0 && bounds.height() > 0 &&
                    (text.isNotEmpty() || viewId.isNotEmpty() || desc.isNotEmpty())
                ) {
                    out.add(CapturedNode(text, viewId, desc, bounds))
                }
            }
        } catch (e: Exception) {
            // One unreadable node must not lose the whole capture.
        }
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            try {
                visitNode(child, out)
            } catch (e: Exception) {
                // Skip a broken subtree, keep the rest.
            } finally {
                try {
                    child.recycle()
                } catch (e: Exception) {
                    // Ignore recycle failures.
                }
            }
        }
    }

    /** Nodes currently drawn: text nodes first, plus id/desc-only on Show more. */
    private fun visibleNodes(): List<CapturedNode> =
        if (showAll) nodes else nodes.filter { it.text.isNotEmpty() }

    private fun showOverlay() {
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()

        drawView = CaptureDrawView(this)

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(
            drawView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setBackgroundColor(ContextCompat.getColor(context, R.color.overlay_bar))
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
        }
        bar.addView(
            TextView(this).apply {
                text = getString(R.string.button_capture_hint)
                setTextColor(ContextCompat.getColor(context, R.color.overlay_bar_text))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
        )
        // "Show more" only exists when there is something more to show.
        if (nodes.any { it.text.isEmpty() }) {
            showMoreButton = Button(this).apply {
                text = getString(R.string.button_capture_show_more)
                setOnClickListener {
                    showAll = !showAll
                    text = getString(
                        if (showAll) R.string.button_capture_show_less
                        else R.string.button_capture_show_more
                    )
                    drawView?.setNodes(visibleNodes())
                }
            }
            bar.addView(showMoreButton)
        }
        bar.addView(
            Button(this).apply {
                text = getString(R.string.cancel)
                setOnClickListener { finishWithoutCapture() }
            }
        )
        root.addView(bar)

        drawView?.setNodes(visibleNodes())
        drawView?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val x = event.rawX.toInt()
                val y = event.rawY.toInt()
                // Last drawn = topmost: later nodes in traversal order
                // are deeper / later in the tree and usually on top.
                val hit = visibleNodes().asReversed()
                    .firstOrNull { it.bounds.contains(x, y) }
                if (hit != null) {
                    ButtonCaptureStore.put(hit.text, hit.viewId, hit.description)
                    Toast.makeText(
                        this, R.string.button_capture_captured, Toast.LENGTH_SHORT
                    ).show()
                    finishWithCapture()
                }
            }
            true
        }

        windowManager.addView(root, overlayParams())
        rootView = root
    }

    private fun finishWithCapture() {
        finishAndReturn()
    }

    private fun finishWithoutCapture() {
        // Nothing stored: the editor's onResume take() returns null
        // and leaves its fields exactly as they were.
        finishAndReturn()
    }

    private fun finishAndReturn() {
        val returnName = returnClassName
        removeViews()
        stopSelf()
        val intent = try {
            if (returnName.isNotBlank()) {
                Intent(this, Class.forName(returnName))
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } ?: Intent(this, MainActivity::class.java)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Caller is gone: the stored capture simply goes unconsumed.
        }
    }

    private fun removeViews() {
        rootView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // View was already removed.
            }
        }
        rootView = null
        drawView = null
        showMoreButton = null
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OmeeEye button capture",
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
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.button_capture_title))
            .setContentText(getString(R.string.button_capture_hint))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    /** Red outline rects over the visible clickable nodes, nothing else. */
    private inner class CaptureDrawView(context: Context) : View(context) {
        private var shown: List<CapturedNode> = emptyList()
        private val strokePaint = Paint().apply {
            color = android.graphics.Color.RED
            strokeWidth = 3 * resources.displayMetrics.density
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        fun setNodes(value: List<CapturedNode>) {
            shown = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (node in shown) {
                val b = node.bounds
                canvas.drawRect(
                    b.left.toFloat(), b.top.toFloat(),
                    b.right.toFloat(), b.bottom.toFloat(), strokePaint
                )
            }
        }
    }
}
