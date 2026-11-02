package com.nimu.omeeeye.ui

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nimu.omeeeye.R
import com.nimu.omeeeye.engine.AutomationService
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Translucent full-screen region picker (theme set in the manifest, the
 * same TranslucentTheme as PickPointActivity).
 *
 * The user DRAGS on screen to draw a rectangle; on release the rectangle
 * is returned in absolute screen pixels (view location on screen plus
 * event coordinates — the same conversion as PickPointActivity, so the
 * rect lines up with replayed gestures and with screenshots).
 *
 * There is deliberately NO text input for coordinates anywhere in this
 * activity: regions, template areas and color areas are only ever drawn
 * on screen. This layout is built in code (no XML file) so the activity
 * stays self-contained.
 *
 * Intent extra "mode" (String):
 * - "region":   RESULT_OK with Int extras left, top, right, bottom.
 * - "template": as "region", plus the rect is cropped out of a screenshot
 *               and saved to filesDir/templates/<uuid>.png; the result
 *               also carries "templatePath" = "templates/<uuid>.png"
 *               (the relative path AutomationService.loadTemplateBitmap
 *               resolves against filesDir).
 * - "color":    as "region", plus the CENTRE pixel of the rect is sampled
 *               from a screenshot and returned as Int extra "colorValue"
 *               (ARGB).
 *
 * For "template" and "color", when the accessibility service is not
 * running or the screenshot comes back null, an honest Toast is shown
 * and RESULT_CANCELED is returned — never a fake template or color.
 * Screenshots are taken on a background thread: the screenshot callback
 * runs on the main thread, so calling it from the UI thread would
 * deadlock until its timeout and always fail.
 *
 * Cancel button (or back press) returns RESULT_CANCELED.
 */
class PickRegionActivity : AppCompatActivity() {

    private lateinit var drawView: RegionDrawView

    // Drag state, in absolute screen pixels.
    private var startX: Int = 0
    private var startY: Int = 0
    private var currentX: Int = 0
    private var currentY: Int = 0
    private var dragging: Boolean = false

    private val mode: String
        get() = intent.getStringExtra("mode") ?: "region"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        drawView = RegionDrawView().apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(drawView)

        val hintText = when (mode) {
            "template" -> getString(R.string.rec_pick_template_hint)
            "color" -> getString(R.string.rec_pick_color_hint)
            else -> getString(R.string.rec_pick_region_hint)
        }
        val density = resources.displayMetrics.density
        val hint = TextView(this).apply {
            text = hintText
            setTextColor(resources.getColor(R.color.overlay_bar_text, theme))
            setBackgroundColor(resources.getColor(R.color.overlay_bar, theme))
            alpha = 0.9f
            textSize = 16f
            val pad = (16 * density).toInt()
            setPadding(pad, (10 * density).toInt(), pad, (10 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (48 * density).toInt()
            }
        }
        root.addView(hint)

        val cancel = Button(this).apply {
            text = getString(R.string.cancel)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (48 * density).toInt()
            }
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        root.addView(cancel)

        // The Cancel button consumes its own touches, so this listener
        // only ever sees drags on the bare screen.
        drawView.setOnTouchListener { view, event ->
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val absX = (loc[0] + event.x).toInt()
            val absY = (loc[1] + event.y).toInt()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = absX
                    startY = absY
                    currentX = absX
                    currentY = absY
                    dragging = true
                    drawView.setRect(null)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    currentX = absX
                    currentY = absY
                    drawView.setRect(normalisedRect())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) return@setOnTouchListener false
                    dragging = false
                    currentX = absX
                    currentY = absY
                    val rect = normalisedRect()
                    drawView.setRect(rect)
                    if (rect.width() < 10 || rect.height() < 10) {
                        drawView.setRect(null)
                        Toast.makeText(
                            this,
                            R.string.rec_region_too_small,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        finishDrag(rect)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    drawView.setRect(null)
                    true
                }
                else -> false
            }
        }

        setContentView(root)
    }

    private fun normalisedRect(): Rect {
        val left = minOf(startX, currentX)
        val top = minOf(startY, currentY)
        val right = maxOf(startX, currentX)
        val bottom = maxOf(startY, currentY)
        return Rect(left, top, right, bottom)
    }

    private fun finishDrag(rect: Rect) {
        when (mode) {
            "template", "color" -> finishDragWithScreenshot(rect)
            else -> returnRegion(rect, null)
        }
    }

    private fun finishDragWithScreenshot(rect: Rect) {
        val service = AutomationService.instance
        if (service == null) {
            Toast.makeText(
                this,
                R.string.rec_capture_no_service,
                Toast.LENGTH_LONG
            ).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        // Screenshot callback runs on the main thread: never call this
        // from the UI thread (see class doc). Work on a plain thread.
        Thread {
            val shot = try {
                service.takeScreenshotBitmap()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (shot == null) {
                    Toast.makeText(
                        this,
                        R.string.rec_capture_failed,
                        Toast.LENGTH_LONG
                    ).show()
                    setResult(RESULT_CANCELED)
                    finish()
                    return@runOnUiThread
                }
                try {
                    if (mode == "template") {
                        saveTemplateAndReturn(rect, shot)
                    } else {
                        sampleColorAndReturn(rect, shot)
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        R.string.rec_capture_failed,
                        Toast.LENGTH_LONG
                    ).show()
                    setResult(RESULT_CANCELED)
                    finish()
                } finally {
                    try {
                        shot.recycle()
                    } catch (e: Exception) {
                        // Ignore recycle failures.
                    }
                }
            }
        }.start()
    }

    private fun saveTemplateAndReturn(rect: Rect, shot: android.graphics.Bitmap) {
        val left = rect.left.coerceIn(0, shot.width - 1)
        val top = rect.top.coerceIn(0, shot.height - 1)
        val right = rect.right.coerceIn(left + 1, shot.width)
        val bottom = rect.bottom.coerceIn(top + 1, shot.height)
        if (right <= left || bottom <= top) {
            Toast.makeText(this, R.string.rec_capture_failed, Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
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
            val result = Intent().apply {
                putExtra("left", rect.left)
                putExtra("top", rect.top)
                putExtra("right", rect.right)
                putExtra("bottom", rect.bottom)
                putExtra("templatePath", "templates/$fileName")
            }
            setResult(RESULT_OK, result)
        } finally {
            try {
                cropped.recycle()
            } catch (e: Exception) {
                // Ignore recycle failures.
            }
        }
        finish()
    }

    private fun sampleColorAndReturn(rect: Rect, shot: android.graphics.Bitmap) {
        val cx = ((rect.left + rect.right) / 2).coerceIn(0, shot.width - 1)
        val cy = ((rect.top + rect.bottom) / 2).coerceIn(0, shot.height - 1)
        val colorValue = shot.getPixel(cx, cy)
        val result = Intent().apply {
            putExtra("left", rect.left)
            putExtra("top", rect.top)
            putExtra("right", rect.right)
            putExtra("bottom", rect.bottom)
            putExtra("colorValue", colorValue)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun returnRegion(rect: Rect, extra: Intent?) {
        val result = (extra ?: Intent()).apply {
            putExtra("left", rect.left)
            putExtra("top", rect.top)
            putExtra("right", rect.right)
            putExtra("bottom", rect.bottom)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    /** Draws the rectangle currently being dragged, nothing else. */
    private inner class RegionDrawView : View(this@PickRegionActivity) {
        private var rect: Rect? = null
        private val fillPaint = Paint().apply {
            color = resources.getColor(R.color.accent, theme)
            alpha = 60
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint().apply {
            color = resources.getColor(R.color.accent, theme)
            strokeWidth = 3 * resources.displayMetrics.density
            style = Paint.Style.STROKE
        }

        fun setRect(value: Rect?) {
            rect = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = rect ?: return
            // The rect is in absolute screen pixels; this view sits below
            // the status bar, so shift it back into view coordinates.
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val viewRect = Rect(
                r.left - loc[0],
                r.top - loc[1],
                r.right - loc[0],
                r.bottom - loc[1]
            )
            canvas.drawRect(viewRect, fillPaint)
            canvas.drawRect(viewRect, strokePaint)
        }
    }
}
