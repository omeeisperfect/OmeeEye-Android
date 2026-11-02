package com.nimu.omeeeye.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.nimu.omeeeye.R
import com.nimu.omeeeye.engine.AutomationService
import java.io.File
import java.io.FileOutputStream

/**
 * Screenshot preview (item 8), honouring the Wave-1 contract: started
 * with FLAG_ACTIVITY_NEW_TASK and String extra "path" = saved
 * screenshot file path (the runner already does this; the runner is
 * untouched). Shows the saved image with Share (system share sheet
 * for the image, via FileProvider), Scroll (long screenshot) and
 * Close (back to the previous screen).
 *
 * Scroll (long screenshot): the preview moves its own task to the
 * back so the app underneath becomes the active window again, then
 * scrolls that window's scrollable content with
 * AutomationService.scrollList, capturing a frame after each scroll,
 * and stitches the distinct frames vertically into one image, which
 * is saved and displayed here. If no scrollable content is found or
 * any capture fails before two distinct frames exist, it shows
 * "Long screenshot is not available on this device" and keeps the
 * single shot — never a fake stitched image. Frames repeat the
 * screen's overlap between scrolls (full frames are stacked, not
 * overlap-trimmed), so a stitched image may show repeated bands
 * where consecutive frames overlapped.
 */
class ScreenshotPreviewActivity : AppCompatActivity() {

    private lateinit var previewImage: ImageView
    private lateinit var btnScroll: Button
    private var currentPath: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screenshot_preview)
        previewImage = findViewById(R.id.preview_image)
        btnScroll = findViewById(R.id.btn_preview_scroll)
        findViewById<Button>(R.id.btn_preview_share).setOnClickListener { shareImage() }
        btnScroll.setOnClickListener { startLongScreenshot() }
        findViewById<Button>(R.id.btn_preview_close).setOnClickListener { finish() }
        currentPath = intent.getStringExtra("path") ?: ""
        loadImage(currentPath)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val path = intent.getStringExtra("path") ?: return
        if (path.isNotBlank()) {
            currentPath = path
            loadImage(path)
        }
    }

    private fun resolveFile(path: String): File? {
        if (path.isBlank()) return null
        val direct = File(path)
        if (direct.exists()) return direct
        val underFiles = File(filesDir, path)
        if (underFiles.exists()) return underFiles
        return null
    }

    private fun loadImage(path: String) {
        val file = resolveFile(path)
        if (file == null) {
            Toast.makeText(
                this, R.string.screenshot_preview_missing, Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap == null) {
                Toast.makeText(
                    this, R.string.screenshot_preview_missing, Toast.LENGTH_LONG
                ).show()
                finish()
            } else {
                previewImage.setImageBitmap(bitmap)
            }
        } catch (e: Exception) {
            Toast.makeText(
                this, R.string.screenshot_preview_missing, Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun shareImage() {
        val file = resolveFile(currentPath)
        if (file == null) {
            Toast.makeText(
                this, R.string.screenshot_preview_missing, Toast.LENGTH_SHORT
            ).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                this, "com.nimu.omeeeye.fileprovider", file
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(
                Intent.createChooser(
                    share, getString(R.string.screenshot_preview_share_title)
                )
            )
        } catch (e: Exception) {
            Toast.makeText(
                this, R.string.screenshot_preview_missing, Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun startLongScreenshot() {
        if (AutomationService.instance == null) {
            Toast.makeText(
                this, R.string.screenshot_preview_long_unavailable,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        btnScroll.isEnabled = false
        // Reveal the app underneath: the preview task moves to the
        // back, so the next captures + scrolls act on that app's
        // window, not on this preview.
        moveTaskToBack(true)
        mainHandler.postDelayed({
            Thread {
                val stitched = stitchFrames()
                mainHandler.post {
                    // User closed the preview mid-stitch: do not
                    // reopen it. The stitched file (if any) stays saved.
                    if (isFinishing || isDestroyed) return@post
                    btnScroll.isEnabled = true
                    if (stitched != null) {
                        currentPath = stitched.absolutePath
                        bringToFrontWithPath(stitched.absolutePath)
                        Toast.makeText(
                            this, R.string.screenshot_preview_stitched,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        bringToFrontWithPath(currentPath)
                        Toast.makeText(
                            this, R.string.screenshot_preview_long_unavailable,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        }, 700L)
    }

    private fun bringToFrontWithPath(path: String) {
        try {
            val intent = Intent(this, ScreenshotPreviewActivity::class.java).apply {
                putExtra("path", path)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            // The preview is gone; the stitched file (if any) stays
            // saved under files/screenshots/ and nothing is faked.
            if (!isFinishing) loadImage(path)
        }
    }

    /**
     * Captures, scrolls, captures (up to 5 frames). Returns the saved
     * stitched file, or null when no scrollable content was found or
     * fewer than two distinct frames were captured.
     */
    private fun stitchFrames(): File? {
        val service = AutomationService.instance ?: return null
        val frames = mutableListOf<Bitmap>()
        try {
            val first = try {
                service.takeScreenshotBitmap()
            } catch (e: Exception) {
                null
            } ?: return null
            frames.add(first)
            var scrolled = false
            for (i in 1..4) {
                val found = try {
                    service.scrollList("DOWN", 1)
                } catch (e: Exception) {
                    false
                }
                if (!found) break
                scrolled = true
                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                val next = try {
                    service.takeScreenshotBitmap()
                } catch (e: Exception) {
                    null
                } ?: break
                // End of content: the frame did not change.
                if (frames.any { it.sameAs(next) }) {
                    try {
                        next.recycle()
                    } catch (e: Exception) {
                        // Ignore.
                    }
                    break
                }
                frames.add(next)
            }
            if (!scrolled || frames.size < 2) return null
            val width = frames.maxOf { it.width }
            val height = frames.sumOf { it.height }
            val stitched = Bitmap.createBitmap(
                width, height, Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(stitched)
            var y = 0
            for (frame in frames) {
                canvas.drawBitmap(frame, 0f, y.toFloat(), null)
                y += frame.height
            }
            val dir = File(filesDir, "screenshots").apply { mkdirs() }
            val out = File(dir, "stitched_${System.currentTimeMillis()}.png")
            FileOutputStream(out).use { stream ->
                if (!stitched.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    return null
                }
            }
            return out
        } catch (e: Exception) {
            return null
        } finally {
            for (frame in frames) {
                try {
                    frame.recycle()
                } catch (e: Exception) {
                    // Ignore recycle failures.
                }
            }
        }
    }
}
