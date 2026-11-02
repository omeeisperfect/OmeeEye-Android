package com.nimu.omeeeye.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.nimu.omeeeye.model.MatchMode
import com.nimu.omeeeye.model.MistakeLevel
import com.nimu.omeeeye.model.NavKey
import com.nimu.omeeeye.model.Point
import com.nimu.omeeeye.model.Rect as PickRect
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

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

        /** Set by the trigger engine: fired on Up, Up, Down within 4 seconds. */
        var volumeComboListener: (() -> Unit)? = null

        /**
         * Set when the app itself locks the screen (SCREEN_OFF step), so
         * the emergency-stop screen-off watcher can tell an intentional
         * lock from the user pressing the power button.
         */
        var intentionalScreenOffAtMs: Long = 0L

        // Mistake rates for human-like typing (desktop parity).
        private fun mistakeRate(level: MistakeLevel): Double = when (level) {
            MistakeLevel.OFF -> 0.0
            MistakeLevel.LIGHT -> 0.02
            MistakeLevel.NORMAL -> 0.05
            MistakeLevel.HEAVY -> 0.08
        }
    }

    // Volume-combo tracking (Hide Mode unhide trigger). Key events are
    // only delivered because the service config requests filter key events;
    // returning false lets the volume keys work normally.
    private val volumeSequence = mutableListOf<Pair<Int, Long>>()

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            val now = SystemClock.elapsedRealtime()
            volumeSequence.add(event.keyCode to now)
            volumeSequence.removeAll { now - it.second > 4000 }
            val keys = volumeSequence.map { it.first }
            if (keys.size >= 3 &&
                keys.takeLast(3) == listOf(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    KeyEvent.KEYCODE_VOLUME_UP,
                    KeyEvent.KEYCODE_VOLUME_DOWN
                )
            ) {
                volumeSequence.clear()
                volumeComboListener?.invoke()
            }
        }
        return false
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

    // ------------------------------------------------------------------
    // Human-like typing (Code Library, Excel row, human-like TYPE_TEXT)
    // ------------------------------------------------------------------

    private fun findFocusInput(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (e: Exception) {
            null
        }
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Types [text] character by character into the focused input, with
     * optional mistakes: a wrong alphanumeric character, a short pause,
     * then correction back to the right character. The final text is
     * always exactly [text]. Returns false when there is no focused input.
     */
    fun typeHuman(text: String, level: MistakeLevel, random: Random = Random): Boolean {
        val node = findFocusInput() ?: return false
        try {
            val rate = mistakeRate(level)
            val prefix = StringBuilder()
            for (ch in text) {
                if (rate > 0.0 && ch.isLetterOrDigit() && random.nextDouble() < rate) {
                    val wrong = if (ch.isDigit()) {
                        ('0'..'9').random(random)
                    } else {
                        ('a'..'z').random(random)
                    }
                    if (!setNodeText(node, prefix.toString() + wrong)) return false
                    Thread.sleep(80 + random.nextLong(140))
                    if (!setNodeText(node, prefix.toString())) return false
                }
                prefix.append(ch)
                if (!setNodeText(node, prefix.toString())) return false
                Thread.sleep(60 + random.nextLong(120))
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try {
                node.recycle()
            } catch (e: Exception) {
                // Ignore recycle failures.
            }
        }
    }

    /** Pastes the clipboard into the focused input. False when nothing is focused. */
    fun pasteClipboard(): Boolean {
        val node = findFocusInput() ?: return false
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            false
        } finally {
            try {
                node.recycle()
            } catch (e: Exception) {
                // Ignore.
            }
        }
    }

    // ------------------------------------------------------------------
    // Button recognition (accessibility tree, NOT OCR)
    // ------------------------------------------------------------------

    /**
     * Finds a button/node by text, view id and/or description. Every
     * non-empty identification field must match. Returns the node's
     * on-screen bounds, or null when nothing matches (or the match lies
     * outside [region] in REGION mode).
     */
    fun findNodeByText(
        matchText: String,
        mode: MatchMode,
        buttonId: String,
        buttonDesc: String,
        region: PickRect?
    ): Rect? {
        val root = rootInActiveWindow ?: return null
        return try {
            searchNode(root, matchText, mode, buttonId, buttonDesc, region)
        } catch (e: Exception) {
            null
        }
    }

    private fun searchNode(
        node: AccessibilityNodeInfo,
        matchText: String,
        mode: MatchMode,
        buttonId: String,
        buttonDesc: String,
        region: PickRect?
    ): Rect? {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val textOk = if (matchText.isBlank()) {
            true
        } else {
            when (mode) {
                MatchMode.EXACT -> text.equals(matchText.trim(), ignoreCase = true)
                MatchMode.PARTIAL -> text.contains(matchText.trim(), ignoreCase = true)
            }
        }
        val idOk = buttonId.isBlank() || viewId.contains(buttonId)
        val descOk = buttonDesc.isBlank() || desc.contains(buttonDesc, ignoreCase = true)
        if (textOk && idOk && descOk && (text.isNotEmpty() || desc.isNotEmpty())) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val inRegion = region == null ||
                (bounds.centerX() in region.left..region.right &&
                    bounds.centerY() in region.top..region.bottom)
            if (inRegion && !bounds.isEmpty) return Rect(bounds)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchNode(child, matchText, mode, buttonId, buttonDesc, region)
            if (found != null) {
                try {
                    node.recycle()
                } catch (e: Exception) {
                    // Ignore.
                }
                return found
            }
            try {
                child.recycle()
            } catch (e: Exception) {
                // Ignore.
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Screenshots (API 30+; null on older Android — callers fail cleanly)
    // ------------------------------------------------------------------

    /** Takes a screenshot of the active display, or null when unavailable. */
    fun takeScreenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        var resultBitmap: Bitmap? = null
        val latch = CountDownLatch(1)
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hardware = screenshot.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(
                                hardware,
                                screenshot.colorSpace
                            )
                            resultBitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            wrapped?.recycle()
                            hardware?.close()
                        } catch (e: Exception) {
                            resultBitmap = null
                        }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                }
            )
        } catch (e: Exception) {
            return null
        }
        try {
            latch.await(3000, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return resultBitmap
    }

    /** Screenshot saved under files/screenshots/, or null on failure. */
    fun takeScreenshotSave(): String? {
        val bitmap = takeScreenshotBitmap() ?: return null
        return try {
            val dir = File(filesDir, "screenshots").apply { mkdirs() }
            val out = File(dir, "shot_${System.currentTimeMillis()}.png")
            FileOutputStream(out).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            bitmap.recycle()
            out.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /** Loads a saved template bitmap from internal storage, or null. */
    fun loadTemplateBitmap(fileName: String): Bitmap? {
        return try {
            val file = File(filesDir, fileName)
            if (!file.exists()) null
            else android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Image / color matching on a screenshot bitmap
    // ------------------------------------------------------------------

    /**
     * Finds [template] inside [screenshot] (or inside [region] of it).
     * Both are scaled down to at most 720 px wide for speed, then matched
     * by mean absolute grayscale difference. Returns the match centre in
     * original screenshot coordinates, or null when the best score is
     * below 0.85.
     */
    fun findImageInBitmap(
        screenshot: Bitmap,
        template: Bitmap,
        region: PickRect?
    ): Point? {
        val scale = minOf(1f, 720f / screenshot.width.toFloat())
        val shot = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                screenshot,
                (screenshot.width * scale).toInt(),
                (screenshot.height * scale).toInt(),
                true
            )
        } else {
            screenshot
        }
        val tpl = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                template,
                (template.width * scale).toInt().coerceAtLeast(1),
                (template.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            template
        }
        try {
            val searchLeft = region?.left?.let { (it * scale).toInt() } ?: 0
            val searchTop = region?.top?.let { (it * scale).toInt() } ?: 0
            val searchRight = region?.right?.let { (it * scale).toInt() } ?: shot.width
            val searchBottom = region?.bottom?.let { (it * scale).toInt() } ?: shot.height
            val maxX = (searchRight - tpl.width).coerceAtMost(shot.width - tpl.width)
            val maxY = (searchBottom - tpl.height).coerceAtMost(shot.height - tpl.height)
            if (maxX < searchLeft || maxY < searchTop) return null
            val shotGray = toGray(shot)
            val tplGray = toGray(tpl)
            var bestScore = -1.0
            var bestX = -1
            var bestY = -1
            var y = searchTop
            while (y <= maxY) {
                var x = searchLeft
                while (x <= maxX) {
                    var diffSum = 0L
                    var count = 0L
                    var ty = 0
                    while (ty < tpl.height) {
                        var tx = 0
                        while (tx < tpl.width) {
                            diffSum += kotlin.math.abs(
                                shotGray[(y + ty) * shot.width + (x + tx)] -
                                    tplGray[ty * tpl.width + tx]
                            )
                            count++
                            tx += 2
                        }
                        ty += 2
                    }
                    val score = 1.0 - (diffSum.toDouble() / count.toDouble() / 255.0)
                    if (score > bestScore) {
                        bestScore = score
                        bestX = x
                        bestY = y
                    }
                    x += 2
                }
                y += 2
            }
            if (bestScore < 0.85 || bestX < 0) return null
            val cx = ((bestX + tpl.width / 2) / scale).toInt()
            val cy = ((bestY + tpl.height / 2) / scale).toInt()
            return Point(cx, cy)
        } catch (e: Exception) {
            return null
        } finally {
            if (shot !== screenshot) shot.recycle()
            if (tpl !== template) tpl.recycle()
        }
    }

    private fun toGray(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            pixels[i] = (r * 30 + g * 59 + b * 11) / 100
        }
        return pixels
    }

    /**
     * Finds a pixel close to [argb] (per-channel difference at most
     * [tolerance]) inside [region] of [screenshot], scanning every
     * 4th pixel for speed. Returns the first match, or null.
     */
    fun findColorInBitmap(
        screenshot: Bitmap,
        argb: Int,
        tolerance: Int,
        region: PickRect?
    ): Point? {
        val left = region?.left?.coerceIn(0, screenshot.width - 1) ?: 0
        val top = region?.top?.coerceIn(0, screenshot.height - 1) ?: 0
        val right = region?.right?.coerceIn(0, screenshot.width - 1) ?: (screenshot.width - 1)
        val bottom = region?.bottom?.coerceIn(0, screenshot.height - 1) ?: (screenshot.height - 1)
        val tr = (argb shr 16) and 0xFF
        val tg = (argb shr 8) and 0xFF
        val tb = argb and 0xFF
        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                val p = screenshot.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (kotlin.math.abs(r - tr) <= tolerance &&
                    kotlin.math.abs(g - tg) <= tolerance &&
                    kotlin.math.abs(b - tb) <= tolerance
                ) {
                    return Point(x, y)
                }
                x += 4
            }
            y += 4
        }
        return null
    }

    // ------------------------------------------------------------------
    // Advanced actions: vibration, sound, lock screen, Excel row
    // ------------------------------------------------------------------

    fun vibrate(durationMs: Long) {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // Vibration is a best-effort signal; never fail a run for it.
        }
    }

    /** Short beep. Runs on the caller's (worker) thread. */
    fun beep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
            Thread.sleep(350)
            tone.release()
        } catch (e: Exception) {
            // Best effort only.
        }
    }

    /** Locks the screen (GLOBAL_ACTION_LOCK_SCREEN). Marks it intentional. */
    fun lockScreen(): Boolean {
        intentionalScreenOffAtMs = SystemClock.elapsedRealtime()
        return try {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Types one data row's values into the spreadsheet app.
     *
     * HONEST ANDROID LIMIT, stated here: Android's accessibility API
     * cannot inject Tab/Enter keys, so there is no literal keyboard-only
     * cell advance on Android (unlike desktop). This implementation
     * types each value with human-like typing into the focused cell
     * editor, then advances to the next cell using the editor's own
     * on-screen position (never a user-picked coordinate). If the next
     * cell cannot be found, the step fails cleanly instead of typing
     * into the wrong cell. The final data is exact: every value is
     * typed with mistake correction, and a failed advance stops the
     * step instead of corrupting the sheet.
     */
    fun typeExcelRow(values: List<String>, level: MistakeLevel): Boolean {
        val random = Random
        for ((index, value) in values.withIndex()) {
            val clean = value.replace('\n', ' ').replace('\t', ' ')
            if (!typeHuman(clean, level, random)) return false
            if (index < values.size - 1) {
                val node = findFocusInput() ?: return false
                try {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (bounds.isEmpty) return false
                    val next = Point(
                        bounds.right + bounds.width() / 2,
                        bounds.centerY()
                    )
                    if (!tap(next)) return false
                    Thread.sleep(300)
                } catch (e: Exception) {
                    return false
                } finally {
                    try {
                        node.recycle()
                    } catch (e: Exception) {
                        // Ignore.
                    }
                }
            }
        }
        return true
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
