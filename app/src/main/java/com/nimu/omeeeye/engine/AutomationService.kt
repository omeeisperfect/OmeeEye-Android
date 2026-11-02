package com.nimu.omeeeye.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.nimu.omeeeye.model.NavKey
import com.nimu.omeeeye.model.Point
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Performs the physical actions (tap / swipe / type / keys / launch app)
 * for [WorkflowRunner]. This is the only place that talks to the
 * accessibility framework. minSdk is 24, so dispatchGesture needs no
 * version checks.
 *
 * The service is reached through [instance]; it is null when the user has
 * not enabled the service in Settings, and [WorkflowRunner] fails the run
 * in that case instead of crashing.
 */
class AutomationService : AccessibilityService() {

    companion object {
        var instance: AutomationService? = null
            private set

        private const val TAP_STROKE_MS = 100L
        private const val MIN_STROKE_MS = 100L
        private const val MAX_STROKE_MS = 5000L
        private const val GESTURE_TIMEOUT_MS = 2000L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event watching in base: the service only acts, it never reads
        // screen content except for finding the focused input (see typeText).
    }

    override fun onInterrupt() {
        // Nothing to clean up.
    }

    /**
     * Single-point tap. The stroke lasts ~100ms; the call blocks until the
     * gesture callback reports completion, or 2 seconds pass, whichever is
     * first. Returns true only if the gesture completed.
     */
    fun tap(point: Point): Boolean {
        val path = Path().apply {
            moveTo(point.x.toFloat(), point.y.toFloat())
        }
        return dispatchAndWait(path, TAP_STROKE_MS)
    }

    /**
     * Straight swipe from [start] to [end]. Duration is clamped to
     * 100..5000ms so a bad step can never hang the run.
     */
    fun swipe(start: Point, end: Point, durationMs: Long): Boolean {
        val clamped = durationMs.coerceIn(MIN_STROKE_MS, MAX_STROKE_MS)
        val path = Path().apply {
            moveTo(start.x.toFloat(), start.y.toFloat())
            lineTo(end.x.toFloat(), end.y.toFloat())
        }
        return dispatchAndWait(path, clamped)
    }

    /**
     * Types [text] into whichever input field is currently focused, via the
     * clipboard + paste. Returns false when there is no focused input node
     * (nothing to type into) or the paste itself fails.
     */
    fun typeText(text: String): Boolean {
        val clipboard =
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("omeeeye", text))
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } finally {
            node.recycle()
        }
    }

    /** Back / Home / Recents. */
    fun pressKey(key: NavKey): Boolean {
        val action = when (key) {
            NavKey.BACK -> GLOBAL_ACTION_BACK
            NavKey.HOME -> GLOBAL_ACTION_HOME
            NavKey.RECENTS -> GLOBAL_ACTION_RECENTS
        }
        return performGlobalAction(action)
    }

    /** Launches an installed app by package name. False when it has no launch intent. */
    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun dispatchAndWait(path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val completed = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            null
        )
        if (!dispatched) return false
        try {
            latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return completed.get()
    }
}
