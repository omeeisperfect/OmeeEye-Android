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
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.WorkflowStore
import com.nimu.omeeeye.model.Point
import com.nimu.omeeeye.model.StepType
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Gesture path viewer (item 6). Same OverlayPickService pattern:
 * foreground service + TYPE_APPLICATION_OVERLAY window over the real
 * screen, app task moved to the back. Draws the workflow's path in
 * step order: numbered dots for TAP points, arrows for SWIPE
 * start -> end, rectangles for AREA_CLICK regions. Every other step
 * type is skipped (it has no on-screen gesture to draw).
 *
 * Removal: the Close button always removes the overlay. System Back
 * also removes it: the top bar holds window focus (the draw view is
 * not clickable and the window is NOT_TOUCH_MODAL, so touches still
 * reach the screen beneath) and its key listener closes on Back.
 * Requires the overlay permission like other picks: without it the
 * launcher shows the existing overlay-permission guide instead of
 * drawing anything fake.
 */
class GesturePathService : Service() {

    companion object {
        const val EXTRA_WORKFLOW_ID = "gesture_workflow_id"

        private const val NOTIF_ID = 4
        private const val CHANNEL_ID = "omeeeye_gesture_path"

        /**
         * Starts the viewer. Returns false (and shows the overlay
         * permission guide) when the overlay permission is missing;
         * the caller must then do nothing else. On true, the caller
         * should move its own task to the back.
         */
        fun start(activity: android.app.Activity, workflowId: String): Boolean {
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
            val intent = Intent(activity, GesturePathService::class.java).apply {
                putExtra(EXTRA_WORKFLOW_ID, workflowId)
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

    private sealed class DrawItem(val number: Int) {
        class Dot(number: Int, val x: Int, val y: Int) : DrawItem(number)
        class Arrow(number: Int, val sx: Int, val sy: Int, val ex: Int, val ey: Int) :
            DrawItem(number)

        // A recorded finger path: drawn as its own polyline, never
        // collapsed to a straight start -> end arrow.
        class Path(number: Int, val points: List<Point>) : DrawItem(number)

        class Area(number: Int, val rect: Rect) : DrawItem(number)
    }

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
        removeViews()
        val workflowId = intent?.getStringExtra(EXTRA_WORKFLOW_ID) ?: ""
        val workflow = WorkflowStore.get(this, workflowId)
        val items = mutableListOf<DrawItem>()
        var number = 0
        workflow?.steps?.forEach { step ->
            when (step.type) {
                StepType.TAP -> {
                    val point = step.point
                    if (point != null) {
                        number++
                        items.add(DrawItem.Dot(number, point.x, point.y))
                    }
                }
                StepType.SWIPE -> {
                    val start = step.start
                    val end = step.end
                    if (start != null && end != null) {
                        number++
                        if (step.pathPoints.size >= 3) {
                            items.add(DrawItem.Path(number, step.pathPoints))
                        } else {
                            items.add(
                                DrawItem.Arrow(number, start.x, start.y, end.x, end.y)
                            )
                        }
                    }
                }
                StepType.AREA_CLICK -> {
                    if (step.regionLeft >= 0 && step.regionTop >= 0 &&
                        step.regionRight > step.regionLeft &&
                        step.regionBottom > step.regionTop
                    ) {
                        number++
                        items.add(
                            DrawItem.Area(
                                number,
                                Rect(
                                    step.regionLeft, step.regionTop,
                                    step.regionRight, step.regionBottom
                                )
                            )
                        )
                    }
                }
                else -> {
                    // Other step types have no gesture to draw: skipped.
                }
            }
        }
        if (items.isEmpty()) {
            // Never draw an empty overlay and call it a path.
            Toast.makeText(
                this, R.string.gesture_path_empty, Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return START_NOT_STICKY
        }
        showOverlay(items)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeViews()
        super.onDestroy()
    }

    private fun showOverlay(items: List<DrawItem>) {
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()

        val drawView = GestureDrawView(this, items)

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        // Not clickable: with NOT_TOUCH_MODAL, touches outside the
        // bar fall through to the real screen beneath.
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
            // Holds window focus so system Back reaches the key
            // listener below; the draw view stays non-focusable.
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(ContextCompat.getColor(context, R.color.overlay_bar))
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
        }
        bar.addView(
            TextView(this).apply {
                text = getString(R.string.gesture_path_title)
                setTextColor(ContextCompat.getColor(context, R.color.overlay_bar_text))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
        )
        bar.addView(
            Button(this).apply {
                text = getString(R.string.gesture_path_close)
                setOnClickListener { close() }
            }
        )
        bar.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK &&
                event.action == KeyEvent.ACTION_UP
            ) {
                close()
                true
            } else {
                false
            }
        }
        root.addView(bar)

        windowManager.addView(root, overlayParams())
        rootView = root
        bar.requestFocus()
    }

    private fun close() {
        removeViews()
        stopSelf()
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
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // NOT_TOUCH_MODAL (and deliberately NOT NOT_FOCUSABLE) so the
        // bar can hold focus for Back handling while touches outside
        // it still reach the screen beneath.
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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
                "OmeeEye gesture path",
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
            .setContentTitle(getString(R.string.gesture_path_title))
            .setContentText(getString(R.string.gesture_path_title))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    /** Numbered dots, swipe arrows and area rectangles, in step order. */
    private inner class GestureDrawView(
        context: Context,
        private val items: List<DrawItem>
    ) : View(context) {
        private val dotPaint = Paint().apply {
            color = ContextCompat.getColor(context, R.color.accent)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val ringPaint = Paint().apply {
            color = ContextCompat.getColor(context, R.color.overlay_bar_text)
            style = Paint.Style.STROKE
            strokeWidth = 2 * resources.displayMetrics.density
            isAntiAlias = true
        }
        private val linePaint = Paint().apply {
            color = ContextCompat.getColor(context, R.color.accent)
            strokeWidth = 4 * resources.displayMetrics.density
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        private val numberPaint = Paint().apply {
            color = ContextCompat.getColor(context, R.color.overlay_bar_text)
            textSize = 16 * resources.displayMetrics.density
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = 14 * resources.displayMetrics.density
            val arrowLen = 28 * resources.displayMetrics.density
            for (item in items) {
                when (item) {
                    is DrawItem.Dot -> {
                        canvas.drawCircle(
                            item.x.toFloat(), item.y.toFloat(), radius, dotPaint
                        )
                        canvas.drawCircle(
                            item.x.toFloat(), item.y.toFloat(), radius, ringPaint
                        )
                        canvas.drawText(
                            item.number.toString(),
                            item.x.toFloat(),
                            item.y.toFloat() - radius - 4 * resources.displayMetrics.density,
                            numberPaint
                        )
                    }
                    is DrawItem.Arrow -> {
                        canvas.drawLine(
                            item.sx.toFloat(), item.sy.toFloat(),
                            item.ex.toFloat(), item.ey.toFloat(), linePaint
                        )
                        // Arrowhead at the end point.
                        val angle = atan2(
                            (item.ey - item.sy).toDouble(),
                            (item.ex - item.sx).toDouble()
                        )
                        for (sign in listOf(PI / 6, -PI / 6)) {
                            val a = angle + PI + sign
                            canvas.drawLine(
                                item.ex.toFloat(), item.ey.toFloat(),
                                (item.ex + arrowLen * cos(a)).toFloat(),
                                (item.ey + arrowLen * sin(a)).toFloat(),
                                linePaint
                            )
                        }
                        canvas.drawCircle(
                            item.sx.toFloat(), item.sy.toFloat(), radius, dotPaint
                        )
                        canvas.drawCircle(
                            item.sx.toFloat(), item.sy.toFloat(), radius, ringPaint
                        )
                        canvas.drawText(
                            item.number.toString(),
                            item.sx.toFloat(),
                            item.sy.toFloat() - radius - 4 * resources.displayMetrics.density,
                            numberPaint
                        )
                    }
                    is DrawItem.Path -> {
                        val pts = item.points
                        for (i in 0 until pts.size - 1) {
                            canvas.drawLine(
                                pts[i].x.toFloat(), pts[i].y.toFloat(),
                                pts[i + 1].x.toFloat(), pts[i + 1].y.toFloat(),
                                linePaint
                            )
                        }
                        // Arrowhead on the last segment, like Arrow.
                        val last = pts.last()
                        val prev = pts[pts.size - 2]
                        val angle = atan2(
                            (last.y - prev.y).toDouble(),
                            (last.x - prev.x).toDouble()
                        )
                        for (sign in listOf(PI / 6, -PI / 6)) {
                            val a = angle + PI + sign
                            canvas.drawLine(
                                last.x.toFloat(), last.y.toFloat(),
                                (last.x + arrowLen * cos(a)).toFloat(),
                                (last.y + arrowLen * sin(a)).toFloat(),
                                linePaint
                            )
                        }
                        val first = pts.first()
                        canvas.drawCircle(
                            first.x.toFloat(), first.y.toFloat(), radius, dotPaint
                        )
                        canvas.drawCircle(
                            first.x.toFloat(), first.y.toFloat(), radius, ringPaint
                        )
                        canvas.drawText(
                            item.number.toString(),
                            first.x.toFloat(),
                            first.y.toFloat() - radius - 4 * resources.displayMetrics.density,
                            numberPaint
                        )
                    }
                    is DrawItem.Area -> {
                        val r = item.rect
                        canvas.drawRect(
                            r.left.toFloat(), r.top.toFloat(),
                            r.right.toFloat(), r.bottom.toFloat(), linePaint
                        )
                        canvas.drawText(
                            item.number.toString(),
                            r.left.toFloat() + 8 * resources.displayMetrics.density,
                            r.top.toFloat() + 20 * resources.displayMetrics.density,
                            numberPaint
                        )
                    }
                }
            }
        }
    }
}
