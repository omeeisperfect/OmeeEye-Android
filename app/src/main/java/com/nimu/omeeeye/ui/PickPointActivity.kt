package com.nimu.omeeeye.ui

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nimu.omeeeye.R

/**
 * Transparent full-screen point picker (theme set in the manifest).
 * The ONLY way coordinates enter the app: the user taps on screen, and
 * the tap position is returned. There is deliberately no text input for
 * coordinates anywhere in this activity.
 *
 * Result: RESULT_OK with Int extras "x" and "y", or RESULT_CANCELED
 * (Cancel button, or back press via the default finish path).
 */
class PickPointActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pick_point)

        val hint = intent.getStringExtra("hint") ?: getString(R.string.pick_hint)
        findViewById<TextView>(R.id.pick_hint).text = hint

        findViewById<Button>(R.id.btn_pick_cancel).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        // Taps on the Cancel button are consumed by the button itself, so
        // this listener only ever sees taps on the bare screen.
        // Coordinates are converted to absolute screen pixels: the view sits
        // below the status bar, so raw event coordinates would land too high
        // when replayed as gestures.
        val pickRoot = findViewById<FrameLayout>(R.id.pick_root)
        pickRoot.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_UP -> {
                    val loc = IntArray(2)
                    view.getLocationOnScreen(loc)
                    val result = Intent().apply {
                        putExtra("x", (loc[0] + event.x).toInt())
                        putExtra("y", (loc[1] + event.y).toInt())
                    }
                    setResult(RESULT_OK, result)
                    finish()
                    true
                }
                else -> false
            }
        }
    }
}
