package com.nimu.omeeeye.ui

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.WorkflowStore
import com.nimu.omeeeye.model.Point
import com.nimu.omeeeye.model.Step
import com.nimu.omeeeye.model.StepType
import org.json.JSONArray
import kotlin.math.hypot

/**
 * Transparent full-screen gesture recorder (theme set in the manifest).
 * Only touches on [record_touch_area] are recorded; the Done button handles
 * its own clicks and the hint label never consumes a touch, so neither can
 * leak a bogus step into the recording.
 *
 * On ACTION_UP a short, near-stationary touch becomes a TAP step, anything
 * else a SWIPE step (start, end, duration clamped 100..5000ms). Done returns
 * RESULT_OK with extra "steps_json" (a JSONArray string built with
 * [WorkflowStore.stepToJson]); back press returns RESULT_CANCELED via the
 * default finish path. There is no text input for coordinates anywhere.
 */
class RecordActivity : AppCompatActivity() {

    private val recorded = mutableListOf<Step>()

    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var maxDistancePx = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var tracking = false
    // Absolute-screen offset of the touch area (it sits below the status
    // bar, so raw event coordinates would replay too high without this).
    private var offsetX = 0f
    private var offsetY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record)

        findViewById<Button>(R.id.btn_record_done).setOnClickListener {
            val arr = JSONArray()
            recorded.forEach { arr.put(WorkflowStore.stepToJson(it)) }
            val result = Intent().apply {
                putExtra("steps_json", arr.toString())
            }
            setResult(RESULT_OK, result)
            finish()
        }

        findViewById<View>(R.id.record_touch_area).setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val loc = IntArray(2)
                    view.getLocationOnScreen(loc)
                    offsetX = loc[0].toFloat()
                    offsetY = loc[1].toFloat()
                    downX = offsetX + event.x
                    downY = offsetY + event.y
                    lastX = downX
                    lastY = downY
                    downTimeMs = event.eventTime
                    maxDistancePx = 0f
                    tracking = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (tracking) {
                        lastX = offsetX + event.x
                        lastY = offsetY + event.y
                        val d = hypot(lastX - downX, lastY - downY)
                        if (d > maxDistancePx) maxDistancePx = d
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (tracking) {
                        tracking = false
                        val durationMs = event.eventTime - downTimeMs
                        val upX = (offsetX + event.x).toInt()
                        val upY = (offsetY + event.y).toInt()
                        if (maxDistancePx < TAP_MAX_DISTANCE_PX && durationMs < TAP_MAX_DURATION_MS) {
                            recorded.add(
                                Step(
                                    id = WorkflowStore.newId(),
                                    type = StepType.TAP,
                                    point = Point(upX, upY),
                                    waitAfterMs = DEFAULT_WAIT_AFTER_MS
                                )
                            )
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
                        }
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
    }

    private companion object {
        const val TAP_MAX_DISTANCE_PX = 24f
        const val TAP_MAX_DURATION_MS = 600L
        const val MIN_SWIPE_DURATION_MS = 100L
        const val MAX_SWIPE_DURATION_MS = 5000L
        const val DEFAULT_WAIT_AFTER_MS = 2000L
    }
}
