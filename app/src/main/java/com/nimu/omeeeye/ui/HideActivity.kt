package com.nimu.omeeeye.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nimu.omeeeye.R

/**
 * Base-v2 placeholder screen for the Hide mode section.
 * The hide control is disabled and labelled "Coming soon".
 * Actual hiding is not implemented: this screen changes
 * nothing about the launcher icon, recent apps, or visibility.
 */
class HideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hide)
    }
}
