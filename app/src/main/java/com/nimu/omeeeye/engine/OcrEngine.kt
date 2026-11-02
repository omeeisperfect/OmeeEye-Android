package com.nimu.omeeeye.engine

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device OCR (feature 9) via ML Kit's Latin text recognizer.
 *
 * Honest scope, stated here and in the UI hint: this works best on
 * clean, large, upright text. Small or skewed text may read wrong or
 * not at all — that is a recognizer limit, not a bug the app hides.
 * No text ever leaves the device: recognition runs on-device.
 *
 * [Result.text] is the full text found in the region (empty = nothing
 * found). [Result.bounds] is the bounding box of the first text block,
 * used as the tap point for "Perform click" on OCR success, or null
 * when nothing was found.
 */
object OcrEngine {

    data class Result(val text: String, val bounds: Rect?)

    private const val TIMEOUT_MS = 10000L

    fun read(bitmap: Bitmap, region: Rect?): Result {
        val cropped = crop(bitmap, region)
        val image = InputImage.fromBitmap(cropped, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var result = Result("", null)
        val latch = CountDownLatch(1)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val blocks = visionText.textBlocks
                val text = visionText.text.trim()
                val firstBounds = blocks.firstOrNull()?.boundingBox?.let { box ->
                    offset(box, region)
                }
                result = Result(text, firstBounds)
                latch.countDown()
            }
            .addOnFailureListener {
                latch.countDown()
            }
        try {
            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return result
    }

    private fun crop(bitmap: Bitmap, region: Rect?): Bitmap {
        if (region == null) return bitmap
        val left = region.left.coerceIn(0, bitmap.width - 1)
        val top = region.top.coerceIn(0, bitmap.height - 1)
        val right = region.right.coerceIn(left + 1, bitmap.width)
        val bottom = region.bottom.coerceIn(top + 1, bitmap.height)
        return try {
            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun offset(box: Rect, region: Rect?): Rect {
        if (region == null) return box
        return Rect(
            box.left + region.left,
            box.top + region.top,
            box.right + region.left,
            box.bottom + region.top
        )
    }
}
