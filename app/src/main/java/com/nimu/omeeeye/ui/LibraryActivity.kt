package com.nimu.omeeeye.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nimu.omeeeye.R

/**
 * Base-v2 placeholder screen for the Code Library section.
 * Empty state only: no snippet is saved, loaded, or typed
 * from here yet. Saving and the Type code step arrive in a
 * later stage.
 */
class LibraryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
    }
}
