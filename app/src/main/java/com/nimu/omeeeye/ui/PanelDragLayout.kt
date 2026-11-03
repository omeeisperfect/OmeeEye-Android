package com.nimu.omeeeye.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * A floating-panel wrapper that makes the panel draggable by touch
 * WITHOUT breaking the buttons inside it.
 *
 * How it works: touches first go to the child under the finger (so a
 * button still receives its tap). This layout only intercepts once the
 * finger has moved past [dragThresholdPx] (10dp): the child then gets
 * ACTION_CANCEL (so its click never fires) and the layout itself takes
 * over the drag, reporting the new panel position through
 * [onDragPosition]. A tap that never moves past the threshold is never
 * intercepted, so every existing button click behaves exactly as
 * before. A drag that starts on empty panel padding works the same
 * way: no child consumes the DOWN, so this layout's own onTouchEvent
 * handles the whole gesture.
 *
 * The panel position itself lives with the caller: [dragOrigin]
 * returns the current position (window x/y for FloatingService panels,
 * wrapper margins for the record bar) at drag start, and the caller
 * applies each reported position (and persists it in [onDragEnd]).
 */
class PanelDragLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** Current panel position at drag start, as (x, y). */
    var dragOrigin: (() -> Pair<Int, Int>)? = null

    /** Called on every drag move with the new (x, y) position. */
    var onDragPosition: ((Int, Int) -> Unit)? = null

    /** Called once when a drag ends, so the caller can persist. */
    var onDragEnd: (() -> Unit)? = null

    /** Drag-vs-tap threshold: 10dp. Below it, a touch is a tap. */
    private val dragThresholdPx: Float =
        10f * context.resources.displayMetrics.density

    private var downRawX = 0f
    private var downRawY = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var dragging = false
    private var downSeen = false

    private fun recordDown(rawX: Float, rawY: Float) {
        downRawX = rawX
        downRawY = rawY
        val origin = dragOrigin?.invoke() ?: (0 to 0)
        offsetX = rawX - origin.first
        offsetY = rawY - origin.second
        dragging = false
        downSeen = true
    }

    private fun reset() {
        dragging = false
        downSeen = false
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                recordDown(event.rawX, event.rawY)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!downSeen) return false
                if (!dragging &&
                    hypot(event.rawX - downRawX, event.rawY - downRawY) > dragThresholdPx
                ) {
                    // Steal the gesture: the child under the finger
                    // gets ACTION_CANCEL, so its click never fires.
                    dragging = true
                    return true
                }
                return dragging
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                reset()
                return false
            }
            else -> return false
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // No child consumed the DOWN (empty panel area):
                // claim the gesture so the MOVE/UP events arrive here.
                recordDown(event.rawX, event.rawY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!downSeen) return false
                if (!dragging &&
                    hypot(event.rawX - downRawX, event.rawY - downRawY) > dragThresholdPx
                ) {
                    dragging = true
                }
                if (dragging) {
                    onDragPosition?.invoke(
                        (event.rawX - offsetX).toInt(),
                        (event.rawY - offsetY).toInt()
                    )
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasDragging = dragging
                reset()
                if (wasDragging) onDragEnd?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                reset()
                return true
            }
            else -> return false
        }
    }
}
