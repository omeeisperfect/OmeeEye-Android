package com.nimu.omeeeye.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nimu.omeeeye.R

/**
 * Base-v2 placeholder screen for the Triggers section.
 * Every toggle on this screen is disabled and labelled
 * "Coming soon": trigger execution is not implemented yet,
 * so this screen runs, schedules, and watches nothing.
 */
class TriggersActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_triggers)
    }
}
