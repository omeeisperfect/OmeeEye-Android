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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.WorkflowStore
import com.nimu.omeeeye.engine.AutomationService
import com.nimu.omeeeye.model.Point
import com.nimu.omeeeye.model.Step
import com.nimu.omeeeye.model.StepType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot

/**
 * Result store for overlay picks. The overlay runs as a service over
 * OTHER apps, so it cannot return an Activity result: it stores its
 * result here, brings the calling activity back to the front, and the
 * caller consumes the result in onResume(). A missing entry means the
 * pick is still in flight (or was never started); a "cancelled" entry
 * means the user aborted. Entries are consumed (removed) on take().
 */
object OverlayPickStore {
    const val CANCELLED_KIND = "cancelled"
    private val results = ConcurrentHashMap<String, String>()

    fun put(requestId: String, json: String) {
        results[requestId] = json
    }

    fun putCancelled(requestId: String) {
        results[requestId] = JSONObject().put("kind", CANCELLED_KIND).toString()
    }

    fun take(requestId: String): String? = results.remove(requestId)
}

/**
 * System-overlay picker/recorder. This replaces the old translucent
 * in-app activities (PickPointActivity, PickRegionActivity,
 * RecordActivity): an activity can only ever show the app's own screen,
 * so picking "on screen" from inside the app picked points on the app's
 * own editor instead of the user's home screen or another app.
 *
 * Flow: the caller starts this service, then moves its own task to the
 * back, so the user sees the real screen underneath with this overlay
 * on top of it. The overlay itself consumes the touches used for
 * picking/recording (the tap is never meant to reach the app below
 * during a pick — the recorded coordinates are replayed later by the
 * accessibility service). MotionEvent raw coordinates are already
 * absolute screen pixels, so no status-bar offset conversion is needed.
 *
 * Modes (intent extra EXTRA_MODE): "point", "swipe", "region", "record".
 * Region sub-modes (EXTRA_REGION_MODE): "region", "template", "color".
 * For "template"/"color" the overlay is hidden before the screenshot is
 * taken, so the drawn rectangle never corrupts the captured image.
 *
 * Requires "Display over other apps". When it is missing, the launcher
 * helpers below show a guide dialog that opens the system setting
 * instead of silently failing — picking is never faked.
 */
class OverlayPickService : Service() {

    companion object {
        const val EXTRA_MODE = "overlay_mode"
        const val EXTRA_REGION_MODE = "overlay_region_mode"
        const val EXTRA_HINT = "overlay_hint"
        const val EXTRA_REQUEST_ID = "overlay_request_id"
        const val EXTRA_RETURN_CLASS = "overlay_return_class"

        const val MODE_POINT = "point"
        const val MODE_SWIPE = "swipe"
        const val MODE_REGION = "region"
        const val MODE_RECORD = "record"

        private const val NOTIF_ID = 2
        private const val CHANNEL_ID = "omeeeye_pick"

        // Recording thresholds (single companion only: Kotlin allows one
        // companion object per class, and these are used from lambdas
        // throughout the service).
        const val TAP_MAX_DISTANCE_PX = 24f
        const val TAP_MAX_DURATION_MS = 600L
        const val MIN_SWIPE_DURATION_MS = 100L
        const val MAX_SWIPE_DURATION_MS = 5000L
        const val DEFAULT_WAIT_AFTER_MS = 2000L
        const val SCREENSHOT_DELAY_MS = 350L

        /**
         * Starts an overlay pick. Returns false (and shows a guide
         * dialog) when the overlay permission is missing; the caller
         * must then NOT treat a pick as in flight. On true, the caller
         * should move its own task to the back.
         */
        fun startPoint(activity: android.app.Activity, requestId: String, hint: String, returnClass: String): Boolean =
            start(activity, requestId, returnClass, MODE_POINT, "region", hint)

        fun startSwipe(activity: android.app.Activity, requestId: String, returnClass: String): Boolean =
            start(activity, requestId, returnClass, MODE_SWIPE, "region", "")

        fun startRegion(activity: android.app.Activity, requestId: String, regionMode: String, returnClass: String): Boolean =
            start(activity, requestId, returnClass, MODE_REGION, regionMode, "")

        fun startRecord(activity: android.app.Activity, requestId: String, returnClass: String): Boolean =
            start(activity, requestId, returnClass, MODE_RECORD, "region", "")

        private fun start(
            activity: android.app.Activity,
            requestId: String,
            returnClass: String,
            mode: String,
            regionMode: String,
            hint: String
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
            val intent = Intent(activity, OverlayPickService::class.java).apply {
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_REGION_MODE, regionMode)
                putExtra(EXTRA_HINT, hint)
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_RETURN_CLASS, returnClass)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(intent)
            } else {
                activity.startService(intent)
            }
            // Show the real screen underneath: move this app's task to
            // the back, leaving only the overlay window on top of it.
            activity.moveTaskToBack(true)
            return true
        }
    }

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var drawView: PickDrawView? = null
    private var hintView: TextView? = null
    private var countView: TextView? = null
    private var confirmButton: Button? = null

    private var mode: String = MODE_POINT
    private var regionMode: String = "region"
    private var requestId: String = ""
    private var returnClassName: String = ""

    // Swipe state: phase 0 = picking A, 1 = picking B, 2 = confirm.
    private var swipePhase: Int = 0
    private var swipeStart: Point? = null
    private var swipeEnd: Point? = null

    // Region drag state, absolute screen pixels.
    private var dragStartX: Int = 0
    private var dragStartY: Int = 0
    private var dragCurrentX: Int = 0
    private var dragCurrentY: Int = 0
    private var dragging: Boolean = false

    // Record state (same thresholds as the old RecordActivity).
    private val recorded = mutableListOf<Step>()
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downTimeMs: Long = 0L
    private var maxDistancePx: Float = 0f
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var tracking: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newMode = intent?.getStringExtra(EXTRA_MODE) ?: return START_NOT_STICKY
        val newRequestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return START_NOT_STICKY
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_off, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        // A previous pick is still up: cancel it cleanly before starting
        // the new one, so its caller never waits on a stale request.
        if (rootView != null && requestId.isNotBlank()) {
            OverlayPickStore.putCancelled(requestId)
            removeViews()
        }
        mode = newMode
        regionMode = intent.getStringExtra(EXTRA_REGION_MODE) ?: "region"
        requestId = newRequestId
        returnClassName = intent.getStringExtra(EXTRA_RETURN_CLASS) ?: ""
        resetState()
        showOverlay(intent.getStringExtra(EXTRA_HINT) ?: "")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeViews()
        super.onDestroy()
    }

    private fun resetState() {
        swipePhase = 0
        swipeStart = null
        swipeEnd = null
        dragging = false
        recorded.clear()
        tracking = false
    }

    private fun showOverlay(hint: String) {
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()

        drawView = PickDrawView(this)

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(drawView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Top control bar: hint + (record count) + (swipe confirm) +
        // (record stop) + cancel. The bar consumes its own touches, so
        // taps on it never become picks.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Clickable so taps on the bar itself never fall through to
            // the draw view below and become accidental picks.
            isClickable = true
            setBackgroundColor(ContextCompat.getColor(context, R.color.overlay_bar))
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
        }
        hintView = TextView(this).apply {
            text = when (mode) {
                MODE_SWIPE -> getString(R.string.pick_start_hint)
                MODE_RECORD -> getString(R.string.overlay_record_hint)
                MODE_REGION -> when (regionMode) {
                    "template" -> getString(R.string.rec_pick_template_hint)
                    "color" -> getString(R.string.rec_pick_color_hint)
                    else -> getString(R.string.rec_pick_region_hint)
                }
                else -> hint.ifBlank { getString(R.string.pick_hint) }
            }
            setTextColor(ContextCompat.getColor(context, R.color.overlay_bar_text))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(hintView)

        countView = TextView(this).apply {
            text = getString(R.string.overlay_record_count, 0)
            setTextColor(ContextCompat.getColor(context, R.color.overlay_bar_text))
            textSize = 15f
            visibility = if (mode == MODE_RECORD) View.VISIBLE else View.GONE
            setPadding(pad, 0, pad, 0)
        }
        bar.addView(countView)

        confirmButton = Button(this).apply {
            text = getString(R.string.overlay_pick_confirm)
            visibility = View.GONE
            setOnClickListener { finishSwipe() }
        }
        bar.addView(confirmButton)

        if (mode == MODE_RECORD) {
            val stop = Button(this).apply {
                text = getString(R.string.overlay_record_stop)
                setOnClickListener { finishRecord() }
            }
            bar.addView(stop)
        }
        val cancel = Button(this).apply {
            text = getString(R.string.cancel)
            setOnClickListener { finishCancelled() }
        }
        bar.addView(cancel)
        root.addView(bar)

        drawView?.setOnTouchListener { _, event ->
            val x = event.rawX.toInt()
            val y = event.rawY.toInt()
            when (mode) {
                MODE_POINT -> {
                    if (event.action == MotionEvent.ACTION_UP) finishPoint(x, y)
                    true
                }
                MODE_SWIPE -> {
                    if (event.action == MotionEvent.ACTION_UP) onSwipeTap(x, y)
                    true
                }
                MODE_REGION -> {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            dragStartX = x
                            dragStartY = y
                            dragCurrentX = x
                            dragCurrentY = y
                            dragging = true
                            drawView?.setRect(null)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!dragging) return@setOnTouchListener false
                            dragCurrentX = x
                            dragCurrentY = y
                            drawView?.setRect(normalisedRect())
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!dragging) return@setOnTouchListener false
                            dragging = false
                            dragCurrentX = x
                            dragCurrentY = y
                            val rect = normalisedRect()
                            drawView?.setRect(rect)
                            if (rect.width() < 10 || rect.height() < 10) {
                                drawView?.setRect(null)
                                Toast.makeText(
                                    this,
                                    R.string.rec_region_too_small,
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                finishRegion(rect)
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            dragging = false
                            drawView?.setRect(null)
                            true
                        }
                        else -> false
                    }
                }
                MODE_RECORD -> {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX
                            downY = event.rawY
                            lastX = downX
                            lastY = downY
                            downTimeMs = event.eventTime
                            maxDistancePx = 0f
                            tracking = true
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (tracking) {
                                lastX = event.rawX
                                lastY = event.rawY
                                val d = hypot(lastX - downX, lastY - downY)
                                if (d > maxDistancePx) maxDistancePx = d
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (tracking) {
                                tracking = false
                                val durationMs = event.eventTime - downTimeMs
                                val upX = event.rawX.toInt()
                                val upY = event.rawY.toInt()
                                if (maxDistancePx < TAP_MAX_DISTANCE_PX &&
                                    durationMs < TAP_MAX_DURATION_MS
                                ) {
                                    recorded.add(
                                        Step(
                                            id = WorkflowStore.newId(),
                                            type = StepType.TAP,
                                            point = Point(upX, upY),
                                            waitAfterMs = DEFAULT_WAIT_AFTER_MS
                                        )
                                    )
                                    drawView?.addDot(upX, upY)
                                } else {
                                    recorded.add(
                                        Step(
                                            id = WorkflowStore.newId(),
                                            type = StepType.SWIPE,
                                            start = Point(downX.toInt(), downY.toInt()),
                                            end = Point(upX, upY),
                                            durationMs = durationMs.coerceIn(
                                                MIN_SWIPE_DURATION_MS,
                                                MAX_SWIPE_DURATION_MS
                                            ),
                                            waitAfterMs = DEFAULT_WAIT_AFTER_MS
                                        )
                                    )
                                    drawView?.addDot(downX.toInt(), downY.toInt())
                                    drawView?.addDot(upX, upY)
                                }
                                countView?.text = getString(
                                    R.string.overlay_record_count, recorded.size
                                )
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            tracking = false
                            true
                        }
                        else -> false
                    }
                }
                else -> false
            }
        }

        windowManager.addView(root, overlayParams())
        rootView = root
    }

    private fun normalisedRect(): Rect = Rect(
        minOf(dragStartX, dragCurrentX),
        minOf(dragStartY, dragCurrentY),
        maxOf(dragStartX, dragCurrentX),
        maxOf(dragStartY, dragCurrentY)
    )

    private fun onSwipeTap(x: Int, y: Int) {
        when (swipePhase) {
            0 -> {
                swipeStart = Point(x, y)
                drawView?.setDots(listOf(Point(x, y)))
                hintView?.text = getString(R.string.pick_end_hint)
                swipePhase = 1
            }
            1 -> {
                swipeEnd = Point(x, y)
                val start = swipeStart
                if (start != null) {
                    drawView?.setDots(listOf(start, Point(x, y)))
                }
                hintView?.text = getString(R.string.overlay_swipe_confirm_hint)
                confirmButton?.visibility = View.VISIBLE
                swipePhase = 2
            }
            else -> {
                // Re-tap to move the end point before confirming.
                swipeEnd = Point(x, y)
                val start = swipeStart
                if (start != null) {
                    drawView?.setDots(listOf(start, Point(x, y)))
                }
            }
        }
    }

    private fun finishSwipe() {
        val start = swipeStart
        val end = swipeEnd
        if (start == null || end == null) {
            finishCancelled()
            return
        }
        val json = JSONObject().apply {
            put("kind", "swipe")
            put("startX", start.x)
            put("startY", start.y)
            put("endX", end.x)
            put("endY", end.y)
        }.toString()
        finishWith(json)
    }

    private fun finishPoint(x: Int, y: Int) {
        val json = JSONObject().apply {
            put("kind", "point")
            put("x", x)
            put("y", y)
        }.toString()
        finishWith(json)
    }

    private fun finishRegion(rect: Rect) {
        when (regionMode) {
            "template", "color" -> finishRegionWithScreenshot(rect)
            else -> {
                val json = JSONObject().apply {
                    put("kind", "region")
                    put("left", rect.left)
                    put("top", rect.top)
                    put("right", rect.right)
                    put("bottom", rect.bottom)
                }.toString()
                finishWith(json)
            }
        }
    }

    private fun finishRegionWithScreenshot(rect: Rect) {
        val service = AutomationService.instance
        if (service == null) {
            Toast.makeText(this, R.string.rec_capture_no_service, Toast.LENGTH_LONG).show()
            finishCancelled()
            return
        }
        // Hide the overlay first so the drawn rectangle and bar never
        // appear in the captured screenshot, then capture after a delay.
        removeViews()
        mainHandler.postDelayed({
            Thread {
                val shot = try {
                    service.takeScreenshotBitmap()
                } catch (e: Exception) {
                    null
                }
                mainHandler.post {
                    if (shot == null) {
                        // Surface the real screenshot reason (e.g. the
                        // API error code) instead of only "failed".
                        val detail = service.lastScreenshotError
                        val text = if (detail != null) {
                            "${getString(R.string.rec_capture_failed)}: $detail"
                        } else {
                            getString(R.string.rec_capture_failed)
                        }
                        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
                        finishCancelled()
                        return@post
                    }
                    try {
                        if (regionMode == "template") {
                            saveTemplateAndFinish(rect, shot)
                        } else {
                            sampleColorAndFinish(rect, shot)
                        }
                    } catch (e: Exception) {
                        Toast.makeText(
                            this, R.string.rec_capture_failed, Toast.LENGTH_LONG
                        ).show()
                        finishCancelled()
                    } finally {
                        try {
                            shot.recycle()
                        } catch (e: Exception) {
                            // Ignore recycle failures.
                        }
                    }
                }
            }.start()
        }, SCREENSHOT_DELAY_MS)
    }

    private fun saveTemplateAndFinish(rect: Rect, shot: android.graphics.Bitmap) {
        val left = rect.left.coerceIn(0, shot.width - 1)
        val top = rect.top.coerceIn(0, shot.height - 1)
        val right = rect.right.coerceIn(left + 1, shot.width)
        val bottom = rect.bottom.coerceIn(top + 1, shot.height)
        if (right <= left || bottom <= top) {
            Toast.makeText(this, R.string.rec_capture_failed, Toast.LENGTH_LONG).show()
            finishCancelled()
            return
        }
        val cropped = android.graphics.Bitmap.createBitmap(
            shot, left, top, right - left, bottom - top
        )
        try {
            val dir = File(filesDir, "templates").apply { mkdirs() }
            val fileName = UUID.randomUUID().toString() + ".png"
            val out = File(dir, fileName)
            FileOutputStream(out).use { stream ->
                if (!cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IllegalStateException("Template could not be saved")
                }
            }
            val json = JSONObject().apply {
                put("kind", "region")
                put("left", rect.left)
                put("top", rect.top)
                put("right", rect.right)
                put("bottom", rect.bottom)
                put("templatePath", "templates/$fileName")
            }.toString()
            finishWith(json)
        } finally {
            try {
                cropped.recycle()
            } catch (e: Exception) {
                // Ignore recycle failures.
            }
        }
    }

    private fun sampleColorAndFinish(rect: Rect, shot: android.graphics.Bitmap) {
        val cx = ((rect.left + rect.right) / 2).coerceIn(0, shot.width - 1)
        val cy = ((rect.top + rect.bottom) / 2).coerceIn(0, shot.height - 1)
        val colorValue = shot.getPixel(cx, cy)
        val json = JSONObject().apply {
            put("kind", "region")
            put("left", rect.left)
            put("top", rect.top)
            put("right", rect.right)
            put("bottom", rect.bottom)
            put("colorValue", colorValue)
        }.toString()
        finishWith(json)
    }

    private fun finishRecord() {
        val arr = JSONArray()
        recorded.forEach { arr.put(WorkflowStore.stepToJson(it)) }
        val json = JSONObject().apply {
            put("kind", "record")
            put("steps_json", arr.toString())
        }.toString()
        finishWith(json)
    }

    private fun finishCancelled() {
        finishWith(null)
    }

    private fun finishWith(json: String?) {
        if (requestId.isNotBlank()) {
            if (json != null) OverlayPickStore.put(requestId, json)
            else OverlayPickStore.putCancelled(requestId)
        }
        val returnName = returnClassName
        removeViews()
        stopSelf()
        if (returnName.isNotBlank()) {
            try {
                val cls = Class.forName(returnName)
                // CLEAR_TOP + SINGLE_TOP guarantee the existing editor
                // instance is reused (onNewIntent + onResume) instead of
                // depending on REORDER_TO_FRONT alone to find it; the
                // editor then consumes the stored result in onResume().
                val intent = Intent(this, cls).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                startActivity(intent)
            } catch (e: Exception) {
                // The caller is gone (e.g. Android killed the task while
                // picking): the stored result simply goes unconsumed.
                val main = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(main)
                } catch (ignored: Exception) {
                    // Nothing left to bring forward.
                }
            }
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
        hintView = null
        countView = null
        confirmButton = null
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
                "OmeeEye pick",
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
            .setContentTitle(getString(R.string.overlay_pick_title))
            .setContentText(getString(R.string.pick_hint))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    /** Draws the drag rectangle and the pick/record dots, nothing else. */
    private inner class PickDrawView(context: Context) : View(context) {
        private var rect: Rect? = null
        private var dots: List<Point> = emptyList()
        private val fillPaint = Paint().apply {
            color = ContextCompat.getColor(context, R.color.accent)
            alpha = 60
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint().apply {
            color = ContextCompat.getColor(context, R.color.accent)
            strokeWidth = 3 * resources.displayMetrics.density
            style = Paint.Style.STROKE
        }
        private val dotPaint = Paint().apply {
            color = ContextCompat.getColor(context, R.color.accent)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val dotRingPaint = Paint().apply {
            color = ContextCompat.getColor(context, R.color.overlay_bar_text)
            style = Paint.Style.STROKE
            strokeWidth = 2 * resources.displayMetrics.density
            isAntiAlias = true
        }

        fun setRect(value: Rect?) {
            rect = value
            invalidate()
        }

        fun setDots(value: List<Point>) {
            dots = value
            invalidate()
        }

        fun addDot(x: Int, y: Int) {
            dots = dots + Point(x, y)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = rect
            if (r != null) {
                canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), fillPaint)
                canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), strokePaint)
            }
            val radius = 14 * resources.displayMetrics.density
            for (dot in dots) {
                canvas.drawCircle(dot.x.toFloat(), dot.y.toFloat(), radius, dotPaint)
                canvas.drawCircle(dot.x.toFloat(), dot.y.toFloat(), radius, dotRingPaint)
            }
        }
    }

}
