package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Diagnostic only, for the hardest bundled photos: (1) ML Kit on 2x/3x/4x enlargements
 * of small photos, (2) re-reading tall, narrow fragments (vertical text) after a quarter
 * turn in both directions. No reference text is used; nothing is imported or exported.
 */
@RunWith(AndroidJUnit4::class)
class HardCasesProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun recordScalesAndVerticalRereads() {
        val run = InstrumentationRegistry.getArguments().getString("probeRun")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: "hard-${System.currentTimeMillis()}"
        val directory = File(context.getExternalFilesDir(null), "hard-cases/$run").apply { check(mkdirs()) }
        val files = instrumentation.context.assets.list("real-receipt-photos").orEmpty().filter { it.endsWith(".jpg") }.sorted()
        OfflineOcr().use { ml ->
            for (filename in files) {
                val bytes = instrumentation.context.assets.open("real-receipt-photos/$filename").use { it.readBytes() }
                val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size).copy(Bitmap.Config.ARGB_8888, false)
                val sample = JSONObject().put("filename", filename).put("width", original.width).put("height", original.height)
                try {
                    if (minOf(original.width, original.height) < 700) {
                        sample.put("scales", JSONArray(listOf(1, 2, 3, 4).map { factor ->
                            val scaled = if (factor == 1) original else
                                Bitmap.createScaledBitmap(original, original.width * factor, original.height * factor, true)
                            try {
                                val reading = ml.recognizeRegion(scaled)
                                JSONObject().put("factor", factor).put("text", reading.text)
                                    .put("lines", JSONArray(reading.lines.map(::line)))
                            } finally { if (scaled !== original) scaled.recycle() }
                        }))
                    }
                    val primary = ml.recognize(original)
                    sample.put("vertical", JSONArray(primary.lines.filter { (it.bottom - it.top) * original.height >=
                        2.5f * (it.right - it.left) * original.width }.map { fragment ->
                        val margin = (fragment.right - fragment.left) * original.width * .6f
                        val left = (fragment.left * original.width - margin).toInt().coerceAtLeast(0)
                        val right = (fragment.right * original.width + margin).toInt().coerceAtMost(original.width)
                        val top = (fragment.top * original.height - margin).toInt().coerceAtLeast(0)
                        val bottom = (fragment.bottom * original.height + margin).toInt().coerceAtMost(original.height)
                        val crop = Bitmap.createBitmap(original, left, top, right - left, bottom - top)
                        JSONObject().put("fragment", line(fragment)).put("readings", JSONArray(listOf(90f, -90f).map { degrees ->
                            val turned = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, Matrix().apply { setRotate(degrees) }, true)
                            val padded = Bitmap.createBitmap(turned.width * 2 + 48, turned.height * 2 + 48, Bitmap.Config.ARGB_8888)
                            Canvas(padded).apply {
                                drawColor(Color.WHITE)
                                drawBitmap(Bitmap.createScaledBitmap(turned, turned.width * 2, turned.height * 2, true), 24f, 24f, null)
                            }
                            try {
                                val reading = ml.recognizeRegion(padded)
                                JSONObject().put("degrees", degrees).put("text", reading.text)
                                    .put("confidence", reading.lines.mapNotNull { it.confidence }.average().takeIf { !it.isNaN() } ?: JSONObject.NULL)
                            } finally { padded.recycle(); if (turned !== crop) turned.recycle() }
                        }))
                    }))
                } catch (error: Exception) {
                    sample.put("error", error.toString())
                }
                File(directory, filename.removeSuffix(".jpg") + ".json").writeText(sample.toString(1), Charsets.UTF_8)
            }
        }
    }

    private fun line(line: OcrLine) = JSONObject().put("text", line.text).put("left", line.left.toDouble())
        .put("top", line.top.toDouble()).put("right", line.right.toDouble()).put("bottom", line.bottom.toDouble())
        .put("slope", line.slope.toDouble()).put("confidence", line.confidence?.toDouble() ?: JSONObject.NULL)
}
