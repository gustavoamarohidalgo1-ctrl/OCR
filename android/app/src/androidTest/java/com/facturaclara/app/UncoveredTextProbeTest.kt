package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Diagnostic only: finds text the Paddle detector sees but ML Kit's page reading does not
 * cover, and records what ML Kit and Paddle read from enlarged (and, for dark boxes,
 * inverted) crops of those boxes. Mirrors the worker's upright/straighten steps. No
 * reference text is used and nothing is written to documents.
 * Arguments: sampleSelection (two-digit prefixes, comma separated), probeRun.
 */
@RunWith(AndroidJUnit4::class)
class UncoveredTextProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun readBoxesMlKitMissed() {
        val arguments = InstrumentationRegistry.getArguments()
        val run = arguments.getString("probeRun")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
            ?: "uncovered-${System.currentTimeMillis()}"
        val directory = File(context.getExternalFilesDir(null), "uncovered/$run").apply { check(mkdirs()) }
        val selection = arguments.getString("sampleSelection")?.split(',')?.toSet()
        val files = instrumentation.context.assets.list("real-receipt-photos").orEmpty()
            .filter { it.endsWith(".jpg") && (selection == null || it.take(2) in selection) }.sorted()
        OfflineOcr().use { ml ->
            PaddleTextDetector(context).use { detector ->
                PaddleOcr(context).use { paddle ->
                    for (filename in files) {
                        val bytes = instrumentation.context.assets.open("real-receipt-photos/$filename").use { it.readBytes() }
                        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size).copy(Bitmap.Config.ARGB_8888, true)
                        val sample = JSONObject().put("filename", filename)
                        try {
                            var reading = ml.recognize(bitmap)
                            if (reading.suggestedRotation != 0) {
                                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                                    Matrix().apply { setRotate(reading.suggestedRotation.toFloat()) }, true)
                                reading = ml.recognize(bitmap)
                            }
                            if (reading.suggestedRotation == 0 && reading.suggestedDeskew != 0f) {
                                bitmap = OfflineOcr.straighten(bitmap, reading.suggestedDeskew, 10_000_000L).first
                                reading = ml.recognize(bitmap)
                            }
                            val lines = reading.lines
                            val detected = detector.detect(bitmap).detections
                            val uncovered = detected.filter { coverage(it.line, lines) < .5f }
                            // Paddle bounds one request; read in chunks keeping each box's index.
                            val paddleReadings = uncovered.indices.chunked(100).flatMap { chunk ->
                                paddle.recognize(bitmap, chunk.map { uncovered[it].line }).map { it.copy(index = chunk[it.index]) }
                            }
                            sample.put("width", bitmap.width).put("height", bitmap.height)
                                .put("primary_lines", JSONArray(lines.map(::line)))
                                .put("detections", detected.size)
                                .put("uncovered", JSONArray(uncovered.mapIndexed { index, detection ->
                                    val box = detection.line
                                    val variants = JSONArray()
                                    for (scale in listOf(2, 3)) {
                                        val crop = crop(bitmap, box, scale)
                                        val dark = meanLuminance(crop) < 110
                                        val inputs = if (dark) listOf("plain" to crop, "inverted" to invert(crop)) else listOf("plain" to crop)
                                        for ((kind, image) in inputs) {
                                            val read = try { ml.recognizeRegion(image) } finally { if (image !== crop) image.recycle() }
                                            variants.put(JSONObject().put("scale", scale).put("kind", kind)
                                                .put("text", read.text)
                                                .put("lines", JSONArray(read.lines.map(::line))))
                                        }
                                        crop.recycle()
                                    }
                                    line(box).put("index", index).put("confidence_det", detection.confidence.toDouble())
                                        .put("ml_crops", variants)
                                        .put("paddle", JSONArray(paddleReadings.firstOrNull { it.index == index }?.observations?.map {
                                            JSONObject().put("variant", it.variant.name).put("text", it.text)
                                                .put("confidence", it.confidence.toDouble())
                                        }.orEmpty()))
                                }))
                        } catch (error: Exception) {
                            sample.put("error", error.toString())
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                        File(directory, filename.substringBeforeLast('.') + ".json").writeText(sample.toString(1), Charsets.UTF_8)
                        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\n$filename\n") })
                    }
                }
            }
        }
    }

    /** Share of [box]'s area inside ML Kit's fragments. */
    private fun coverage(box: OcrLine, lines: List<OcrLine>): Float {
        val area = (box.right - box.left) * (box.bottom - box.top)
        if (area <= 0f) return 1f
        val covered = lines.sumOf { line ->
            val w = min(box.right, line.right) - max(box.left, line.left)
            val h = min(box.bottom, line.bottom) - max(box.top, line.top)
            if (w > 0f && h > 0f) (w * h).toDouble() else 0.0
        }
        return min(1f, (covered / area).toFloat())
    }

    private fun crop(bitmap: Bitmap, box: OcrLine, scale: Int): Bitmap {
        val padY = (box.bottom - box.top) * .35f
        val padX = (box.bottom - box.top) * .5f
        val left = ((box.left - padX) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = ((box.top - padY) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = ((box.right + padX) * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ((box.bottom + padY) * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        val region = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val scaled = Bitmap.createScaledBitmap(region, max(32, region.width * scale), max(32, region.height * scale), true)
        if (scaled !== region) region.recycle()
        return scaled
    }

    private fun meanLuminance(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var sum = 0L
        for (p in pixels) sum += (299 * ((p shr 16) and 255) + 587 * ((p shr 8) and 255) + 114 * (p and 255)) / 1000
        return (sum / max(1, pixels.size)).toInt()
    }

    private fun invert(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        }
        Canvas(out).drawBitmap(source, 0f, 0f, paint)
        return out
    }

    private fun line(line: OcrLine) = JSONObject().put("text", line.text).put("left", line.left.toDouble())
        .put("top", line.top.toDouble()).put("right", line.right.toDouble()).put("bottom", line.bottom.toDouble())
        .put("confidence", line.confidence?.toDouble() ?: JSONObject.NULL)
}
